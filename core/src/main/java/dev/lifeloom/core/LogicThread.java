package dev.lifeloom.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 逻辑线程（核心原语，M3 收尾）：模拟与调度相关代码在单一线程上串行执行。
 *
 * <p>目的：简化并发模型与热替换——钩子调用、事件分发与插件生命周期操作
 * 统一提交到本线程；调用方同步等待结果（若调用方已在本线程上，则直接内联执行，
 * 避免自等待）。线程为守护线程；{@link #shutdown()} 后不再接受新的提交，
 * 已在队列中的任务会执行完（避免已提交的调用方挂起）。
 */
public final class LogicThread {

    private final Object lifecycle = new Object();
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    LogicThread(String name) {
        this.thread = new Thread(this::loop, name);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        while (running) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                continue; // 重新检查 running
            }
            runQuietly(task);
        }
        // 停止后把剩余任务跑完：已提交的调用方不应挂起。
        Runnable rest;
        while ((rest = queue.poll()) != null) {
            runQuietly(rest);
        }
    }

    private static void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            System.err.println("[core] 逻辑线程任务异常: " + t);
        }
    }

    /** 当前线程是否就是逻辑线程。 */
    public boolean isOnLogicThread() {
        return Thread.currentThread() == thread;
    }

    /** 逻辑线程名（诊断用）。 */
    public String name() {
        return thread.getName();
    }

    /** 等待队列长度（诊断用；不含正在执行的任务）。 */
    public int queueSize() {
        return queue.size();
    }

    /**
     * 在逻辑线程上执行任务并同步返回结果；调用方已是逻辑线程时直接内联执行。
     */
    <T> T call(Callable<T> task) throws Exception {
        if (isOnLogicThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<>(task);
        synchronized (lifecycle) {
            if (!running) {
                throw new LifeloomException("逻辑线程已停止，不再接受新的派发");
            }
            queue.add(future);
        }
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new LifeloomException("逻辑线程任务失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LifeloomException("等待逻辑线程时被中断", e);
        }
    }

    /** 停止逻辑线程；随后新的提交会被拒绝。 */
    public void shutdown() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            running = false;
            thread.interrupt();
        }
    }
}
