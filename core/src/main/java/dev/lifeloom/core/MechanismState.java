package dev.lifeloom.core;

import java.util.Set;

/**
 * 机制状态：按机制归属的键值数据（M1 最小版）。
 *
 * <p>状态以机制 ID 为键保存于核心，独立于插件注册的生命周期：
 * 插件被卸载或整体替换时，其机制状态保留（“数据不断档”，见 ADR-0002），
 * 后续由存档插件负责持久化。
 *
 * <p>M1 仅支持字符串键值；更丰富的参数系统随参数存储里程碑加入。
 */
public interface MechanismState {

    /** 读取值；不存在时返回 null。 */
    String get(String key);

    /** 写入值（value 不能为 null）。 */
    void put(String key, String value);

    /** 删除键；返回旧值或 null。 */
    String remove(String key);

    /** 当前全部键（只读视图）。 */
    Set<String> keys();
}