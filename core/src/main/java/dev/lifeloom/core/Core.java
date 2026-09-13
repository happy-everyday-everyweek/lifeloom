package dev.lifeloom.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lifeloom 核心（极薄宿主）。
 *
 * <p>当前职责：装载 / 卸载 / 热替换插件、维护注册表与机制状态、按钩子 ID 调用钩子；
 * 以及 M2 的极薄权限原语：非系统插件的跨边界操作请求经 {@link PermissionBroker}
 * 转发给已注册闸门（放行决策与用户提示由闸门实现承担，如权限插件），
 * 并提供由外壳注入的用户提示通道（{@link UserPrompt}）。调度与参数存储（M3）随里程碑加入。
 *
 * <p>执行闸门：所有钩子调用经 {@code enterExecution / exitExecution} 计数；
 * 替换与卸载通过 {@code withDrain} 执行：停止接受新的执行请求，等待执行中的调用结束，
 * 完成任务后恢复（见 ADR-0002）。
 */
public final class Core {

    private final PluginLoader loader;
    private final Registry registry = new Registry();
    private final MechanismStateStore states = new MechanismStateStore();
    private final SwapManager swapManager = new SwapManager(this);
    private final PermissionBroker permissions = new PermissionBroker();
    private volatile UserPrompt userPrompt = UserPrompt.DENY_ALL;

    // --- 执行闸门（drain 支持） ---
    private final Object gate = new Object();
    private int activeExecutions = 0;
    private boolean swapping = false;
    private final ThreadLocal<Integer> executionDepth = ThreadLocal.withInitial(() -> 0);

    public Core(PluginLoader loader) {
        this.loader = loader;
    }

    public Registry registry() {
        return registry;
    }

    /** 权限闸门转发器（极薄原语；放行决策由已注册闸门承担）。 */
    public PermissionBroker permissions() {
        return permissions;
    }

    /** 用户提示通道（由外壳注入；缺省为全部拒绝）。 */
    public UserPrompt userPrompt() {
        return userPrompt;
    }

    /** 注入用户提示通道（外壳启动时调用）。 */
    public void setUserPrompt(UserPrompt userPrompt) {
        this.userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
    }

    /** 机制状态存储（包内协作；插件侧请走 PluginContext 或 HookContext）。 */
    MechanismStateStore states() {
        return states;
    }

    /** 某机制的状态视图（诊断 / 工具用；插件侧请走 PluginContext 或 HookContext）。 */
    public MechanismState stateOf(String mechanismId) {
        return states.stateFor(mechanismId);
    }

    // --- 装载 / 卸载 ---

    /**
     * 扫描目录装载全部插件包（{@code *.jar}）。
     * 单个插件装载失败不中断整体扫描：失败项记录到标准错误后跳过。
     *
     * @return 成功装载的插件数
     */
    public int loadPluginsFrom(Path pluginsDir) throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        int loaded = 0;
        for (Path file : files) {
            try {
                loadPlugin(file);
                loaded++;
            } catch (Exception e) {
                System.err.println("[core] 插件装载失败: " + file + " -> " + e.getMessage());
            }
        }
        return loaded;
    }

    /** 装载单个插件（不做 drain；调用方需保证不在执行中进行）。 */
    public LoadedPlugin loadPlugin(Path pluginFile) throws Exception {
        LoadedPlugin plugin = loader.load(pluginFile);
        attachPlugin(plugin);
        return plugin;
    }

    /**
     * 卸载插件（走 drain 闸门）：onUnload → 清理注册内容 → 移除其闸门 → 释放类加载器。
     * 机制状态保留（数据不断档）。
     *
     * @return 是否找到并卸载了该插件
     */
    public boolean unloadPlugin(String pluginId) {
        try {
            return withDrain(() -> detachPlugin(pluginId));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new LifeloomException("卸载失败: " + pluginId, e);
        }
    }

    /**
     * 整体替换（热替换）：drain → 卸载旧版本 → 装载新版本 → 恢复（见 ADR-0002）。
     *
     * @param pluginId   目标插件 ID（须与新包清单一致）
     * @param newPackage 新版本插件包路径
     * @return 替换结果摘要
     */
    public SwapManager.Result replacePlugin(String pluginId, Path newPackage) throws Exception {
        return withDrain(() -> swapManager.swap(pluginId, newPackage));
    }

    // --- 调用 ---

    /** 按钩子 ID 调用钩子（核心 / 外壳侧发起；经执行闸门；替换进行中会拒绝）。 */
    public void invokeHook(String hookId) throws Exception {
        Registry.HookRef ref = registry.findHook(hookId);
        if (ref == null) {
            throw new LifeloomException("钩子未注册: " + hookId);
        }
        enterExecution(hookId);
        try {
            invokeResolved(ref);
        } finally {
            exitExecution();
        }
    }

    /**
     * 插件经 {@link PluginContext} 调用钩子（包内协作）。
     *
     * <p>调用他方钩子时，非系统插件需经权限闸门放行；调用自身钩子直接执行。
     */
    void invokeHookFrom(LoadedPlugin caller, String hookId) throws Exception {
        Registry.HookRef ref = registry.findHook(hookId);
        if (ref == null) {
            throw new LifeloomException("钩子未注册: " + hookId);
        }
        enterExecution(hookId);
        try {
            if (ref.plugin() != caller) {
                boolean allowed = permissions.request(caller, "invoke-hook", hookId,
                        "调用插件“" + ref.plugin().descriptor().id() + "”的钩子 " + hookId);
                if (!allowed) {
                    throw new LifeloomException("权限未放行：调用钩子 " + hookId);
                }
            }
            invokeResolved(ref);
        } finally {
            exitExecution();
        }
    }

    private void invokeResolved(Registry.HookRef ref) throws Exception {
        MechanismState state = states.stateFor(ref.mechanism().id());
        ref.hook().invoke(new HookContext(ref.hook().id(), ref.mechanism().id(), state));
    }

    /** 返回当前全部已装载插件的 ID 列表（调试用）。 */
    public List<String> pluginIds() {
        List<String> ids = new ArrayList<>();
        for (LoadedPlugin plugin : registry.plugins()) {
            ids.add(plugin.descriptor().id());
        }
        return ids;
    }

    // --- 包内协作（SwapManager 使用） ---

    PluginLoader loader() {
        return loader;
    }

    /** 注册 + onLoad + 置 ACTIVE；失败时回滚清理。 */
    void attachPlugin(LoadedPlugin plugin) throws Exception {
        try {
            registry.addPlugin(plugin);
        } catch (RuntimeException e) {
            safeUnload(plugin);
            throw e;
        }
        try {
            plugin.instance().onLoad(new DefaultPluginContext(plugin, this));
        } catch (Exception e) {
            registry.removePlugin(plugin.descriptor().id());
            permissions.removeGatekeepersOf(plugin);
            safeUnload(plugin);
            plugin.setState(LoadedPlugin.State.FAILED);
            throw new LifeloomException("插件 onLoad 失败: " + plugin.descriptor().id(), e);
        }
        plugin.setState(LoadedPlugin.State.ACTIVE);
    }

    /** onUnload + 清理注册 + 移除闸门 + 释放类加载器；机制状态保留。 */
    boolean detachPlugin(String pluginId) {
        LoadedPlugin plugin = registry.findPlugin(pluginId);
        if (plugin == null) {
            return false;
        }
        try {
            plugin.instance().onUnload();
        } catch (Exception e) {
            System.err.println("[core] 插件 onUnload 异常: " + pluginId + " -> " + e.getMessage());
        }
        registry.removePlugin(pluginId);
        permissions.removeGatekeepersOf(plugin);
        safeUnload(plugin);
        plugin.setState(LoadedPlugin.State.UNLOADED);
        return true;
    }

    /** 释放插件资源（装载失败 / 卸载 / 替换流程）。 */
    void safeUnload(LoadedPlugin plugin) {
        try {
            loader.unload(plugin);
        } catch (Exception e) {
            System.err.println("[core] 释放插件资源失败: "
                    + plugin.descriptor().id() + " -> " + e.getMessage());
        }
    }

    // --- 执行闸门 ---

    /** 是否有替换流程正在进行（drain 中；诊断与外壳 UI 用）。 */
    public boolean isReplacing() {
        synchronized (gate) {
            return swapping;
        }
    }

    private void enterExecution(String hookId) {
        synchronized (gate) {
            if (swapping) {
                throw new LifeloomException("插件替换进行中，暂不接受新的执行请求: " + hookId);
            }
            activeExecutions++;
            executionDepth.set(executionDepth.get() + 1);
        }
    }

    private void exitExecution() {
        synchronized (gate) {
            activeExecutions--;
            executionDepth.set(executionDepth.get() - 1);
            gate.notifyAll();
        }
    }

    /**
     * drain 语义：停止接受新的执行请求；等待执行中的调用完成后执行任务，然后恢复。
     * 不允许在钩子执行中（当前线程）发起——避免自等待。
     */
    private <T> T withDrain(Callable<T> task) throws Exception {
        if (executionDepth.get() > 0) {
            throw new LifeloomException("不能在执行钩子期间执行该操作（当前线程正在执行中）");
        }
        synchronized (gate) {
            swapping = true;
            try {
                while (activeExecutions > 0) {
                    gate.wait(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                swapping = false;
                gate.notifyAll();
                throw new LifeloomException("操作被中断（等待执行完成时）", e);
            }
        }
        try {
            return task.call();
        } finally {
            synchronized (gate) {
                swapping = false;
                gate.notifyAll();
            }
        }
    }
}
