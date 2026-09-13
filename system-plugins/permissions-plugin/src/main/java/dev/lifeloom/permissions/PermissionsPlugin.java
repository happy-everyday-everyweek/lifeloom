package dev.lifeloom.permissions;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.PermissionRequest;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限插件（系统插件，M2）：Lifeloom 的闸门实现，负责放行与用户提示。
 *
 * <p>职责：对非系统插件的跨边界操作请求（访问他方机制状态、调用他方钩子等）
 * 作出放行决定；未决请求经用户提示通道向用户询问，用户决定按
 * （发起方，操作，目标）记忆，同一会话内不再重复询问。
 *
 * <p>本插件为系统插件，自身操作不受闸门限制；装载时向核心注册闸门。
 */
public final class PermissionsPlugin implements Plugin {

    private static final String MECHANISM_ID = "dev.lifeloom.permissions";
    private static final String HOOK_STATUS = MECHANISM_ID + ".status";

    private final Map<String, Boolean> decisions = new LinkedHashMap<>();
    private PluginContext context;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        context.registerGatekeeper(this::decide);
        context.registerMechanism(new Mechanism(MECHANISM_ID, "权限", List.of(new Hook() {
            @Override
            public String id() {
                return HOOK_STATUS;
            }

            @Override
            public void invoke(HookContext hookContext) {
                System.out.println("[permissions] 当前记忆的决定 " + decisions.size() + " 条:");
                for (Map.Entry<String, Boolean> entry : decisions.entrySet()) {
                    System.out.println("  " + (entry.getValue() ? "放行" : "拒绝") + " ← " + entry.getKey());
                }
            }
        })));
        System.out.println("[permissions] 权限插件已装载：闸门已注册（承担放行与用户提示）");
    }

    @Override
    public void onUnload() {
        System.out.println("[permissions] 权限插件卸载中：闸门随本插件移除，"
                + "三方跨边界操作将回到 fail-closed（全部拒绝）");
    }

    /** 闸门决策：先查既有决定；未决时经用户提示通道询问并记忆。 */
    private boolean decide(PermissionRequest request) {
        String key = request.pluginId() + " → " + request.operation() + " → " + request.targetId();
        Boolean remembered = decisions.get(key);
        if (remembered != null) {
            System.out.println("[permissions] 复用既有决定（" + (remembered ? "放行" : "拒绝") + "）：" + key);
            return remembered;
        }
        boolean allowed = context.askUser("插件“" + request.pluginId() + "”请求："
                + request.detail() + "。是否放行？");
        decisions.put(key, allowed);
        System.out.println("[permissions] 用户决定：" + (allowed ? "放行" : "拒绝") + "（已记忆）");
        return allowed;
    }
}
