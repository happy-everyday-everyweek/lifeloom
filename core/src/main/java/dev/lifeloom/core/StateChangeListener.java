package dev.lifeloom.core;

/** 机制状态变更监听（{@link PluginContext#watchState} 使用）。 */
@FunctionalInterface
public interface StateChangeListener {

    /**
     * 某机制状态发生一次变更（写入与删除都会通知；同值重复写入不通知）。
     *
     * @param mechanismId 机制 ID
     * @param key         变更的键
     * @param oldValue    旧值；此前不存在时为 null
     * @param newValue    新值；被删除时为 null
     */
    void onChange(String mechanismId, String key, String oldValue, String newValue);
}
