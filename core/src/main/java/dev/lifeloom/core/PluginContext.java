package dev.lifeloom.core;

/**
 * 核心提供给插件的上下文。
 *
 * <p>M2 起提供：机制注册、机制状态访问、钩子调用（经权限闸门）、闸门注册与用户询问
 * （后两者仅系统插件可用）；M3 起提供：事件发射 / 订阅与机制状态变更聆听。
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
     * 监听某机制的状态变更（写入与删除时通知；同值重复写入不通知）。
     *
     * <p>他方机制需经权限闸门放行；监听随当前插件卸载 / 替换自动解除。
     */
    void watchState(String mechanismId, StateChangeListener listener);

    /**
     * 调用指定钩子。
     *
     * <p>调用其他插件的钩子时，非系统插件需经权限闸门放行，未放行时抛出异常；
     * 调用自己插件的钩子直接执行。
     */
    void invokeHook(String hookId) throws Exception;

    /**
     * 发射事件（运行期使用；同步分发给全部订阅者）。
     *
     * <p>发射经执行闸门：插件替换（drain）期间的新发射会被拒绝。
     */
    void emit(String eventId, String payload);

    /**
     * 订阅事件（建议在装载期订阅）。
     *
     * <p>订阅绑定当前插件；卸载 / 替换时自动解除。
     */
    void subscribe(String eventId, EventBus.Listener listener);

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
