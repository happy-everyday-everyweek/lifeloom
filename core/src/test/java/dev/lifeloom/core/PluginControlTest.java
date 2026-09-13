package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 系统插件协作原语（清单查看与装载控制）：系统 / 三方边界与延迟执行语义。
 */
public class PluginControlTest {

    @Test
    public void systemPluginListsMechanismsAndPlugins() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("a.jar", path -> TestPlugins.loaded("ctl.a", "1.0",
                TestPlugins.withHook("ctl.a.m", "ctl.a.m.h", ctx -> {
                }), path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            LoadedPlugin plugin = core.loadPlugin(Paths.get("a.jar"));
            DefaultPluginContext context = new DefaultPluginContext(plugin, core);

            List<Mechanism> mechanisms = context.mechanisms();
            assertEquals(1, mechanisms.size());
            assertEquals("ctl.a.m", mechanisms.get(0).id());

            List<PluginDescriptor> plugins = context.plugins();
            assertEquals(1, plugins.size());
            assertEquals("ctl.a", plugins.get(0).id());
            assertEquals(PluginOrigin.SYSTEM, plugins.get(0).origin());
        } finally {
            core.close();
        }
    }

    @Test
    public void thirdPartyCannotUseControlPrimitives() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("tp.jar", path -> TestPlugins.loaded("ctl.tp", "1.0",
                TestPlugins.withHook("ctl.tp.m", "ctl.tp.m.h", ctx -> {
                }), path));

        Core core = new Core(loader);
        try {
            LoadedPlugin plugin = core.loadPlugin(Paths.get("tp.jar"));
            DefaultPluginContext context = new DefaultPluginContext(plugin, core);

            for (Runnable call : List.<Runnable>of(
                    context::mechanisms,
                    context::plugins,
                    () -> context.unloadPlugin("ctl.a"))) {
                try {
                    call.run();
                    fail("三方插件不应可用该原语");
                } catch (LifeloomException expected) {
                    assertTrue("应说明仅系统插件: " + expected.getMessage(),
                            expected.getMessage().contains("仅系统插件"));
                }
            }

            try {
                context.loadPlugin(Paths.get("x.jar"));
                fail("三方插件不应可用装载原语");
            } catch (LifeloomException expected) {
                assertTrue(expected.getMessage().contains("仅系统插件"));
            }
            try {
                context.replacePlugin("ctl.a", Paths.get("x.jar"));
                fail("三方插件不应可用替换原语");
            } catch (LifeloomException expected) {
                assertTrue(expected.getMessage().contains("仅系统插件"));
            }
        } finally {
            core.close();
        }
    }

    @Test
    public void controlOpsExecuteImmediatelyOutsideExecution() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("a.jar", path -> TestPlugins.loaded("ctl.a", "1.0",
                TestPlugins.withHook("ctl.a.m", "ctl.a.m.h", ctx -> {
                }), path, PluginOrigin.SYSTEM));
        loader.register("b.jar", path -> TestPlugins.loaded("ctl.b", "1.0",
                TestPlugins.withHook("ctl.b.m", "ctl.b.m.h", ctx -> {
                }), path));

        Core core = new Core(loader);
        try {
            LoadedPlugin a = core.loadPlugin(Paths.get("a.jar"));
            DefaultPluginContext context = new DefaultPluginContext(a, core);

            context.loadPlugin(Paths.get("b.jar"));
            assertNotNull("直接调用应已装载", core.registry().findPlugin("ctl.b"));

            context.unloadPlugin("ctl.b");
            assertNull("直接调用应已卸载", core.registry().findPlugin("ctl.b"));
        } finally {
            core.close();
        }
    }

    @Test
    public void unloadRequestedInsideHookIsDeferredAndApplied() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("victim.jar", path -> TestPlugins.loaded("ctl.victim", "1.0",
                TestPlugins.withHook("ctl.victim.m", "ctl.victim.m.h", ctx -> {
                }), path, PluginOrigin.SYSTEM));
        loader.register("console.jar", path -> TestPlugins.loaded("ctl.console", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism("ctl.console.m", "控制台", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "ctl.console.m.uninstall";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                        context.unloadPlugin("ctl.victim");
                        hookContext.reply("accepted");
                    }
                })));
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("victim.jar"));
            core.loadPlugin(Paths.get("console.jar"));

            assertEquals("accepted", core.invokeHook("ctl.console.m.uninstall", null));
            assertNull("延迟卸载应已在本次调用结束后执行", core.registry().findPlugin("ctl.victim"));
            assertNotNull("请求方自身应仍在", core.registry().findPlugin("ctl.console"));
        } finally {
            core.close();
        }
    }

    @Test
    public void loadRequestedInsideHookIsDeferredAndApplied() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("extra.jar", path -> TestPlugins.loaded("ctl.extra", "1.0",
                TestPlugins.withHook("ctl.extra.m", "ctl.extra.m.h", ctx -> {
                }), path));
        loader.register("console.jar", path -> TestPlugins.loaded("ctl.console", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism("ctl.console.m", "控制台", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "ctl.console.m.install";
                    }

                    @Override
                    public void invoke(HookContext hookContext) throws Exception {
                        context.loadPlugin(Paths.get("extra.jar"));
                        hookContext.reply("accepted");
                    }
                })));
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("console.jar"));

            assertEquals("accepted", core.invokeHook("ctl.console.m.install", null));
            assertNotNull("延迟装载应已在本次调用结束后执行", core.registry().findPlugin("ctl.extra"));
        } finally {
            core.close();
        }
    }
}
