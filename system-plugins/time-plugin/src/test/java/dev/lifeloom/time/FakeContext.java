package dev.lifeloom.time;

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
 * 测试用插件上下文：内存状态 + 钩子直调 + 记录型发射（不实现跨插件权限等真实校验）。
 */
final class FakeContext implements PluginContext {

    private final String id;
    private final Map<String, MechanismState> states = new LinkedHashMap<>();
    private final List<Mechanism> mechanisms = new ArrayList<>();
    final List<String> emits = new ArrayList<>();

    FakeContext(String id) {
        this.id = id;
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
        throw new LifeloomException("测试夹具未提供外呼桩: " + hookId);
    }

    @Override
    public void emit(String eventId, String payload) {
        emits.add(eventId + "|" + payload);
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
