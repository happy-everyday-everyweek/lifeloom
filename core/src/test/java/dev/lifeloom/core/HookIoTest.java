package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * M3 收尾：插件间调用协议（接缝 = Core / PluginContext 的调用接口与钩子解析器接口）。
 * 覆盖：输入 / 输出往返、跨插件取回输出、解析器跳过与重定向、解析随卸载移除、成环拒绝、权限。
 */
public class HookIoTest {

    @Test
    public void inputAndOutputRoundTrip() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("echo.jar", path -> TestPlugins.loaded("io.echo", "1.0", echoPlugin(), path));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("echo.jar"));
            assertEquals("echo:hi", core.invokeHook("io.echo.m.h", "hi"));
            assertNull("无输入无输出时应返回 null", core.invokeHook("io.echo.m.h", null));
        } finally {
            core.close();
        }
    }

    @Test
    public void callerReceivesOutputViaContext() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> got = new AtomicReference<>();
        loader.register("echo.jar", path -> TestPlugins.loaded("io.echo", "1.0", echoPlugin(), path));
        loader.register("caller.jar", path -> TestPlugins.loaded("io.caller", "1.0", new Plugin() {
            private PluginContext ctx;

            @Override
            public void onLoad(PluginContext context) {
                this.ctx = context;
                context.registerMechanism(new Mechanism("io.caller.m", "调用方", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "io.caller.m.go";
                    }

                    @Override
                    public void invoke(HookContext hookContext) throws Exception {
                        got.set(ctx.invokeHook("io.echo.m.h", "ctx"));
                    }
                })));
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("echo.jar"));
            core.loadPlugin(Paths.get("caller.jar"));
            core.invokeHook("io.caller.m.go");
            assertEquals("echo:ctx", got.get());
        } finally {
            core.close();
        }
    }

    @Test
    public void resolverCanSkipHook() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> outcome = new AtomicReference<>("未被调用");
        loader.register("target.jar", path -> TestPlugins.loaded("r.target", "1.0",
                TestPlugins.withHook("r.target.m", "r.target.m.h",
                        ctx -> outcome.set("executed")), path));
        loader.register("resolver.jar", path -> TestPlugins.loaded("r.resolver", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerHookResolver(hookId -> "r.target.m.h".equals(hookId)
                        ? HookResolver.Resolution.skip()
                        : HookResolver.Resolution.execute());
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("target.jar"));
            core.loadPlugin(Paths.get("resolver.jar"));
            assertNull("被禁用的钩子应跳过执行", core.invokeHook("r.target.m.h", null));
            assertEquals("未被调用", outcome.get());
        } finally {
            core.close();
        }
    }

    @Test
    public void resolverRedirectsToReplacement() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> first = new AtomicReference<>("none");
        AtomicReference<String> second = new AtomicReference<>("none");
        loader.register("target.jar", path -> TestPlugins.loaded("rr.target", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism("rr.target.m", "原与替代", List.of(
                        new Hook() {
                            @Override
                            public String id() {
                                return "rr.target.m.orig";
                            }

                            @Override
                            public void invoke(HookContext hookContext) {
                                first.set("orig");
                            }
                        },
                        new Hook() {
                            @Override
                            public String id() {
                                return "rr.target.m.new";
                            }

                            @Override
                            public void invoke(HookContext hookContext) {
                                second.set("new");
                            }
                        })));
            }
        }, path));
        loader.register("resolver.jar", path -> TestPlugins.loaded("rr.resolver", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerHookResolver(hookId -> "rr.target.m.orig".equals(hookId)
                        ? HookResolver.Resolution.redirect("rr.target.m.new")
                        : HookResolver.Resolution.execute());
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("target.jar"));
            core.loadPlugin(Paths.get("resolver.jar"));
            core.invokeHook("rr.target.m.orig");
            assertEquals("none", first.get());
            assertEquals("new", second.get());
        } finally {
            core.close();
        }
    }

    @Test
    public void resolverRemovedOnUnload() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> outcome = new AtomicReference<>("未被调用");
        loader.register("target.jar", path -> TestPlugins.loaded("ru.target", "1.0",
                TestPlugins.withHook("ru.target.m", "ru.target.m.h",
                        ctx -> outcome.set("executed")), path));
        loader.register("resolver.jar", path -> TestPlugins.loaded("ru.resolver", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerHookResolver(hookId -> HookResolver.Resolution.skip());
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("target.jar"));
            core.loadPlugin(Paths.get("resolver.jar"));
            core.invokeHook("ru.target.m.h");
            assertEquals("未被调用", outcome.get());

            assertTrue(core.unloadPlugin("ru.resolver"));
            core.invokeHook("ru.target.m.h");
            assertEquals("解析器卸载后应恢复直接执行", "executed", outcome.get());
        } finally {
            core.close();
        }
    }

    @Test
    public void redirectChainLoopIsRejected() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("loop.jar", path -> TestPlugins.loaded("lp.target", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism("lp.m", "环", List.of(
                        new Hook() {
                            @Override
                            public String id() {
                                return "lp.m.a";
                            }

                            @Override
                            public void invoke(HookContext hookContext) {
                            }
                        },
                        new Hook() {
                            @Override
                            public String id() {
                                return "lp.m.b";
                            }

                            @Override
                            public void invoke(HookContext hookContext) {
                            }
                        })));
            }
        }, path));
        loader.register("resolver.jar", path -> TestPlugins.loaded("lp.resolver", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerHookResolver(hookId -> {
                    if ("lp.m.a".equals(hookId)) {
                        return HookResolver.Resolution.redirect("lp.m.b");
                    }
                    if ("lp.m.b".equals(hookId)) {
                        return HookResolver.Resolution.redirect("lp.m.a");
                    }
                    return HookResolver.Resolution.execute();
                });
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("loop.jar"));
            core.loadPlugin(Paths.get("resolver.jar"));
            try {
                core.invokeHook("lp.m.a");
                fail("成环的替代链应被拒绝");
            } catch (LifeloomException expected) {
                assertTrue("错误信息应指出成环: " + expected.getMessage(),
                        expected.getMessage().contains("成环"));
            }
        } finally {
            core.close();
        }
    }

    @Test
    public void thirdPartyCannotRegisterResolver() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> error = new AtomicReference<>();
        loader.register("tp.jar", path -> TestPlugins.loaded("io.tp", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                try {
                    context.registerHookResolver(hookId -> HookResolver.Resolution.execute());
                } catch (LifeloomException e) {
                    error.set(e.getMessage());
                }
            }
        }, path));

        Core core = new Core(loader);
        try {
            assertNotNull("装载应成功（异常被插件捕获）", core.loadPlugin(Paths.get("tp.jar")));
            assertTrue("三方插件注册解析器应被拒绝: " + error.get(),
                    error.get() != null && error.get().contains("仅系统插件"));
        } finally {
            core.close();
        }
    }

    /** 回声插件：把输入回显为输出（null 输入不回）。两个用例共用。 */
    private static Plugin echoPlugin() {
        return new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism("io.echo.m", "回声", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "io.echo.m.h";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                        if (hookContext.input() != null) {
                            hookContext.reply("echo:" + hookContext.input());
                        }
                    }
                })));
            }
        };
    }
}
