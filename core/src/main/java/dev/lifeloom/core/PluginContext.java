package dev.lifeloom.core;

/**
 * 核心提供给插件的上下文。
 *
 * <p>M0 只提供机制注册入口；参数存储、日志、权限等能力随里程碑逐步加入。
 */
public interface PluginContext {

    /** 当前插件的 ID（来自插件清单）。 */
    String pluginId();

    /**
     * 注册一个机制。
     *
     * <p>注册时核心做全局唯一性校验：机制 ID 与其中所有钩子 ID 都不得与已注册项重复。
     * 机制归属当前注册插件（插件无法替其他插件注册机制）。
     */
    void registerMechanism(Mechanism mechanism);
}