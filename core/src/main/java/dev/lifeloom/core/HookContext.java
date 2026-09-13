package dev.lifeloom.core;

/**
 * 钩子调用上下文。
 *
 * <p>M1 起提供：被调用钩子 ID、所属机制 ID、机制状态视图。
 * M3 收尾起提供：调用输入（input）与调用输出（reply / output）——插件间调用的
 * 数据通道；字符串格式由调用方与钩子自行约定（统一约定随底层插件层细谈）。
 * 后续加入：调用方标识、时间推进句柄等。
 */
public final class HookContext {

    private final String hookId;
    private final String mechanismId;
    private final MechanismState state;
    private final String input;
    private String output;

    /** 无输入调用。 */
    public HookContext(String hookId, String mechanismId, MechanismState state) {
        this(hookId, mechanismId, state, null);
    }

    /** 带输入调用。 */
    public HookContext(String hookId, String mechanismId, MechanismState state, String input) {
        this.hookId = hookId;
        this.mechanismId = mechanismId;
        this.state = state;
        this.input = input;
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

    /** 调用输入（可为 null；由调用方提供）。 */
    public String input() {
        return input;
    }

    /** 设置调用输出（由钩子调用；调用方经调用返回值获取）。 */
    public void reply(String output) {
        this.output = output;
    }

    /** 调用输出（未经 reply 时为 null）。 */
    public String output() {
        return output;
    }
}
