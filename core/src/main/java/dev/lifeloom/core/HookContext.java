package dev.lifeloom.core;

/**
 * 钩子调用上下文。
 *
 * <p>M0 为最小实现（仅记录被调用的钩子 ID）；后续依次加入：
 * 调用方标识、动作与参数句柄、权限校验入口、时间推进句柄等（随调度器与权限里程碑）。
 */
public final class HookContext {

    private final String hookId;

    public HookContext(String hookId) {
        this.hookId = hookId;
    }

    /** 当前被调用钩子的 ID。 */
    public String hookId() {
        return hookId;
    }
}