package dev.lifeloom.actions;

import dev.lifeloom.core.EventBus;
import dev.lifeloom.core.Gatekeeper;
import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.HookResolver;
import dev.lifeloom.core.LifeloomException;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.PluginContext;
import dev.lifeloom.core.StateChangeListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试用插件上下文：内存状态 + 钩子直调 + 外呼桩（invokeHook 按桩分发）。
 */
final class FakeContext implements PluginContext {

    /** 外呼桩。 */
    interface Stub {
        String invoke(String input) throws Exception;
    }

    private final String id;
    private final Map<String, MechanismState> states = new LinkedHashMap<>();
    private final List<Mechanism> mechanisms = new ArrayList<>();
    private final Map<String, Stub> stubs = new LinkedHashMap<>();

    FakeContext(String id) {
        this.id = id;
    }

    void stub(String hookId, Stub stub) {
        stubs.put(hookId, stub);
    }

    /** 直接调用某已注册钩子（模拟核心的调用入口）。 */
    String call(String hookId, String input) throws Exception {
        for (Mechanism mechanism : mechanisms) {
            for (Hook hook : mechanism.hooks()) {
                if (hook.id().equals(hookId)) {
                    HookContext context = new HookContext(
                            hook.id(), mechanism.id(), stateFor(mechanism.id()), input);
                    hook.invoke(context);
                    return context.output();
                }
            }
        }
        throw new LifeloomException("钩子未注册: " + hookId);
    }

    /** 清空已注册机制（模拟替换时的卸载；状态保留以验证数据不断档）。 */
    void unregisterAll() {
        mechanisms.clear();
    }

    @Override
    public String pluginId() {
        return id;
    }

    @Override
    public void registerMechanism(Mechanism mechanism) {
        mechanisms.add(mechanism);
    }

    @Override
    public MechanismState stateFor(String mechanismId) {
        return states.computeIfAbsent(mechanismId, key -> new MapState());
    }

    @Override
    public void watchState(String mechanismId, StateChangeListener listener) {
    }

    @Override
    public void invokeHook(String hookId) throws Exception {
        invokeHook(hookId, null);
    }

    @Override
    public String invokeHook(String hookId, String input) throws Exception {
        Stub stub = stubs.get(hookId);
        if (stub == null) {
            throw new LifeloomException("钩子未注册: " + hookId);
        }
        return stub.invoke(input);
    }

    @Override
    public void emit(String eventId, String payload) {
    }

    @Override
    public void subscribe(String eventId, EventBus.Listener listener) {
    }

    @Override
    public void registerGatekeeper(Gatekeeper gatekeeper) {
    }

    @Override
    public void registerHookResolver(HookResolver resolver) {
    }

    @Override
    public boolean askUser(String message) {
        return false;
    }

    /** 内存键值状态。 */
    private static final class MapState implements MechanismState {

        private final Map<String, String> map = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }

        @Override
        public String remove(String key) {
            return map.remove(key);
        }

        @Override
        public Set<String> keys() {
            return new LinkedHashSet<>(map.keySet());
        }
    }
}
