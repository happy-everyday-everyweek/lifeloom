package dev.lifeloom.core;

/**
 * 钩子：机制内的可执行单元。
 *
 * <p>钩子拥有全局唯一 ID（惯用 {@code 机制ID.钩子名} 风格），由插件提供实现。
 * 核心按钩子 ID 调用（后续里程碑由调度器统一处理触发与时序）。
 */
public interface Hook {

    /** 钩子的全局唯一 ID。 */
    String id();

    /** 执行钩子逻辑。 */
    void invoke(HookContext context) throws Exception;
}