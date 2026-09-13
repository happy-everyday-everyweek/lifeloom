package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M3 收尾：核心派发路由（接缝 = Core 的调用接口）。
 * 覆盖：钩子与事件在逻辑线程上执行、并发调用串行化、嵌套调用内联。
 */
public class LogicDispatchTest {

    @Test
    public void hookAndEventsRunOnLogicThread() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> hookThread = new AtomicReference<>();
        AtomicReference<String> eventThread = new AtomicReference<>();
        AtomicReference<Boolean> onLogic = new AtomicReference<>(false);

        final Core[] coreRef = new Core[1];
        loader.register("p.jar", path -> TestPlugins.loaded("l.p", "1.0", new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
                context.subscribe("l.t", event -> eventThread.set(Thread.currentThread().getName()));
                context.registerMechanism(new Mechanism("l.m", "探针", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "l.m.h";
                    }

                    @Override
                    public void invoke(HookContext hookContext) {
                        hookThread.set(Thread.currentThread().getName());
                        onLogic.set(coreRef[0].isOnLogicThread());
                    }
                })));
            }
        }, path));

        Core core = new Core(loader);
        coreRef[0] = core;
        try {
            core.loadPlugin(Paths.get("p.jar"));
            core.invokeHook("l.m.h");
            assertEquals(core.logicThreadName(), hookThread.get());
            assertTrue("钩子执行时应在逻辑线程上", onLogic.get());

            core.emitEvent(null, "l.t", "x");
            assertEquals(core.logicThreadName(), eventThread.get());
            assertFalse("外壳线程不应是逻辑线程", core.isOnLogicThread());
        } finally {
            core.close();
        }
    }

    @Test
    public void concurrentInvocationsExecuteSerially() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        loader.register("p.jar", path -> TestPlugins.loaded("l.p", "1.0",
                TestPlugins.withHook("l.m", "l.m.h", ctx -> {
                    int now = running.incrementAndGet();
                    maxRunning.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                    total.incrementAndGet();
                }), path));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("p.jar"));
            CountDownLatch done = new CountDownLatch(6);
            for (int i = 0; i < 2; i++) {
                new Thread(() -> {
                    for (int j = 0; j < 3; j++) {
                        try {
                            core.invokeHook("l.m.h");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            done.countDown();
                        }
                    }
                }).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals("逻辑线程上不允许并行执行", 1, maxRunning.get());
            assertEquals(6, total.get());
        } finally {
            core.close();
        }
    }

    @Test
    public void nestedInvocationFromHookStaysInline() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        AtomicReference<String> innerThread = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        loader.register("b.jar", path -> TestPlugins.loaded("l.b", "1.0",
                TestPlugins.withHook("l.b.m", "l.b.m.h", ctx ->
                        innerThread.set(Thread.currentThread().getName())), path));
        loader.register("a.jar", path -> TestPlugins.loaded("l.a", "1.0", new Plugin() {
            private PluginContext ctx;

            @Override
            public void onLoad(PluginContext context) {
                this.ctx = context;
                context.registerMechanism(new Mechanism("l.a.m", "外层", List.of(new Hook() {
                    @Override
                    public String id() {
                        return "l.a.m.h";
                    }

                    @Override
                    public void invoke(HookContext hookContext) throws Exception {
                        ctx.invokeHook("l.b.m.h");
                        completed.set(true);
                    }
                })));
            }
        }, path, PluginOrigin.SYSTEM));

        Core core = new Core(loader);
        try {
            core.loadPlugin(Paths.get("b.jar"));
            core.loadPlugin(Paths.get("a.jar"));
            core.invokeHook("l.a.m.h");
            assertTrue("嵌套调用应完成（无死锁）", completed.get());
            assertEquals(core.logicThreadName(), innerThread.get());
        } finally {
            core.close();
        }
    }
}
