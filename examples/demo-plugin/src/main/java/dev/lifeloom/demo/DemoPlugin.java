package dev.lifeloom.demo;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.List;

/**
 * 示例插件（M0）：注册一个“问候”机制，包含一个 hello 钩子。
 * 用途：验证核心的“装载 → 注册 → 调用 → 卸载”流程。
 */
public final class DemoPlugin implements Plugin {

    private static final String MECHANISM_ID = "dev.lifeloom.demo.greeting";
    private static final String HOOK_HELLO = MECHANISM_ID + ".hello";

    @Override
    public void onLoad(PluginContext context) {
        Hook hello = new Hook() {
            @Override
            public String id() {
                return HOOK_HELLO;
            }

            @Override
            public void invoke(HookContext hookContext) {
                System.out.println("[demo-plugin] Hello from Lifeloom! 钩子=" + hookContext.hookId());
            }
        };
        context.registerMechanism(new Mechanism(MECHANISM_ID, "问候", List.of(hello)));
    }

    @Override
    public void onUnload() {
        System.out.println("[demo-plugin] DemoPlugin 已卸载。");
    }
}