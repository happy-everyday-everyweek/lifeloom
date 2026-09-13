package dev.lifeloom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限闸门转发器（核心原语，极薄）。
 *
 * <p>语义：系统插件与核心自身不受限；非系统插件的操作请求转发给已注册闸门，
 * 全部放行才放行；无闸门时拒绝（fail-closed，避免未装权限插件时误放行）。
 * 放行策略与用户提示由闸门实现承担——权限插件是其一。
 *
 * <p>闸门随其注册插件的卸载（或替换）而移除。
 */
public final class PermissionBroker {

    private final Map<LoadedPlugin, List<Gatekeeper>> gatekeepers = new LinkedHashMap<>();

    /** 注册闸门；仅系统插件可注册。 */
    synchronized void registerGatekeeper(LoadedPlugin registrant, Gatekeeper gatekeeper) {
        if (registrant.origin() != PluginOrigin.SYSTEM) {
            throw new LifeloomException("仅系统插件可注册闸门: " + registrant.descriptor().id());
        }
        gatekeepers.computeIfAbsent(registrant, key -> new ArrayList<>()).add(gatekeeper);
    }

    /** 移除某插件注册的全部闸门（卸载 / 替换时调用）。 */
    synchronized void removeGatekeepersOf(LoadedPlugin plugin) {
        gatekeepers.remove(plugin);
    }

    /** 是否已有闸门注册（诊断用）。 */
    public synchronized boolean hasGatekeeper() {
        return !gatekeepers.isEmpty();
    }

    /**
     * 请求放行一次操作。
     *
     * @param caller    发起操作的插件
     * @param operation 操作类别
     * @param targetId  操作目标
     * @param detail    面向用户的描述
     * @return 是否放行
     */
    public boolean request(LoadedPlugin caller, String operation, String targetId, String detail) {
        if (caller.origin() == PluginOrigin.SYSTEM) {
            return true;
        }
        List<Gatekeeper> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>();
            for (List<Gatekeeper> list : gatekeepers.values()) {
                snapshot.addAll(list);
            }
        }
        if (snapshot.isEmpty()) {
            return false;
        }
        PermissionRequest request = new PermissionRequest(
                caller.descriptor().id(), operation, targetId, detail);
        for (Gatekeeper gatekeeper : snapshot) {
            if (!gatekeeper.decide(request)) {
                return false;
            }
        }
        return true;
    }
}