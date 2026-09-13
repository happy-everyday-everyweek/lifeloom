package dev.lifeloom.core;

/**
 * 插件主接口（SPI）。
 *
 * <p>插件包内由清单 {@code mainClass} 指向的主类必须实现本接口。
 * 核心装载插件时实例化主类并调用 {@link #onLoad(PluginContext)}；
 * 卸载时调用 {@link #onUnload()}。
 *
 * <p>M0 约定：插件只通过 {@link PluginContext} 与核心交互。
 */
public interface Plugin {

    /**
     * 插件被装载时调用。插件在此注册自己的机制与钩子。
     *
     * @param context 核心提供的上下文
     */
    void onLoad(PluginContext context) throws Exception;

    /**
     * 插件被卸载时调用。默认空实现；插件应在此释放自己持有的资源。
     */
    default void onUnload() throws Exception {
    }
}