package dev.lifeloom.time;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.LifeloomException;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.List;

/**
 * 时间插件（系统插件，运转组）：游戏时钟的持有者。
 *
 * <p>无动作则静止——时钟只被显式推进（由动作插件驱动）；倍率仅记录，供时间映射方使用。
 * 契约（v1）：
 * <ul>
 *   <li>钩子 {@code dev.lifeloom.time.now}：输出 {@code now=<分钟>;scale=<倍率>}</li>
 *   <li>钩子 {@code dev.lifeloom.time.advance}：输入分钟数（非负整数），推进时钟并发射事件，输出同 now</li>
 *   <li>钩子 {@code dev.lifeloom.time.set-scale}：输入正整数倍率，输出 {@code scale=<倍率>}</li>
 *   <li>事件 {@code dev.lifeloom.time.advanced}：载荷为新时刻（分钟数）</li>
 *   <li>状态：{@code now} / {@code scale}（机制 {@code dev.lifeloom.time}；随装卸、替换保留）</li>
 * </ul>
 */
public final class TimePlugin implements Plugin {

    /** 机制 ID。 */
    public static final String MECHANISM_ID = "dev.lifeloom.time";
    /** 查询当前时刻；输出 now=<分钟>;scale=<倍率>。 */
    public static final String HOOK_NOW = MECHANISM_ID + ".now";
    /** 推进时钟；输入分钟数（非负整数）。 */
    public static final String HOOK_ADVANCE = MECHANISM_ID + ".advance";
    /** 设置倍率；输入正整数。 */
    public static final String HOOK_SET_SCALE = MECHANISM_ID + ".set-scale";
    /** 时间推进事件；载荷为新时刻（分钟数）。 */
    public static final String EVENT_ADVANCED = MECHANISM_ID + ".advanced";

    private static final String KEY_NOW = "now";
    private static final String KEY_SCALE = "scale";

    private PluginContext context;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        context.registerMechanism(new Mechanism(MECHANISM_ID, "时间", List.of(
                Hook.of(HOOK_NOW, this::now),
                Hook.of(HOOK_ADVANCE, this::advance),
                Hook.of(HOOK_SET_SCALE, this::setScale))));
        System.out.println("[time] 时间插件已装载（无动作则静止；由动作驱动流逝）");
    }

    @Override
    public void onUnload() {
        System.out.println("[time] 时间插件卸载中（时钟数据保留）");
    }

    /** now：输出当前时刻与倍率。 */
    private String now(HookContext hookContext) {
        return format(nowValue(hookContext.state()), scaleValue(hookContext.state()));
    }

    /** advance：推进时钟并广播事件；推进量为 0 时为空操作。 */
    private String advance(HookContext hookContext) {
        long amount = parseAmount(hookContext.input());
        MechanismState state = hookContext.state();
        long now = nowValue(state);
        if (amount > 0) {
            now += amount;
            state.put(KEY_NOW, String.valueOf(now));
            context.emit(EVENT_ADVANCED, String.valueOf(now));
        }
        return format(now, scaleValue(state));
    }

    /** set-scale：设置倍率。 */
    private String setScale(HookContext hookContext) {
        int scale = parseScale(hookContext.input());
        hookContext.state().put(KEY_SCALE, String.valueOf(scale));
        return "scale=" + scale;
    }

    private static long nowValue(MechanismState state) {
        String raw = state.get(KEY_NOW);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int scaleValue(MechanismState state) {
        String raw = state.get(KEY_SCALE);
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static long parseAmount(String input) {
        if (input == null || input.isBlank()) {
            throw new LifeloomException("时间推进量无效: " + input);
        }
        long amount;
        try {
            amount = Long.parseLong(input.trim());
        } catch (NumberFormatException e) {
            throw new LifeloomException("时间推进量无效: " + input);
        }
        if (amount < 0) {
            throw new LifeloomException("时间推进量无效: " + input);
        }
        return amount;
    }

    private static int parseScale(String input) {
        if (input == null || input.isBlank()) {
            throw new LifeloomException("倍率无效: " + input);
        }
        int scale;
        try {
            scale = Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            throw new LifeloomException("倍率无效: " + input);
        }
        if (scale <= 0) {
            throw new LifeloomException("倍率无效: " + input);
        }
        return scale;
    }

    private static String format(long now, int scale) {
        return "now=" + now + ";scale=" + scale;
    }
}
