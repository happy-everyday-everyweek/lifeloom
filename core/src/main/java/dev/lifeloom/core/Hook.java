package dev.lifeloom.core;

import java.util.Objects;

/**
 * 钩子：机制内的可执行单元。
 *
 * <p>钩子拥有全局唯一 ID（惯用 {@code 机制ID.钩子名} 风格），由插件提供实现。
 * 核心按钩子 ID 调用（后续里程碑由调度器统一处理触发与时序）。
 *
 * <p>可用 {@link #of(String, Body)} 从函数快速构造：函数返回值作为调用输出（reply）。
 */
public interface Hook {

    /** 钩子的全局唯一 ID。 */
    String id();

    /** 执行钩子逻辑。 */
    void invoke(HookContext context) throws Exception;

    /**
     * 从函数快速构造钩子：函数返回值作为调用输出（reply；返回 null 即无输出）；
     * 函数可抛异常（向上传播给调用方）。
     */
    static Hook of(String id, Body body) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(body, "body");
        return new Hook() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void invoke(HookContext context) throws Exception {
                context.reply(body.apply(context));
            }
        };
    }

    /** 钩子体：接受调用上下文，返回调用输出（可为 null）。 */
    @FunctionalInterface
    interface Body {
        String apply(HookContext context) throws Exception;
    }
}
