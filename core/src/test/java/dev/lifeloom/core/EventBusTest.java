package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M3 调度原语测试：事件总线（订阅、发射、卸载清理、异常隔离）。
 */
public class EventBusTest {

    @Test
    public void subscribersReceiveEventsWithEmitterAndPayload() {
        EventBus bus = new EventBus();
        LoadedPlugin subscriber = plugin("e.sub", PluginOrigin.SYSTEM);
        List<String> seen = new ArrayList<>();
        bus.subscribe(subscriber, "e.topic", event -> seen.add(event.emitter() + "|" + event.payload()));

        bus.emit(null, "e.topic", "hi");

        assertEquals(List.of("(core)|hi"), seen);
    }

    @Test
    public void multipleSubscribersAllReceiveInOrder() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<>();
        bus.subscribe(plugin("e.a", PluginOrigin.SYSTEM), "e.t", event -> order.add("a"));
        bus.subscribe(plugin("e.b", PluginOrigin.THIRD_PARTY), "e.t", event -> order.add("b"));

        bus.emit(plugin("e.emitter", PluginOrigin.SYSTEM), "e.t", null);

        assertEquals(List.of("a", "b"), order);
    }

    @Test
    public void listenerErrorDoesNotBreakOtherListeners() {
        EventBus bus = new EventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(plugin("e.a", PluginOrigin.SYSTEM), "e.t", event -> {
            throw new IllegalStateException("模拟订阅者故障");
        });
        bus.subscribe(plugin("e.b", PluginOrigin.SYSTEM), "e.t", event -> seen.add("b"));

        bus.emit(null, "e.t", null);

        assertEquals(List.of("b"), seen);
    }

    @Test
    public void unloadRemovesSubscriptions() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        List<String> seen = new ArrayList<>();
        loader.register("sub.jar", path -> TestPlugins.loaded("e.sub", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.subscribe("e.t", event -> seen.add(event.payload()));
            }
        }, path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("sub.jar"));

        core.emitEvent(null, "e.t", "1");
        assertEquals(List.of("1"), seen);

        assertTrue(core.unloadPlugin("e.sub"));
        core.emitEvent(null, "e.t", "2");
        assertEquals("卸载后不应再收到事件", List.of("1"), seen);
    }

    @Test
    public void emitFromHookReachesSubscriber() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        List<String> seen = new ArrayList<>();
        loader.register("sub.jar", path -> TestPlugins.loaded("e.sub", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.subscribe("e.t", event -> seen.add(event.payload()));
            }
        }, path));
        loader.register("emitter.jar", path -> TestPlugins.loaded("e.emitter", "1.0", new Plugin() {
            private PluginContext ctx;

            @Override
            public void onLoad(PluginContext context) {
                this.ctx = context;
                context.registerMechanism(new Mechanism("e.emitter.m", "发射者", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "e.emitter.m.fire";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                        ctx.emit("e.t", "from-hook");
                    }
                })));
            }
        }, path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("sub.jar"));
        core.loadPlugin(Paths.get("emitter.jar"));

        core.invokeHook("e.emitter.m.fire");

        assertEquals(List.of("from-hook"), seen);
    }

    private static LoadedPlugin plugin(String id, PluginOrigin origin) {
        Plugin instance = new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
            }
        };
        return TestPlugins.loaded(id, "1.0", instance, id + ".jar", origin);
    }
}
