package dev.lifeloom.core;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 核心 M1 测试：装载 / 调用 / 卸载、热替换（drain + 数据不断档）。
 */
public class CoreTest {

    private static final String M = "test.mech";
    private static final String H = "test.mech.h";

    // --- 基础流程 ---

    @Test
    public void loadInvokeUnload() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("p.jar", path -> TestPlugins.loaded("test.p", "1.0",
                TestPlugins.withHook(M, H, ctx -> {
                    int n = parseInt(ctx.state().get("count")) + 1;
                    ctx.state().put("count", String.valueOf(n));
                }), path));
        Core core = new Core(loader);

        core.loadPlugin(Paths.get("p.jar"));
        assertNotNull(core.registry().findHook(H));

        core.invokeHook(H);
        core.invokeHook(H);
        assertEquals("2", core.stateOf(M).get("count"));

        assertTrue(core.unloadPlugin("test.p"));
        assertNull(core.registry().findHook(H));
        assertEquals("卸载后机制状态应保留", "2", core.stateOf(M).get("count"));
    }

    // --- 热替换 ---

    @Test
    public void swapKeepsStateAcrossVersions() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("v1.jar", path -> TestPlugins.loaded("test.p", "1.0",
                TestPlugins.withHook(M, H, ctx -> {
                    int n = parseInt(ctx.state().get("count")) + 1;
                    ctx.state().put("count", String.valueOf(n));
                }), path));
        loader.register("v2.jar", path -> TestPlugins.loaded("test.p", "2.0",
                TestPlugins.withHook(M, H, ctx -> {
                    int n = parseInt(ctx.state().get("count")) + 1;
                    ctx.state().put("count", String.valueOf(n));
                    ctx.state().put("lastVersion", "2.0");
                }), path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("v1.jar"));
        core.invokeHook(H);
        assertEquals("1", core.stateOf(M).get("count"));

        SwapManager.Result result = core.replacePlugin("test.p", Paths.get("v2.jar"));
        assertTrue(result.isUpgrade());
        assertEquals("1.0", result.oldVersion());
        assertEquals("2.0", result.newVersion());
        assertEquals("2.0", core.registry().findPlugin("test.p").descriptor().version());

        core.invokeHook(H);
        assertEquals("替换后计数应延续（1 -> 2）", "2", core.stateOf(M).get("count"));
        assertEquals("2.0", core.stateOf(M).get("lastVersion"));
    }

    @Test
    public void swapRejectsMismatchedId() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("a.jar", path -> TestPlugins.loaded("test.a", "1.0",
                TestPlugins.withHook(M, H, ctx -> {
                }), path));
        loader.register("b.jar", path -> TestPlugins.loaded("test.b", "1.0",
                TestPlugins.withHook(M + "b", H + "b", ctx -> {
                }), path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("a.jar"));

        try {
            core.replacePlugin("test.a", Paths.get("b.jar"));
            fail("包 ID 不符时应抛异常");
        } catch (LifeloomException expected) {
            // 期望路径
        }
        assertNotNull("旧插件应仍在", core.registry().findPlugin("test.a"));
        assertNotNull(core.registry().findHook(H));
        assertNull("不符的包不应注册机制", core.registry().findHook(H + "b"));
    }

    @Test
    public void swapRollsBackOnAttachFailure() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        loader.register("v1.jar", path -> TestPlugins.loaded("test.p", "1.0",
                TestPlugins.withHook(M, H, ctx -> {
                }), path));
        loader.register("broken.jar", path -> TestPlugins.loaded("test.p", "2.0",
                new Plugin() {
                    @Override
                    public void onLoad(PluginContext context) {
                        throw new IllegalStateException("模拟 onLoad 失败");
                    }
                }, path));

        Core core = new Core(loader);
        core.loadPlugin(Paths.get("v1.jar"));

        try {
            core.replacePlugin("test.p", Paths.get("broken.jar"));
            fail("替换应失败");
        } catch (LifeloomException expected) {
            // 期望路径
        }
        LoadedPlugin restored = core.registry().findPlugin("test.p");
        assertNotNull("旧版本应被恢复", restored);
        assertEquals("1.0", restored.descriptor().version());
        core.invokeHook(H);
    }

    // --- drain ---

    @Test
    public void replaceRejectedDuringExecution() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        final Core[] coreRef = new Core[1];
        final AtomicReference<String> outcome = new AtomicReference<>();

        loader.register("v1.jar", path -> TestPlugins.loaded("test.p", "1.0",
                TestPlugins.withHook(M, H, ctx -> {
                    try {
                        coreRef[0].replacePlugin("test.p", Paths.get("v2.jar"));
                        outcome.set("替换竟然成功了");
                    } catch (LifeloomException expected) {
                        outcome.set("rejected");
                    } catch (Exception e) {
                        outcome.set("其他异常: " + e);
                    }
                }), path));
        loader.register("v2.jar", path -> TestPlugins.loaded("test.p", "2.0",
                TestPlugins.withHook(M, H, ctx -> {
                }), path));

        Core core = new Core(loader);
        coreRef[0] = core;
        core.loadPlugin(Paths.get("v1.jar"));
        core.invokeHook(H);

        assertEquals("钩子执行中发起替换应被拒绝", "rejected", outcome.get());
    }

    @Test
    public void drainWaitsForExecutionAndBlocksNewInvocations() throws Exception {
        FakePluginLoader loader = new FakePluginLoader();
        CountDownLatch hookStarted = new CountDownLatch(1);

        loader.register("v1.jar", path -> TestPlugins.loaded("test.p", "1.0",
                TestPlugins.withHook(M, H, ctx -> {
                    hookStarted.countDown();
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }), path));
        loader.register("v2.jar", path -> TestPlugins.loaded("test.p", "2.0",
                TestPlugins.withHook(M, H, ctx -> {
                }), path));

        final Core core = new Core(loader);
        core.loadPlugin(Paths.get("v1.jar"));

        // 线程 T1：执行慢钩子。
        final AtomicReference<Throwable> executorError = new AtomicReference<>();
        Thread executor = new Thread(() -> {
            try {
                core.invokeHook(H);
            } catch (Throwable t) {
                executorError.set(t);
            }
        }, "executor");
        executor.start();
        assertTrue(hookStarted.await(5, TimeUnit.SECONDS));

        // 线程 T2：发起替换（应进入 drain，等待 T1 完成）。
        final AtomicReference<SwapManager.Result> swapResult = new AtomicReference<>();
        final AtomicReference<Throwable> replacerError = new AtomicReference<>();
        Thread replacer = new Thread(() -> {
            try {
                swapResult.set(core.replacePlugin("test.p", Paths.get("v2.jar")));
            } catch (Throwable t) {
                replacerError.set(t);
            }
        }, "replacer");
        replacer.start();

        // 等待 replacer 进入 drain（替换进行中）。
        long waitStart = System.currentTimeMillis();
        while (!core.isReplacing() && System.currentTimeMillis() - waitStart < 5000) {
            Thread.sleep(10);
        }
        assertTrue("替换应进入 drain 状态", core.isReplacing());

        // drain 期间新调用应被拒绝。
        boolean rejected = false;
        try {
            core.invokeHook(H);
        } catch (LifeloomException expected) {
            rejected = true;
        }
        assertTrue("drain 期间新调用应被拒绝", rejected);

        executor.join(5000);
        replacer.join(5000);
        assertNull("执行线程不应报错: " + executorError.get(), executorError.get());
        assertNull("替换线程不应报错: " + replacerError.get(), replacerError.get());
        assertNotNull(swapResult.get());
        assertEquals("2.0", swapResult.get().newVersion());

        // 替换完成后，钩子应可用（新版本）。
        core.invokeHook(H);
        assertEquals("2.0", core.registry().findPlugin("test.p").descriptor().version());
    }

    // --- 辅助 ---

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