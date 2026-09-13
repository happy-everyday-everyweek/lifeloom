package dev.lifeloom.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 核心提供给插件的上下文。
 *
 * <p>M2 起提供：机制注册、机制状态访问、钩子调用（经权限闸门）、闸门注册与用户询问
 * （后两者仅系统插件可用）；M3 起提供：事件发射 / 订阅、机制状态变更聆听与
 * 钩子解析器注册（仅系统插件可用）；M3 收尾起：钩子调用带输入输出；
 * 本轮起：系统插件协作原语——机制 / 插件清单查看与装载控制（仅系统插件可用，
 * 钩子执行中请求时延迟到本次调用结束后执行）。
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
     * 调用指定钩子（带输入；返回钩子输出，钩子未 reply 时为 null）。
     *
     * <p>权限与钩子解析语义同 {@link #invokeHook(String)}；输入 / 输出为字符串，
     * 格式由调用方与钩子约定。
     */
    String invokeHook(String hookId, String input) throws Exception;

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
     * 注册一个钩子解析器（仅系统插件可用）。
     *
     * <p>解析器在每次钩子调用前解析目标（直接执行 / 跳过 / 重定向），供钩子管理类插件
     * 实现“禁用与替代登记”；随本插件卸载 / 替换自动移除。非系统插件调用将抛出异常。
     */
    void registerHookResolver(HookResolver resolver);

    /**
     * 向用户展示提示并询问是/否（仅系统插件可用；经外壳的提示通道）。
     */
    boolean askUser(String message);

    /**
     * 全部已注册机制的清单快照（注册顺序；仅系统插件可用——供插件管理 / 调试工具）。
     */
    List<Mechanism> mechanisms();

    /**
     * 全部已装载插件的清单快照（装载顺序；仅系统插件可用）。
     */
    List<PluginDescriptor> plugins();

    /**
     * 装载新插件（仅系统插件可用）。
     *
     * <p>在钩子执行中调用时，操作延迟到本次调用结束后执行（随后生效，失败只记录）；
     * 直接调用（非执行中）立即执行，失败抛异常。
     */
    void loadPlugin(Path pluginFile) throws Exception;

    /**
     * 卸载插件（drain；仅系统插件可用）。延迟语义同 {@link #loadPlugin(Path)}。
     */
    void unloadPlugin(String pluginId);

    /**
     * 整体替换（热替换）插件（drain；仅系统插件可用）。延迟语义同 {@link #loadPlugin(Path)}。
     */
    void replacePlugin(String pluginId, Path newPluginFile) throws Exception;
}
