package dev.lifeloom.demo.v2;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.List;

/**
 * 示例插件 v2（0.2.0）：与 v1 同插件 ID（dev.lifeloom.demo）的升级包。
 *
 * <p>演示热替换：替换后机制状态（计数）从 v1 继承，不断档。
 */
public final class DemoPluginV2 implements Plugin {

    private static final String MECHANISM_ID = "dev.lifeloom.demo.greeting";
    private static final String HOOK_HELLO = MECHANISM_ID + ".hello";

    @Override
    public void onLoad(PluginContext context) {
        MechanismState state = context.stateFor(MECHANISM_ID);
        int loads = parseInt(state.get("loads")) + 1;
        state.put("loads", String.valueOf(loads));
        System.out.println("[demo-plugin v2] 已装载（该机制累计装载 " + loads + " 次，继承自旧版本）");

        Hook hello = new Hook() {
            @Override
            public String id() {
                return HOOK_HELLO;
            }

            @Override
            public void invoke(HookContext hookContext) {
                MechanismState s = hookContext.state();
                int count = parseInt(s.get("count")) + 1;
                s.put("count", String.valueOf(count));
                System.out.println("[demo-plugin v2] hello #" + count
                        + "（数据由旧版本继承；机制 " + hookContext.mechanismId() + "）");
            }
        };
        context.registerMechanism(new Mechanism(MECHANISM_ID, "问候", List.of(hello)));
    }

    @Override
    public void onUnload() {
        System.out.println("[demo-plugin v2] 已卸载。");
    }

    private static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}