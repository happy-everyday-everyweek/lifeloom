package dev.lifeloom.hooks;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.HookResolver;
import dev.lifeloom.core.LifeloomException;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 钩子管理插件（系统插件，运转组）：钩子的管理者。
 *
 * <p>职责（v1）：禁用与替代的登记——把“禁用某钩子”“把调用替代到新钩子”登记为
 * 钩子解析器的解析输出（支撑“禁用原钩子＋新建钩子”的推荐改法；解析随手调用经
 * 核心极薄原语执行，见 ADR-0006）。注册期检查与冲突检测随清单 / 签名机制接入；
 * 替代链的链长与成环由核心在调用时检测。
 *
 * <p>契约（v1）：
 * <ul>
 *   <li>钩子 {@code dev.lifeloom.hooks.disable}：输入钩子 ID；登记禁用（调用被跳过）</li>
 *   <li>钩子 {@code dev.lifeloom.hooks.enable}：输入钩子 ID；解除禁用</li>
 *   <li>钩子 {@code dev.lifeloom.hooks.replace}：输入 {@code 源=目标}；登记替代（调用被重定向）</li>
 *   <li>钩子 {@code dev.lifeloom.hooks.restore}：输入源钩子 ID；解除替代</li>
 *   <li>钩子 {@code dev.lifeloom.hooks.status}：输出 {@code disabled=[...];replaced=[...]}</li>
 *   <li>状态：{@code disabled:<钩子ID>} / {@code redirect:<钩子ID>}（机制 {@code dev.lifeloom.hooks}；随装卸、替换保留）</li>
 *   <li>约束：本机制自身的钩子不受管理（防自锁）；禁用优先于替代</li>
 * </ul>
 */
public final class HooksPlugin implements Plugin {

    /** 机制 ID。 */
    public static final String MECHANISM_ID = "dev.lifeloom.hooks";
    /** 登记禁用；输入钩子 ID。 */
    public static final String HOOK_DISABLE = MECHANISM_ID + ".disable";
    /** 解除禁用；输入钩子 ID。 */
    public static final String HOOK_ENABLE = MECHANISM_ID + ".enable";
    /** 登记替代；输入 源=目标。 */
    public static final String HOOK_REPLACE = MECHANISM_ID + ".replace";
    /** 解除替代；输入源钩子 ID。 */
    public static final String HOOK_RESTORE = MECHANISM_ID + ".restore";
    /** 查看登记；输出 disabled=[...];replaced=[...]。 */
    public static final String HOOK_STATUS = MECHANISM_ID + ".status";

    private static final String PREFIX_DISABLED = "disabled:";
    private static final String PREFIX_REDIRECT = "redirect:";

    private MechanismState state;

    @Override
    public void onLoad(PluginContext context) {
        state = context.stateFor(MECHANISM_ID);
        context.registerMechanism(new Mechanism(MECHANISM_ID, "钩子管理", List.of(
                Hook.of(HOOK_DISABLE, this::disable),
                Hook.of(HOOK_ENABLE, this::enable),
                Hook.of(HOOK_REPLACE, this::replace),
                Hook.of(HOOK_RESTORE, this::restore),
                Hook.of(HOOK_STATUS, this::status))));
        context.registerHookResolver(this::resolve);
        System.out.println("[hooks] 钩子管理插件已装载：禁用 / 替代登记经解析器生效");
    }

    @Override
    public void onUnload() {
        System.out.println("[hooks] 钩子管理插件卸载中：解析随本插件移除，登记保留在机制状态");
    }

    /** 解析：禁用优先，其次替代；均未登记时无意见（返回 null，行为照旧）。 */
    private HookResolver.Resolution resolve(String hookId) {
        if (state.get(PREFIX_DISABLED + hookId) != null) {
            return HookResolver.Resolution.skip();
        }
        String target = state.get(PREFIX_REDIRECT + hookId);
        if (target != null) {
            return HookResolver.Resolution.redirect(target);
        }
        return null;
    }

    /** 登记禁用。 */
    private String disable(HookContext hookContext) {
        String hookId = requireHookId(hookContext.input());
        guardOwn(hookId);
        state.put(PREFIX_DISABLED + hookId, "true");
        return "disabled=" + hookId;
    }

    /** 解除禁用。 */
    private String enable(HookContext hookContext) {
        String hookId = requireHookId(hookContext.input());
        guardOwn(hookId);
        state.remove(PREFIX_DISABLED + hookId);
        return "enabled=" + hookId;
    }

    /** 登记替代（源=目标）。 */
    private String replace(HookContext hookContext) {
        String[] pair = parseReplace(hookContext.input());
        guardOwn(pair[0]);
        guardOwn(pair[1]);
        state.put(PREFIX_REDIRECT + pair[0], pair[1]);
        return "replaced=" + pair[0] + "->" + pair[1];
    }

    /** 解除替代。 */
    private String restore(HookContext hookContext) {
        String hookId = requireHookId(hookContext.input());
        guardOwn(hookId);
        state.remove(PREFIX_REDIRECT + hookId);
        return "restored=" + hookId;
    }

    /** 查看全部登记（按 ID 排序，输出稳定）。 */
    private String status(HookContext hookContext) {
        List<String> disabled = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        for (String key : state.keys()) {
            if (key.startsWith(PREFIX_DISABLED)) {
                disabled.add(key.substring(PREFIX_DISABLED.length()));
            } else if (key.startsWith(PREFIX_REDIRECT)) {
                replaced.add(key.substring(PREFIX_REDIRECT.length()) + "->" + state.get(key));
            }
        }
        disabled.sort(null);
        replaced.sort(null);
        return "disabled=" + disabled + ";replaced=" + replaced;
    }

    private static String requireHookId(String input) {
        String hookId = input == null ? "" : input.trim();
        if (hookId.isEmpty()) {
            throw new LifeloomException("钩子 ID 无效: " + input);
        }
        return hookId;
    }

    private static String[] parseReplace(String input) {
        String text = input == null ? "" : input;
        int separator = text.indexOf('=');
        String source = separator < 0 ? "" : text.substring(0, separator).trim();
        String target = separator < 0 ? "" : text.substring(separator + 1).trim();
        if (source.isEmpty() || target.isEmpty()) {
            throw new LifeloomException("替代格式无效（应为 源=目标）: " + input);
        }
        if (source.equals(target)) {
            throw new LifeloomException("替代目标不能是自身: " + source);
        }
        return new String[]{source, target};
    }

    /** 本机制自身的钩子不受管理（防自锁：禁用 / 替代入口被禁用后无法恢复）。 */
    private static void guardOwn(String hookId) {
        if (hookId.equals(MECHANISM_ID) || hookId.startsWith(MECHANISM_ID + ".")) {
            throw new LifeloomException("不能管理钩子管理自身的钩子: " + hookId);
        }
    }
}
