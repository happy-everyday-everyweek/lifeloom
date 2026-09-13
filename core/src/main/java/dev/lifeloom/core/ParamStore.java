package dev.lifeloom.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 参数存储（核心原语，M3）：按命名空间保存的字符串键值数据，带变更通知。
 *
 * <p>不做参数语义（结构、基础 / 派生、计算由插件定义）。机制状态占用命名空间
 * {@code mechanism:<机制ID>}：独立于插件注册生命周期，卸载 / 替换不清除，
 * 新版本可续读写（数据不断档）。其他命名空间（如实例参数）随成员插件接入。
 *
 * <p>变更监听默认绑定到插件，卸载 / 替换时自动解除（另有不绑定插件的原始监听，
 * 诊断 / 外壳用，见 {@link #watchRaw}）；通知在写入线程上同步发出，
 * 监听者抛错只记录、不影响写入方与其余监听者。
 */
public final class ParamStore {

    /** 变更监听：某命名空间内一次键值变更。 */
    @FunctionalInterface
    interface ChangeListener {
        void onChange(String namespaceId, String key, String oldValue, String newValue);
    }

    private static final class Subscription {

        /** 监听所属插件；原始监听（诊断 / 外壳用）时为 null。 */
        final LoadedPlugin owner;
        final ChangeListener listener;

        Subscription(LoadedPlugin owner, ChangeListener listener) {
            this.owner = owner;
            this.listener = listener;
        }
    }

    private final Map<String, Map<String, String>> spaces = new LinkedHashMap<>();
    private final Map<String, List<Subscription>> watchers = new LinkedHashMap<>();

    static String mechanismNamespace(String mechanismId) {
        return "mechanism:" + Objects.requireNonNull(mechanismId, "mechanismId");
    }

    /** 机制状态视图（命名空间 mechanism:<机制ID>；独立于插件注册生命周期）。 */
    synchronized MechanismState mechanismState(String mechanismId) {
        return new MapView(mechanismNamespace(mechanismId));
    }

    /** 某命名空间当前是否有非空数据（诊断用）。 */
    public synchronized boolean hasData(String namespaceId) {
        Map<String, String> map = spaces.get(namespaceId);
        return map != null && !map.isEmpty();
    }

    /** 监听某机制的变更（绑定插件；卸载 / 替换时自动解除）。 */
    synchronized void watchMechanism(LoadedPlugin owner, String mechanismId, ChangeListener listener) {
        if (listener == null) {
            throw new LifeloomException("变更监听不能为空");
        }
        watchers.computeIfAbsent(mechanismNamespace(mechanismId), key -> new ArrayList<>())
                .add(new Subscription(owner, listener));
    }

    /**
     * 注册一个不绑定插件的监听（诊断 / 外壳用；需自行取消）。
     *
     * @return 取消句柄；close 后不再收到通知
     */
    synchronized AutoCloseable watchRaw(String namespaceId, ChangeListener listener) {
        if (listener == null) {
            throw new LifeloomException("变更监听不能为空");
        }
        List<Subscription> list = watchers.computeIfAbsent(namespaceId, key -> new ArrayList<>());
        Subscription subscription = new Subscription(null, listener);
        list.add(subscription);
        return () -> {
            synchronized (ParamStore.this) {
                List<Subscription> current = watchers.get(namespaceId);
                if (current != null) {
                    current.remove(subscription);
                    if (current.isEmpty()) {
                        watchers.remove(namespaceId);
                    }
                }
            }
        };
    }

    /** 移除某插件的全部监听（卸载 / 装载失败时调用）。 */
    synchronized void removeWatchersOf(LoadedPlugin plugin) {
        for (List<Subscription> list : watchers.values()) {
            list.removeIf(subscription -> subscription.owner == plugin);
        }
        watchers.values().removeIf(List::isEmpty);
    }

    private synchronized Map<String, String> spaceMap(String namespaceId) {
        return spaces.computeIfAbsent(namespaceId, key -> new LinkedHashMap<>());
    }

    private void notifyChange(String namespaceId, String key, String oldValue, String newValue) {
        List<Subscription> snapshot;
        synchronized (this) {
            List<Subscription> list = watchers.get(namespaceId);
            if (list == null || list.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(list);
        }
        for (Subscription subscription : snapshot) {
            try {
                subscription.listener.onChange(namespaceId, key, oldValue, newValue);
            } catch (Exception e) {
                System.err.println("[core] 参数监听者异常（" + namespaceId + "." + key + "）: " + e);
            }
        }
    }

    /** 键值视图：写入与删除会触发变更通知。 */
    private final class MapView implements MechanismState {

        private final String namespaceId;

        MapView(String namespaceId) {
            this.namespaceId = namespaceId;
        }

        @Override
        public String get(String key) {
            synchronized (ParamStore.this) {
                return spaceMap(namespaceId).get(key);
            }
        }

        @Override
        public void put(String key, String value) {
            Objects.requireNonNull(value, "value");
            String oldValue;
            synchronized (ParamStore.this) {
                oldValue = spaceMap(namespaceId).put(key, value);
            }
            if (!value.equals(oldValue)) {
                notifyChange(namespaceId, key, oldValue, value);
            }
        }

        @Override
        public String remove(String key) {
            String oldValue;
            synchronized (ParamStore.this) {
                oldValue = spaceMap(namespaceId).remove(key);
            }
            if (oldValue != null) {
                notifyChange(namespaceId, key, oldValue, null);
            }
            return oldValue;
        }

        @Override
        public Set<String> keys() {
            synchronized (ParamStore.this) {
                return Collections.unmodifiableSet(new LinkedHashSet<>(spaceMap(namespaceId).keySet()));
            }
        }
    }
}
