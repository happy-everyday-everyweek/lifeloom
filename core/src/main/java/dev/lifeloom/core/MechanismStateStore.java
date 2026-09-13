package dev.lifeloom.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 机制状态的存储与视图分发（核心内部）。
 *
 * <p>状态按机制 ID 保存，不随插件卸载 / 替换而清除；
 * 同一机制 ID 的新版本插件可继续读写旧数据。
 */
public final class MechanismStateStore {

    private final Map<String, Map<String, String>> states = new LinkedHashMap<>();

    /** 获取某机制的状态视图；不存在时创建空状态。 */
    public MechanismState stateFor(String mechanismId) {
        Map<String, String> map = states.computeIfAbsent(mechanismId, key -> new LinkedHashMap<>());
        return new MapView(map);
    }

    /** 该机制当前是否有非空状态数据（诊断用）。 */
    public boolean hasState(String mechanismId) {
        Map<String, String> map = states.get(mechanismId);
        return map != null && !map.isEmpty();
    }

    private static final class MapView implements MechanismState {

        private final Map<String, String> map;

        MapView(Map<String, String> map) {
            this.map = map;
        }

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, Objects.requireNonNull(value, "value"));
        }

        @Override
        public String remove(String key) {
            return map.remove(key);
        }

        @Override
        public Set<String> keys() {
            return Collections.unmodifiableSet(map.keySet());
        }
    }
}