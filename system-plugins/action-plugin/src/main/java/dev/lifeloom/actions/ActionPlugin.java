package dev.lifeloom.actions;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.LifeloomException;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作插件（系统插件，运转组）：动作模型与执行的主理。
 *
 * <p>职责（v1）：动作注册、可执行入口（登记判定）、执行与时间驱动——执行动作即调用同名
 * 钩子（“动作相当于钩子”），成功后按动作声明的时长推进游戏内时间（驱动时间插件；
 * 逻辑失败不耗时）。排队与打断、弹性动作的输入声明、实例附着随后续里程碑（实时节拍、
 * UI、实例成员）细化；v1 执行为同步原子，由核心单逻辑线程保证串行。
 *
 * <p>契约（v1）：
 * <ul>
 *   <li>钩子 {@code dev.lifeloom.actions.register}：输入 {@code id=<动作ID>;minutes=<非负整数>}；登记 / 覆盖动作</li>
 *   <li>钩子 {@code dev.lifeloom.actions.can}：输入动作 ID；输出 {@code can=<true|false>}（v1 判定 = 是否已登记）</li>
 *   <li>钩子 {@code dev.lifeloom.actions.execute}：输入动作 ID；执行动作逻辑并按声明的时长推进时间；输出 {@code executed=<动作ID>}</li>
 *   <li>钩子 {@code dev.lifeloom.actions.status}：输出 {@code actions=[ID(分钟), ...]}（按 ID 排序）</li>
 *   <li>状态：{@code action:<动作ID>} → 时长分钟数（机制 {@code dev.lifeloom.actions}；随装卸、替换保留）</li>
 *   <li>协作：动作逻辑 = 与动作同 ID 的钩子；时间推进经 {@code dev.lifeloom.time.advance}（时长 0 不调用）</li>
 * </ul>
 */
public final class ActionPlugin implements Plugin {

    /** 机制 ID。 */
    public static final String MECHANISM_ID = "dev.lifeloom.actions";
    /** 登记 / 覆盖动作；输入 id=<动作ID>;minutes=<非负整数>。 */
    public static final String HOOK_REGISTER = MECHANISM_ID + ".register";
    /** 可执行判定；输入动作 ID。 */
    public static final String HOOK_CAN = MECHANISM_ID + ".can";
    /** 执行动作；输入动作 ID。 */
    public static final String HOOK_EXECUTE = MECHANISM_ID + ".execute";
    /** 查看登记；输出 actions=[ID(分钟), ...]。 */
    public static final String HOOK_STATUS = MECHANISM_ID + ".status";
    /** 时间插件的推进钩子（协作契约，见时间插件）。 */
    public static final String TIME_ADVANCE = "dev.lifeloom.time.advance";

    private static final String PREFIX_ACTION = "action:";

    private PluginContext context;
    private MechanismState state;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        state = context.stateFor(MECHANISM_ID);
        context.registerMechanism(new Mechanism(MECHANISM_ID, "动作", List.of(
                Hook.of(HOOK_REGISTER, this::register),
                Hook.of(HOOK_CAN, this::can),
                Hook.of(HOOK_EXECUTE, this::execute),
                Hook.of(HOOK_STATUS, this::status))));
        System.out.println("[actions] 动作插件已装载：动作注册 / 执行 / 时间驱动就绪");
    }

    @Override
    public void onUnload() {
        System.out.println("[actions] 动作插件卸载中：动作登记保留在机制状态");
    }

    /** 登记 / 覆盖动作：id=<动作ID>;minutes=<非负整数>。 */
    private String register(HookContext hookContext) {
        String[] parsed = parseRegistration(hookContext.input());
        state.put(PREFIX_ACTION + parsed[0], parsed[1]);
        return "registered=" + parsed[0];
    }

    /** 可执行判定（v1：已登记即可执行）。 */
    private String can(HookContext hookContext) {
        String actionId = requireActionId(hookContext.input());
        return "can=" + (state.get(PREFIX_ACTION + actionId) != null);
    }

    /** 执行：动作逻辑（同名钩子）→ 按时长推进时间；逻辑失败不耗时。 */
    private String execute(HookContext hookContext) throws Exception {
        String actionId = requireActionId(hookContext.input());
        String minutes = state.get(PREFIX_ACTION + actionId);
        if (minutes == null) {
            throw new LifeloomException("动作未登记: " + actionId);
        }
        context.invokeHook(actionId, null);
        if (Integer.parseInt(minutes) > 0) {
            context.invokeHook(TIME_ADVANCE, minutes);
        }
        return "executed=" + actionId;
    }

    /** 查看全部登记（按 ID 排序，输出稳定）。 */
    private String status(HookContext hookContext) {
        List<String> actions = new ArrayList<>();
        for (String key : state.keys()) {
            if (key.startsWith(PREFIX_ACTION)) {
                actions.add(key.substring(PREFIX_ACTION.length()) + "(" + state.get(key) + ")");
            }
        }
        actions.sort(null);
        return "actions=" + actions;
    }

    private static String requireActionId(String input) {
        String actionId = input == null ? "" : input.trim();
        if (actionId.isEmpty()) {
            throw new LifeloomException("动作 ID 无效: " + input);
        }
        return actionId;
    }

    /** 解析登记输入：id=<动作ID>;minutes=<非负整数>（未知字段忽略）。 */
    private static String[] parseRegistration(String input) {
        String text = input == null ? "" : input;
        String id = null;
        String minutes = null;
        for (String segment : text.split(";")) {
            int separator = segment.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = segment.substring(0, separator).trim();
            String value = segment.substring(separator + 1).trim();
            if (key.equals("id")) {
                id = value;
            } else if (key.equals("minutes")) {
                minutes = value;
            }
        }
        if (id == null || id.isEmpty() || minutes == null) {
            throw new LifeloomException("动作格式无效（应为 id=<动作ID>;minutes=<分钟>）: " + input);
        }
        int value;
        try {
            value = Integer.parseInt(minutes);
        } catch (NumberFormatException e) {
            throw new LifeloomException("动作时长无效: " + minutes);
        }
        if (value < 0) {
            throw new LifeloomException("动作时长无效: " + minutes);
        }
        return new String[]{id, String.valueOf(value)};
    }
}
