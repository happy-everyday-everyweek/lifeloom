package dev.lifeloom.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lifeloom 核心（极薄宿主）。
 *
 * <p>当前职责：装载 / 卸载 / 热替换插件、维护注册表与参数存储、按钩子 ID 调用钩子；
 * M2 权限原语（闸门转发器与用户提示通道）；M3 调度与存储原语：单逻辑线程模型、
 * 事件总线（发射 / 订阅）与参数存储（按命名空间存取、变更通知）；插件间调用协议：
 * 钩子调用带输入输出、钩子解析器（禁用 / 替代登记的衔接点，见 ADR-0006）；
 * 系统插件协作原语：机制 / 插件清单查看与装载控制（仅系统插件可用；钩子执行中
 * 请求时延迟到本次调用结束后执行）。
 *
 * <p>单逻辑线程：钩子调用、事件分发与插件生命周期操作都由 {@link LogicThread} 串行执行。
 * 执行闸门：派发先经 {@code enterGate} 计数；替换与卸载通过 {@code withDrain} 执行
 * （停止接受新的执行请求、等待在途派发结束、执行任务后恢复，见 ADR-0002）；
 * 派发在逻辑线程上执行期间记录深度，执行中禁止发起替换 / 卸载。
 */
public final class Core {

    private final PluginLoader loader;
    private final Registry registry = new Registry();
    private final ParamStore params = new ParamStore();
    private final SwapManager swapManager = new SwapManager(this);
    private final PermissionBroker permissions = new PermissionBroker();
    private final HookResolvers hookResolvers = new HookResolvers();
    private final EventBus events = new EventBus();
    private final LogicThread logic = new LogicThread("lifeloom-logic");
    private volatile UserPrompt userPrompt = UserPrompt.DENY_ALL;
    private final Deque<Runnable> deferredOps = new ArrayDeque<>();

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

    /** 钩子解析器登记簿（极薄原语；解析由已注册解析器承担，如钩子管理插件）。 */
    public HookResolvers hookResolvers() {
        return hookResolvers;
    }

    /** 事件总线（诊断 / 外壳用；插件侧请走 PluginContext）。 */
    public EventBus events() {
        return events;
    }

    /** 用户提示通道（由外壳注入；缺省为全部拒绝）。 */
    public UserPrompt userPrompt() {
        return userPrompt;
    }

    /** 注入用户提示通道（外壳启动时调用）。 */
    public void setUserPrompt(UserPrompt userPrompt) {
        this.userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
    }

    /** 参数存储（包内协作；插件侧请走 PluginContext 或 HookContext）。 */
    ParamStore params() {
        return params;
    }

    /** 某机制的状态视图（诊断 / 工具用；插件侧请走 PluginContext 或 HookContext）。 */
    public MechanismState stateOf(String mechanismId) {
        return params.mechanismState(mechanismId);
    }

    /**
     * 监听某机制状态变更（诊断 / 外壳用；不绑定插件，需自行关闭）。
     *
     * @return 取消句柄；close 后不再收到通知
     */
    public AutoCloseable watchState(String mechanismId, StateChangeListener listener) {
        Objects.requireNonNull(mechanismId, "mechanismId");
        Objects.requireNonNull(listener, "listener");
        return params.watchRaw(ParamStore.mechanismNamespace(mechanismId),
                (namespaceId, key, oldValue, newValue) ->
                        listener.onChange(mechanismId, key, oldValue, newValue));
    }

    // --- 逻辑线程（诊断 / 外壳用） ---

    /** 当前线程是否就是逻辑线程。 */
    public boolean isOnLogicThread() {
        return logic.isOnLogicThread();
    }

    /** 逻辑线程名（诊断用）。 */
    public String logicThreadName() {
        return logic.name();
    }

    /** 逻辑线程等待队列长度（诊断用）。 */
    public int logicQueueSize() {
        return logic.queueSize();
    }

    /** 停止逻辑线程（外壳收尾时调用；此后不再接受新的派发）。 */
    public void close() {
        logic.shutdown();
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

    /** 装载单个插件（在逻辑线程上执行；不做 drain；调用方需保证不在执行中进行）。 */
    public LoadedPlugin loadPlugin(Path pluginFile) throws Exception {
        return logic.call(() -> {
            LoadedPlugin plugin = loader.load(pluginFile);
            attachPlugin(plugin);
            return plugin;
        });
    }

    /**
     * 卸载插件（走 drain 闸门）：onUnload → 清理注册内容、闸门、解析器、事件订阅与状态监听 → 释放类加载器。
     * 机制状态保留（数据不断档）。
     *
     * @return 是否找到并卸载了该插件
     */
    public boolean unloadPlugin(String pluginId) {
        try {
            return withDrain(() -> logic.call(() -> detachPlugin(pluginId)));
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
        return withDrain(() -> logic.call(() -> swapManager.swap(pluginId, newPackage)));
    }

    // --- 调用 ---

    /** 按钩子 ID 调用钩子（核心 / 外壳侧发起）。经执行闸门与钩子解析器；替换进行中会拒绝。 */
    public void invokeHook(String hookId) throws Exception {
        invokeHook(hookId, null);
    }

    /**
     * 按钩子 ID 调用钩子（带输入；返回钩子输出，未 reply 时为 null）。
     *
     * <p>经执行闸门与钩子解析器（禁用 / 替代登记）；被解析为跳过时直接返回 null。
     */
    public String invokeHook(String hookId, String input) throws Exception {
        return dispatch(() -> {
            Registry.HookRef ref = resolveInvocation(hookId);
            if (ref == null) {
                return null; // 被解析器判定为禁用（跳过执行）
            }
            return invokeResolved(ref, input);
        }, hookId);
    }

    /** 发射事件（核心 / 外壳侧发起；经执行闸门；替换进行中会拒绝）。 */
    public void emitEvent(LoadedPlugin emitter, String eventId, String payload) {
        try {
            dispatch(() -> {
                events.emit(emitter, eventId, payload);
                return null;
            }, "event:" + eventId);
        } catch (LifeloomException e) {
            throw e;
        } catch (Exception e) {
            throw new LifeloomException("事件发射失败: " + eventId, e);
        }
    }

    /**
     * 插件经 {@link PluginContext} 调用钩子（包内协作；在逻辑线程上执行）。
     *
     * <p>调用他方钩子时，非系统插件需经权限闸门放行（对解析后的最终目标）；
     * 调用自身钩子直接执行。被解析为跳过时直接返回 null。
     */
    void invokeHookFrom(LoadedPlugin caller, String hookId) throws Exception {
        invokeHookFrom(caller, hookId, null);
    }

    String invokeHookFrom(LoadedPlugin caller, String hookId, String input) throws Exception {
        return dispatch(() -> {
            Registry.HookRef ref = resolveInvocation(hookId);
            if (ref == null) {
                return null; // 被解析器判定为禁用（跳过执行）
            }
            if (ref.plugin() != caller) {
                boolean allowed = permissions.request(caller, "invoke-hook", ref.hook().id(),
                        "调用插件“" + ref.plugin().descriptor().id() + "”的钩子 " + ref.hook().id());
                if (!allowed) {
                    throw new LifeloomException("权限未放行：调用钩子 " + ref.hook().id());
                }
            }
            return invokeResolved(ref, input);
        }, hookId);
    }

    /** 解析钩子调用：经钩子解析器走替代链；被禁用时返回 null。由逻辑线程执行。 */
    private Registry.HookRef resolveInvocation(String hookId) {
        String resolved = hookResolvers.resolveChain(hookId);
        if (resolved == null) {
            return null;
        }
        Registry.HookRef ref = registry.findHook(resolved);
        if (ref == null) {
            throw new LifeloomException("钩子未注册: " + resolved
                    + (resolved.equals(hookId) ? "" : "（由替代链解析而来）"));
        }
        return ref;
    }

    private String invokeResolved(Registry.HookRef ref, String input) throws Exception {
        MechanismState state = params.mechanismState(ref.mechanism().id());
        HookContext context = new HookContext(ref.hook().id(), ref.mechanism().id(), state, input);
        ref.hook().invoke(context);
        return context.output();
    }

    /** 返回当前全部已装载插件的 ID 列表（调试用）。 */
    public List<String> pluginIds() {
        List<String> ids = new ArrayList<>();
        for (LoadedPlugin plugin : registry.plugins()) {
            ids.add(plugin.descriptor().id());
        }
        return ids;
    }

    // --- 系统插件协作原语（清单查看与装载控制；仅系统插件可用） ---

    /** 全部已注册机制快照（注册顺序；仅系统插件可用）。 */
    List<Mechanism> mechanismsFor(LoadedPlugin requester) {
        requireSystem(requester, "查看机制清单");
        return new ArrayList<>(registry.mechanisms());
    }

    /** 全部已装载插件清单快照（装载顺序；仅系统插件可用）。 */
    List<PluginDescriptor> pluginsFor(LoadedPlugin requester) {
        requireSystem(requester, "查看插件清单");
        List<PluginDescriptor> descriptors = new ArrayList<>();
        for (LoadedPlugin plugin : registry.plugins()) {
            descriptors.add(plugin.descriptor());
        }
        return descriptors;
    }

    /**
     * 装载插件（仅系统插件可用；在钩子执行中调用时，延迟到本次调用结束后执行）。
     * 延迟执行失败只记录到标准错误。
     */
    void loadPluginFrom(LoadedPlugin requester, Path pluginFile) throws Exception {
        requireSystem(requester, "装载插件");
        if (logic.isOnLogicThread()) {
            deferControl(() -> {
                try {
                    loadPlugin(pluginFile);
                } catch (Exception e) {
                    throw new LifeloomException("延迟装载失败: " + pluginFile, e);
                }
            });
            return;
        }
        loadPlugin(pluginFile);
    }

    /** 卸载插件（仅系统插件可用；延迟语义同 {@link #loadPluginFrom}）。 */
    void unloadPluginFrom(LoadedPlugin requester, String pluginId) {
        requireSystem(requester, "卸载插件");
        if (logic.isOnLogicThread()) {
            deferControl(() -> {
                if (!unloadPlugin(pluginId)) {
                    throw new LifeloomException("延迟卸载未找到插件: " + pluginId);
                }
            });
            return;
        }
        unloadPlugin(pluginId);
    }

    /** 整体替换插件（仅系统插件可用；延迟语义同 {@link #loadPluginFrom}）。 */
    void replacePluginFrom(LoadedPlugin requester, String pluginId, Path newPluginFile) throws Exception {
        requireSystem(requester, "替换插件");
        if (logic.isOnLogicThread()) {
            deferControl(() -> {
                try {
                    replacePlugin(pluginId, newPluginFile);
                } catch (Exception e) {
                    throw new LifeloomException("延迟替换失败: " + pluginId, e);
                }
            });
            return;
        }
        replacePlugin(pluginId, newPluginFile);
    }

    private static void requireSystem(LoadedPlugin requester, String operation) {
        if (requester.origin() != PluginOrigin.SYSTEM) {
            throw new LifeloomException("仅系统插件可用（" + operation + "）: "
                    + requester.descriptor().id());
        }
    }

    // --- 延迟控制操作（钩子执行中请求的装载控制；于非逻辑线程的派发收尾处执行） ---

    private void deferControl(Runnable op) {
        synchronized (deferredOps) {
            deferredOps.addLast(op);
        }
    }

    /** 执行排队的延迟控制操作（FIFO）。失败只记录，不影响调用方（调用方已收到“已受理”）。 */
    private void runDeferredOps() {
        while (true) {
            Runnable op;
            synchronized (deferredOps) {
                op = deferredOps.pollFirst();
            }
            if (op == null) {
                return;
            }
            try {
                op.run();
            } catch (LifeloomException e) {
                System.err.println("[core] 延迟控制操作失败: " + e.getMessage());
            }
        }
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
            hookResolvers.removeResolversOf(plugin);
            events.removeSubscriptionsOf(plugin);
            params.removeWatchersOf(plugin);
            safeUnload(plugin);
            plugin.setState(LoadedPlugin.State.FAILED);
            throw new LifeloomException("插件 onLoad 失败: " + plugin.descriptor().id(), e);
        }
        plugin.setState(LoadedPlugin.State.ACTIVE);
    }

    /** onUnload + 清理注册内容、闸门、解析器、订阅与监听 + 释放类加载器；机制状态保留。 */
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
        hookResolvers.removeResolversOf(plugin);
        events.removeSubscriptionsOf(plugin);
        params.removeWatchersOf(plugin);
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

    // --- 逻辑线程派发 ---

    /**
     * 在逻辑线程上执行一次派发：先在调用线程进入执行闸门（drain 计数含排队中的派发），
     * 再提交逻辑线程；执行期间在逻辑线程上记深度（执行中禁止发起替换 / 卸载）。
     * 派发收尾（非逻辑线程）处执行排队的延迟控制操作。
     */
    private <T> T dispatch(Callable<T> task, String requestId) throws Exception {
        enterGate(requestId);
        try {
            return logic.call(() -> {
                int depth = executionDepth.get();
                executionDepth.set(depth + 1);
                try {
                    return task.call();
                } finally {
                    executionDepth.set(depth);
                }
            });
        } finally {
            exitGate();
            if (!logic.isOnLogicThread()) {
                runDeferredOps();
            }
        }
    }

    // --- 执行闸门 ---

    /** 是否有替换流程正在进行（drain 中；诊断与外壳 UI 用）。 */
    public boolean isReplacing() {
        synchronized (gate) {
            return swapping;
        }
    }

    private void enterGate(String requestId) {
        synchronized (gate) {
            if (swapping) {
                throw new LifeloomException("插件替换进行中，暂不接受新的执行请求: " + requestId);
            }
            activeExecutions++;
        }
    }

    private void exitGate() {
        synchronized (gate) {
            activeExecutions--;
            gate.notifyAll();
        }
    }

    /**
     * drain 语义：停止接受新的执行请求；等待在途派发完成后执行任务，然后恢复。
     * 不允许在钩子执行中（逻辑线程）发起——避免自等待与嵌套替换。
     */
    private <T> T withDrain(Callable<T> task) throws Exception {
        if (executionDepth.get() > 0) {
            throw new LifeloomException("不能在执行钩子期间执行该操作（当前线程正在执行中）");
        }
        synchronized (gate) {
            if (swapping) {
                throw new LifeloomException("已有替换 / 卸载流程进行中，暂不接受新的替换 / 卸载");
            }
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
