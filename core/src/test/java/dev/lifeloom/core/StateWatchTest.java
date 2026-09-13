package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M3 参数存储原语测试：机制状态变更通知（watchState）与监听生命周期。
 */
public class StateWatchTest {

    @Test
    public void watchOwnMechanismReceivesChanges() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        List<String> events = new ArrayList<>();
        loader.register("p.jar", path -> TestPlugins.loaded("w.p", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.watchState("w.m", (mechanismId, key, oldValue, newValue) ->
                        events.add(mechanismId + ":" + key + ":" + oldValue + "->" + newValue));
                context.registerMechanism(new Mechanism("w.m", "监听演示", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "w.m.h";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                    }
                })));
            }
        }, path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("p.jar"));

        core.stateOf("w.m").put("k", "v1");
        core.stateOf("w.m").put("k", "v1");
        core.stateOf("w.m").put("k", "v2");
        core.stateOf("w.m").remove("k");

        assertEquals(List.of("w.m:k:null->v1", "w.m:k:v1->v2", "w.m:k:v2->null"), events);
    }

    @Test
    public void watchOtherPluginGatedByPermission() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> outcome = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        loader.register("target.jar", path -> TestPlugins.loaded("w.target", "1.0",
                TestPlugins.withHook("w.target.m", "w.target.m.h", ctx -> {
                }), path));
        loader.register("watcher.jar", path -> TestPlugins.loaded("w.watcher", "1.0", new Plugin() {
            private PluginContext ctx;

            @Override
            public void onLoad(PluginContext context) {
                this.ctx = context;
                context.registerMechanism(new Mechanism("w.watcher.m", "监听者", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "w.watcher.m.try";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                        try {
                            ctx.watchState("w.target.m", (m, k, o, n) -> hits.incrementAndGet());
                            outcome.set("allowed");
                        } catch (LifeloomException e) {
                            outcome.set("denied: " + e.getMessage());
                        }
                    }
                })));
            }
        }, path));
        loader.register("gate.jar", path -> TestPlugins.loaded("w.gate", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.registerGatekeeper(request -> true);
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("target.jar"));
        core.loadPlugin(Paths.get("watcher.jar"));

        core.invokeHook("w.watcher.m.try");
        assertTrue("无闸门时应拒绝监听他方机制: " + outcome.get(),
                outcome.get() != null && outcome.get().startsWith("denied"));

        core.loadPlugin(Paths.get("gate.jar"));
        core.invokeHook("w.watcher.m.try");
        assertEquals("allowed", outcome.get());

        core.stateOf("w.target.m").put("x", "1");
        assertEquals("放行后监听应生效", 1, hits.get());
    }

    @Test
    public void watchersRemovedOnUnload() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicInteger hits = new AtomicInteger();
        loader.register("p.jar", path -> TestPlugins.loaded("w.p", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.watchState("w.m", (m, k, o, n) -> hits.incrementAndGet());
                context.registerMechanism(new Mechanism("w.m", "监听演示", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "w.m.h";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                    }
                })));
            }
        }, path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("p.jar"));
        core.stateOf("w.m").put("k", "1");
        assertEquals(1, hits.get());

        assertTrue(core.unloadPlugin("w.p"));
        core.stateOf("w.m").put("k", "2");
        assertEquals("卸载后不应再收到通知", 1, hits.get());
    }
}
