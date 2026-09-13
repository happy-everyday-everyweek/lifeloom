package dev.lifeloom.core;

import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/**
 * 测试辅助：构造测试插件与已装载句柄。
 */
final class TestPlugins {

    private TestPlugins() {
    }

    /** 构造一个注册单机制的插件；钩子行为由 hookBody 提供。 */
    static Plugin withHook(String mechanismId, String hookId, Consumer<HookContext> hookBody) {
        return withHook(mechanismId, hookId, null, hookBody);
    }

    /** 同上，且允许自定义 onLoad 行为（onLoadBody 可为 null）。 */
    static Plugin withHook(String mechanismId, String hookId,
                           Consumer<PluginContext> onLoadBody, Consumer<HookContext> hookBody) {
        return new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                if (onLoadBody != null) {
                    onLoadBody.accept(context);
                }
                context.registerMechanism(new Mechanism(mechanismId, mechanismId, List.of(new Hook() {
                    @Override
                    public String id() {
                        return hookId;
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                        hookBody.accept(hookContext);
                    }
                })));
            }
        };
    }

    /** 包装为已装载句柄。 */
    static LoadedPlugin loaded(String pluginId, String version, Plugin instance, String sourcePath) {
        PluginDescriptor descriptor = new PluginDescriptor(
                pluginId, "Test Plugin " + pluginId, version, instance.getClass().getName());
        return new LoadedPlugin(descriptor, TestPlugins.class.getClassLoader(),
                instance, Paths.get(sourcePath));
    }
}