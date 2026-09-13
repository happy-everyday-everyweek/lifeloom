package dev.lifeloom.core;

import java.nio.file.Path;

/**
 * 插件装载器：负责从插件包装载与卸载插件。
 *
 * <p>跨端两个实现：桌面版基于 URLClassLoader（{@link JvmPluginLoader}）；
 * Android 版基于 DexClassLoader（后续加入）。实现负责类空间隔离与其资源释放。
 *
 * <p>约定：{@link #load(Path)} 只做“包 → 实例”（读清单、建类加载器、实例化主类），
 * 不调用 {@link Plugin#onLoad}；生命周期编排由核心负责。
 */
public interface PluginLoader {

    /**
     * 从插件包文件装载插件实例。
     *
     * @param pluginFile 插件包文件（桌面为 JAR）
     * @return 已装载插件（状态为 LOADING）
     */
    LoadedPlugin load(Path pluginFile) throws Exception;

    /**
     * 卸载插件：释放插件实例与其类加载器所占资源。
     * 不负责注册表清理（由核心负责）。
     */
    void unload(LoadedPlugin plugin) throws Exception;
}