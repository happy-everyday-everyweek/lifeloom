package dev.lifeloom.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lifeloom 核心（极薄宿主）M0 实现。
 *
 * <p>当前职责：装载 / 卸载插件、维护注册表、按钩子 ID 调用钩子。
 * 热替换（M1）、权限（M2）、调度与参数存储（M3）随里程碑加入。
 */
public final class Core {

    private final PluginLoader loader;
    private final Registry registry = new Registry();

    public Core(PluginLoader loader) {
        this.loader = loader;
    }

    public Registry registry() {
        return registry;
    }

    /**
     * 扫描目录装载全部插件包（{@code *.jar}）。
     *
     * <p>单个插件装载失败不中断整体扫描：失败项记录到标准错误后跳过。
     *
     * @return 成功装载的插件数
     */
    public int loadPluginsFrom(Path pluginsDir) throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        int loaded = 0;
        for (Path file : files) {
            try {
                loadPlugin(file);
                loaded++;
            } catch (Exception e) {
                System.err.println("[core] 插件装载失败: " + file + " -> " + e.getMessage());
            }
        }
        return loaded;
    }

    /**
     * 装载单个插件：包 → 实例 → onLoad（注册机制）→ ACTIVE。
     * onLoad 失败时回滚：清理已注册内容、释放类加载器。
     */
    public LoadedPlugin loadPlugin(Path pluginFile) throws Exception {
        LoadedPlugin plugin = loader.load(pluginFile);
        try {
            registry.addPlugin(plugin);
        } catch (RuntimeException e) {
            safeUnload(plugin);
            throw e;
        }
        try {
            plugin.instance().onLoad(new DefaultPluginContext(plugin, registry));
        } catch (Exception e) {
            registry.removePlugin(plugin.descriptor().id());
            safeUnload(plugin);
            plugin.setState(LoadedPlugin.State.FAILED);
            throw new LifeloomException("插件 onLoad 失败: " + plugin.descriptor().id(), e);
        }
        plugin.setState(LoadedPlugin.State.ACTIVE);
        return plugin;
    }

    /**
     * 卸载插件：onUnload → 清理注册内容 → 释放类加载器。
     *
     * @return 是否找到并卸载了该插件
     */
    public boolean unloadPlugin(String pluginId) {
        LoadedPlugin plugin = registry.findPlugin(pluginId);
        if (plugin == null) {
            return false;
        }
        try {
            plugin.instance().onUnload();
        } catch (Exception e) {
            System.err.println("[core] 插件 onUnload 异常: " + pluginId + " -> " + e.getMessage());
        }
        registry.removePlugin(pluginId);
        safeUnload(plugin);
        plugin.setState(LoadedPlugin.State.UNLOADED);
        return true;
    }

    /** 按钩子 ID 调用钩子。 */
    public void invokeHook(String hookId) throws Exception {
        Registry.HookRef ref = registry.findHook(hookId);
        if (ref == null) {
            throw new LifeloomException("钩子未注册: " + hookId);
        }
        ref.hook().invoke(new HookContext(hookId));
    }

    /** 返回当前全部已装载插件的 ID 列表（调试用）。 */
    public List<String> pluginIds() {
        List<String> ids = new ArrayList<>();
        for (LoadedPlugin plugin : registry.plugins()) {
            ids.add(plugin.descriptor().id());
        }
        return ids;
    }

    private void safeUnload(LoadedPlugin plugin) {
        try {
            loader.unload(plugin);
        } catch (Exception e) {
            System.err.println("[core] 释放插件资源失败: "
                    + plugin.descriptor().id() + " -> " + e.getMessage());
        }
    }
}