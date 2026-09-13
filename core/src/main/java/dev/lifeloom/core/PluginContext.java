package dev.lifeloom.core;

/**
 * 核心提供给插件的上下文。
 *
 * <p>M1 起提供机制注册与机制状态访问；参数存储、日志、权限等能力随里程碑逐步加入。
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
     * <p>M1 规则：目标机制若已注册，必须属于当前插件；未注册时允许访问
     * （用于装载期读取旧版本数据做迁移）。跨机制访问的授权流程随权限里程碑加入。
     */
    MechanismState stateFor(String mechanismId);
}