package dev.lifeloom.core;

import java.util.Set;

/**
 * 机制状态：按机制归属的键值数据。
 *
 * <p>状态按机制 ID 保存于核心（由参数存储 ParamStore 提供），独立于插件注册的生命周期：
 * 插件被卸载或整体替换时，其机制状态保留（“数据不断档”，见 ADR-0002），
 * 后续由存档插件负责持久化。
 *
 * <p>当前为字符串键值；更丰富的参数语义（基础 / 派生等）由参数成员插件承载。
 */
public interface MechanismState {

    /** 读取值；不存在时返回 null。 */
    String get(String key);

    /** 写入值（value 不能为 null）。 */
    void put(String key, String value);

    /** 删除键；返回旧值或 null。 */
    String remove(String key);

    /** 当前全部键（快照）。 */
    Set<String> keys();
}
