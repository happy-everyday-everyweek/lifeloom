package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M2 集成测试：非系统插件的跨边界操作经核心转发到闸门。
 * 覆盖：无闸门 fail-closed、闸门放行 / 拒绝、自身操作不经闸门、闸门随卸载移除、用户提示通道注入。
 */
public class CorePermissionTest {

    private static final String TARGET_MECH = "t.target";
    private static final String TARGET_HOOK = TARGET_MECH + ".h";
    private static final String CALLER_MECH = "t.caller";
    private static final String CALLER_PROBE = CALLER_MECH + ".probe";
    private static final String CALLER_OWN = CALLER_MECH + ".own";

    private interface Body {
        void run(HookContext ctx) throws Exception;
    }

    private static Hook hook(String id, Body body) {
        return new Hook() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void invoke(HookContext context) throws Exception {
                body.run(context);
            }
        };
    }

    /** “目标”插件：提供 TARGET_MECH / TARGET_HOOK。 */
    private static Plugin targetPlugin() {
        return new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism(TARGET_MECH, "目标", List.of(
                        hook(TARGET_HOOK, ctx -> {
                        }))));
            }
        };
    }

    /**
     * “调用方”三方插件：probe 依次访问自身机制状态、他方机制状态、调用他方钩子、调用自身钩子；
     * 结果写入原子量（被拒绝记为 false）。
     */
    private static Plugin callerPlugin(AtomicBoolean ownState, AtomicBoolean stateAccess,
                                       AtomicBoolean hookInvoke, AtomicBoolean ownInvoke) {
        return new Plugin() {
            private PluginContext ctx;

            @Override
            public void onLoad(PluginContext context) {
                this.ctx = context;
                context.registerMechanism(new Mechanism(CALLER_MECH, "调用方", List.of(
                        hook(CALLER_PROBE, hc -> {
                            try {
                                ctx.stateFor(CALLER_MECH);
                                ownState.set(true);
                            } catch (LifeloomException e) {
                                ownState.set(false);
                            }
                            try {
                                ctx.stateFor(TARGET_MECH);
                                stateAccess.set(true);
                            } catch (LifeloomException e) {
                                stateAccess.set(false);
                            }
                            try {
                                ctx.invokeHook(TARGET_HOOK);
                                hookInvoke.set(true);
                            } catch (Exception e) {
                                hookInvoke.set(false);
                            }
                            ctx.invokeHook(CALLER_OWN);
                        }),
                        hook(CALLER_OWN, hc -> ownInvoke.set(true)))));
            }
        };
    }

    @Test
    public void crossOpsFailClosedWithoutGatekeeper() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicBoolean ownState = new AtomicBoolean();
        AtomicBoolean stateAccess = new AtomicBoolean();
        AtomicBoolean hookInvoke = new AtomicBoolean();
        AtomicBoolean ownInvoke = new AtomicBoolean();
        loader.register("target.jar",
                path -> TestPlugins.loaded("t.target.plugin", "1.0", targetPlugin(), path));
        loader.register("caller.jar", path -> TestPlugins.loaded("t.caller.plugin", "1.0",
                callerPlugin(ownState, stateAccess, hookInvoke, ownInvoke), path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("target.jar"));
        core.loadPlugin(Paths.get("caller.jar"));
        core.invokeHook(CALLER_PROBE);

        assertTrue("自身机制状态访问不经闸门", ownState.get());
        assertFalse("无闸门时访问他方机制状态应被拒绝", stateAccess.get());
        assertFalse("无闸门时调用他方钩子应被拒绝", hookInvoke.get());
        assertTrue("自身钩子调用不经闸门", ownInvoke.get());
    }

    @Test
    public void gatekeeperAllowsAndDeniesOps() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicBoolean ownState = new AtomicBoolean();
        AtomicBoolean stateAccess = new AtomicBoolean();
        AtomicBoolean hookInvoke = new AtomicBoolean();
        AtomicBoolean ownInvoke = new AtomicBoolean();
        AtomicInteger requests = new AtomicInteger();
        loader.register("target.jar",
                path -> TestPlugins.loaded("t.target.plugin", "1.0", targetPlugin(), path));
        loader.register("caller.jar", path -> TestPlugins.loaded("t.caller.plugin", "1.0",
                callerPlugin(ownState, stateAccess, hookInvoke, ownInvoke), path));
        loader.register("gate.jar", path -> TestPlugins.loaded("t.gate.plugin", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerGatekeeper(request -> {
                    requests.incrementAndGet();
                    return "access-mechanism-state".equals(request.operation());
                });
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("target.jar"));
        core.loadPlugin(Paths.get("caller.jar"));
        core.loadPlugin(Paths.get("gate.jar"));
        core.invokeHook(CALLER_PROBE);

        assertTrue("状态访问应被闸门放行", stateAccess.get());
        assertFalse("调用他方钩子应被闸门拒绝", hookInvoke.get());
        assertTrue("自身钩子调用不经闸门", ownInvoke.get());
        assertEquals("闸门应恰好收到两次跨边界请求", 2, requests.get());
    }

    @Test
    public void unloadGatekeeperPluginRestoresFailClosed() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicBoolean ownState = new AtomicBoolean();
        AtomicBoolean stateAccess = new AtomicBoolean();
        AtomicBoolean hookInvoke = new AtomicBoolean();
        AtomicBoolean ownInvoke = new AtomicBoolean();
        loader.register("target.jar",
                path -> TestPlugins.loaded("t.target.plugin", "1.0", targetPlugin(), path));
        loader.register("caller.jar", path -> TestPlugins.loaded("t.caller.plugin", "1.0",
                callerPlugin(ownState, stateAccess, hookInvoke, ownInvoke), path));
        loader.register("gate.jar", path -> TestPlugins.loaded("t.gate.plugin", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerGatekeeper(request -> true);
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("target.jar"));
        core.loadPlugin(Paths.get("caller.jar"));
        core.loadPlugin(Paths.get("gate.jar"));
        core.invokeHook(CALLER_PROBE);
        assertTrue(stateAccess.get());
        assertTrue(hookInvoke.get());

        assertTrue(core.unloadPlugin("t.gate.plugin"));
        stateAccess.set(false);
        hookInvoke.set(false);
        core.invokeHook(CALLER_PROBE);
        assertFalse("闸门卸载后跨边界操作回到 fail-closed", stateAccess.get());
        assertFalse(hookInvoke.get());
    }

    @Test
    public void askUserUsesInjectedPromptChannel() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<Boolean> answer = new AtomicReference<>();
        loader.register("sys.jar", path -> TestPlugins.loaded("t.sys.plugin", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerMechanism(new Mechanism("t.sys", "系统提示", List.of(
                        hook("t.sys.ask", hc -> answer.set(context.askUser("测试询问"))))));
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("sys.jar"));

        core.invokeHook("t.sys.ask");
        assertFalse("缺省提示通道应拒绝", answer.get());

        core.setUserPrompt(new UserPrompt() {
            @Override
            public void inform(String message) {
            }

            @Override
            public boolean confirm(String message) {
                return true;
            }
        });
        core.invokeHook("t.sys.ask");
        assertTrue("注入的提示通道应被使用", answer.get());
    }

    @Test
    public void thirdPartyAskUserRejected() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> error = new AtomicReference<>();
        loader.register("tp.jar", path -> TestPlugins.loaded("t.tp.plugin", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                try {
                    context.askUser("不应允许");
                } catch (LifeloomException e) {
                    error.set(e.getMessage());
                }
            }
        }, path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("tp.jar"));

        assertTrue("三方插件请求用户提示应被拒绝: " + error.get(),
                error.get() != null && error.get().contains("仅系统插件"));
    }
}
