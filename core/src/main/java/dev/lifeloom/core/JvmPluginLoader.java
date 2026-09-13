package dev.lifeloom.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 桌面（JVM）插件装载器：每个插件一个 {@link URLClassLoader}，装载 JAR。
 *
 * <p>类隔离策略（M0，暂定）：插件类加载器的父加载器为核心自身，
 * 使插件能访问 {@code dev.lifeloom.core} 的 SPI；插件之间互不可见。
 * 更严格的类空间策略随“插件细谈”再定。
 */
public final class JvmPluginLoader implements PluginLoader {

    @Override
    public LoadedPlugin load(Path pluginFile) throws Exception {
        if (!Files.isRegularFile(pluginFile)) {
            throw new LifeloomException("插件包不存在: " + pluginFile);
        }
        PluginDescriptor descriptor = readDescriptor(pluginFile);
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{pluginFile.toUri().toURL()},
                Plugin.class.getClassLoader());
        try {
            Class<?> mainClass = Class.forName(descriptor.mainClass(), true, classLoader);
            if (!Plugin.class.isAssignableFrom(mainClass)) {
                throw new LifeloomException(
                        "主类未实现 Plugin 接口: " + descriptor.mainClass());
            }
            Plugin instance = (Plugin) mainClass.getDeclaredConstructor().newInstance();
            return new LoadedPlugin(descriptor, classLoader, instance);
        } catch (Exception e) {
            classLoader.close();
            if (e instanceof LifeloomException) {
                throw e;
            }
            throw new LifeloomException("装载插件失败: " + pluginFile, e);
        }
    }

    @Override
    public void unload(LoadedPlugin plugin) throws Exception {
        ClassLoader classLoader = plugin.classLoader();
        if (classLoader instanceof URLClassLoader) {
            ((URLClassLoader) classLoader).close();
        }
    }

    private static PluginDescriptor readDescriptor(Path pluginFile) throws IOException {
        try (ZipFile zip = new ZipFile(pluginFile.toFile())) {
            ZipEntry entry = zip.getEntry(PluginDescriptor.MANIFEST_PATH);
            if (entry == null) {
                throw new LifeloomException(
                        "插件包缺少清单 " + PluginDescriptor.MANIFEST_PATH + ": " + pluginFile);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return PluginDescriptor.read(in);
            }
        }
    }
}