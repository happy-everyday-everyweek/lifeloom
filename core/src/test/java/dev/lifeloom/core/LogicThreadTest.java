package dev.lifeloom.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * M3 收尾：逻辑线程（接缝 = LogicThread 的公开行为）。
 * 覆盖：专用线程、内联执行、串行化、异常传播、停止后拒绝。
 */
public class LogicThreadTest {

    @Test
    public void runsTasksOnDedicatedThread() throws Exception {
        LogicThread logic = new LogicThread("test-logic");
        try {
            String name = logic.call(() -> Thread.currentThread().getName());
            assertEquals("test-logic", name);
            assertFalse("调用方线程不应是逻辑线程", logic.isOnLogicThread());
            logic.call(() -> {
                assertTrue("任务应运行在逻辑线程上", logic.isOnLogicThread());
                return null;
            });
        } finally {
            logic.shutdown();
        }
    }

    @Test
    public void callOnLogicThreadRunsInline() throws Exception {
        LogicThread logic = new LogicThread("test-logic");
        try {
            String result = logic.call(() -> logic.call(() -> "inline"));
            assertEquals("inline", result);
        } finally {
            logic.shutdown();
        }
    }

    @Test
    public void concurrentSubmissionsExecuteSerially() throws Exception {
        LogicThread logic = new LogicThread("test-logic");
        try {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(6);
            Runnable body = () -> {
                int now = running.incrementAndGet();
                maxRunning.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
            };
            for (int i = 0; i < 2; i++) {
                new Thread(() -> {
                    for (int j = 0; j < 3; j++) {
                        try {
                            logic.call(() -> {
                                body.run();
                                return null;
                            });
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        done.countDown();
                    }
                }).start();
            }
            assertTrue("任务应在时限内完成", done.await(10, TimeUnit.SECONDS));
            assertEquals("逻辑线程上不允许并行执行", 1, maxRunning.get());
        } finally {
            logic.shutdown();
        }
    }

    @Test
    public void exceptionsPropagateToCaller() {
        LogicThread logic = new LogicThread("test-logic");
        try {
            try {
                logic.call(() -> {
                    throw new IllegalStateException("模拟任务故障");
                });
                fail("应抛出任务异常");
            } catch (IllegalStateException expected) {
                assertEquals("模拟任务故障", expected.getMessage());
            } catch (Exception e) {
                fail("异常类型应保持: " + e);
            }
        } finally {
            logic.shutdown();
        }
    }

    @Test
    public void shutdownRejectsNewCalls() {
        LogicThread logic = new LogicThread("test-logic");
        logic.shutdown();
        try {
            logic.call(() -> "x");
            fail("停止后不应接受新任务");
        } catch (LifeloomException expected) {
            // 期望路径
        } catch (Exception e) {
            fail("应抛出 LifeloomException: " + e);
        }
    }
}
