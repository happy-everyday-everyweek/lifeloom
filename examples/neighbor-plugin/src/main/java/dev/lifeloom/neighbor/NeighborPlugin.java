package dev.lifeloom.neighbor;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.LifeloomException;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.List;

/**
 * 三方示例插件（邻居）：验证权限闸门对三方插件的拦截。
 *
 * <p>check 钩子依次尝试：访问他方机制（demo 插件）的状态、调用他方钩子；
 * 被拒绝时打印原因并继续。本插件为三方来源，跨边界操作须经权限闸门放行。
 */
public final class NeighborPlugin implements Plugin {

    private static final String MECHANISM_ID = "dev.lifeloom.neighbor";
    private static final String HOOK_CHECK = MECHANISM_ID + ".check";
    private static final String TARGET_MECHANISM = "dev.lifeloom.demo.greeting";
    private static final String TARGET_HOOK = TARGET_MECHANISM + ".hello";

    private PluginContext context;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        context.registerMechanism(new Mechanism(MECHANISM_ID, "邻居（三方示例）", List.of(new Hook() {
            @Override
            public String id() {
                return HOOK_CHECK;
            }

            @Override
            public void invoke(HookContext hookContext) {
                probeState();
                probeHook();
            }
        })));
        System.out.println("[neighbor] 三方示例插件已装载（跨边界操作须经权限闸门）");
    }

    @Override
    public void onUnload() {
        System.out.println("[neighbor] 已卸载。");
    }

    private void probeState() {
        System.out.println("[neighbor] 尝试访问他方机制状态: " + TARGET_MECHANISM);
        try {
            MechanismState state = context.stateFor(TARGET_MECHANISM);
            System.out.println("[neighbor] 访问成功，读取到 count=" + state.get("count"));
        } catch (LifeloomException e) {
            System.out.println("[neighbor] 被拒绝：" + e.getMessage());
        }
    }

    private void probeHook() {
        System.out.println("[neighbor] 尝试调用他方钩子: " + TARGET_HOOK);
        try {
            context.invokeHook(TARGET_HOOK);
            System.out.println("[neighbor] 调用成功");
        } catch (Exception e) {
            System.out.println("[neighbor] 被拒绝：" + e.getMessage());
        }
    }
}
