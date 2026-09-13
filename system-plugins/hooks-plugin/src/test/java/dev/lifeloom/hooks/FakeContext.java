package dev.lifeloom.hooks;

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
 * 测试用插件上下文：内存状态 + 钩子直调 + 解析器模拟（镜像核心的解析链语义）。
 */
final class FakeContext implements PluginContext {

    private final String id;
    private final Map<String, MechanismState> states = new LinkedHashMap<>();
    private final List<Mechanism> mechanisms = new ArrayList<>();
    private final List<HookResolver> resolvers = new ArrayList<>();

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

    /** 用已注册解析器解析钩子（镜像核心语义：跳过 → null、替代沿链、无意见原样）。 */
    String resolve(String hookId) {
        String current = hookId;
        Set<String> seen = new LinkedHashSet<>();
        for (int step = 0; step <= 8; step++) {
            if (!seen.add(current)) {
                throw new LifeloomException("钩子替代链成环: " + hookId);
            }
            String next = null;
            boolean skip = false;
            for (HookResolver resolver : resolvers) {
                HookResolver.Resolution resolution = resolver.resolve(current);
                if (resolution == null) {
                    continue;
                }
                if (resolution.isSkip()) {
                    skip = true;
                    break;
                }
                if (resolution.redirectTarget() != null) {
                    next = resolution.redirectTarget();
                    break;
                }
            }
            if (skip) {
                return null;
            }
            if (next == null) {
                return current;
            }
            current = next;
        }
        throw new LifeloomException("钩子替代链过深: " + hookId);
    }

    /** 清空已注册机制与解析器（模拟替换时的卸载；状态保留以验证数据不断档）。 */
    void unregisterAll() {
        mechanisms.clear();
        resolvers.clear();
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
    }

    @Override
    public void subscribe(String eventId, EventBus.Listener listener) {
    }

    @Override
    public void registerGatekeeper(Gatekeeper gatekeeper) {
    }

    @Override
    public void registerHookResolver(HookResolver resolver) {
        resolvers.add(resolver);
    }

    @Override
    public boolean askUser(String message) {
        return false;
    }

    @Override
    public List<Mechanism> mechanisms() {
        throw new UnsupportedOperationException("测试夹具未实现该原语: mechanisms");
    }

    @Override
    public List<dev.lifeloom.core.PluginDescriptor> plugins() {
        throw new UnsupportedOperationException("测试夹具未实现该原语: plugins");
    }

    @Override
    public void loadPlugin(java.nio.file.Path pluginFile) {
        throw new UnsupportedOperationException("测试夹具未实现该原语: loadPlugin");
    }

    @Override
    public void unloadPlugin(String pluginId) {
        throw new UnsupportedOperationException("测试夹具未实现该原语: unloadPlugin");
    }

    @Override
    public void replacePlugin(String pluginId, java.nio.file.Path newPluginFile) {
        throw new UnsupportedOperationException("测试夹具未实现该原语: replacePlugin");
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
