package dev.lifeloom.core;

/**
 * 钩子调用上下文。
 *
 * <p>M1 起提供：被调用钩子 ID、所属机制 ID、机制状态视图。
 * 后续依次加入：调用方标识、动作与参数句柄、权限校验入口、时间推进句柄等（随调度器与权限里程碑）。
 */
public final class HookContext {

    private final String hookId;
    private final String mechanismId;
    private final MechanismState state;

    public HookContext(String hookId, String mechanismId, MechanismState state) {
        this.hookId = hookId;
        this.mechanismId = mechanismId;
        this.state = state;
    }

    /** 当前被调用钩子的 ID。 */
    public String hookId() {
        return hookId;
    }

    /** 当前钩子所属机制的 ID。 */
    public String mechanismId() {
        return mechanismId;
    }

    /** 当前钩子所属机制的状态视图（按机制归属，替换不断档）。 */
    public MechanismState state() {
        return state;
    }
}