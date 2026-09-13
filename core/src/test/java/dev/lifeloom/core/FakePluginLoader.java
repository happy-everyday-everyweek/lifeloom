package dev.lifeloom.core;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试用装载器：按“包路径”提供预置插件，不进行真实类加载。
 */
final class FakePluginLoader implements PluginLoader {

    private final Map<String, Function<String, LoadedPlugin>> packages = new HashMap<>();

    /** 注册包路径 → 装载逻辑（入参为包路径字符串，返回已装载句柄）。 */
    void register(String path, Function<String, LoadedPlugin> factory) {
        packages.put(path, factory);
    }

    @Override
    public LoadedPlugin load(Path pluginFile) {
        Function<String, LoadedPlugin> factory = packages.get(pluginFile.toString());
        if (factory == null) {
            throw new LifeloomException("未知测试包: " + pluginFile);
        }
        return factory.apply(pluginFile.toString());
    }

    @Override
    public void unload(LoadedPlugin plugin) {
        // 无资源可释放。
    }
}