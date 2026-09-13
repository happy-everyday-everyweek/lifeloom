package dev.lifeloom.core;

/**
 * 核心提供给插件的上下文。
 *
 * <p>M2 起提供：机制注册、机制状态访问、钩子调用（经权限闸门）、闸门注册与用户询问
 * （后两者仅系统插件可用）。
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

    /**
     * 获取某机制的状态视图（按机制归属，替换不断档）。
     *
     * <p>目标机制若已注册且属于其他插件，需经权限闸门放行（非系统插件未放行时抛异常）；
     * 未注册时允许访问（用于装载期读取旧版本数据做迁移）。
     */
    MechanismState stateFor(String mechanismId);

    /**
     * 调用指定钩子。
     *
     * <p>调用其他插件的钩子时，非系统插件需经权限闸门放行，未放行时抛出异常；
     * 调用自己插件的钩子直接执行。
     */
    void invokeHook(String hookId) throws Exception;

    /**
     * 注册一个闸门（仅系统插件可用）。
     *
     * <p>闸门用于横切控制（如权限）；非系统插件调用将抛出异常。
     */
    void registerGatekeeper(Gatekeeper gatekeeper);

    /**
     * 向用户展示提示并询问是/否（仅系统插件可用；经外壳的提示通道）。
     */
    boolean askUser(String message);
}