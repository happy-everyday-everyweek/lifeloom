package dev.lifeloom.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册表：核心内插件、机制、钩子的唯一索引。
 *
 * <p>非线程安全实现——按单逻辑线程模型（见 docs/核心设计.md），
 * 注册与调用均在逻辑线程上串行发生（2026-09-13 落地）。
 */
public final class Registry {

    /** 钩子引用：执行与权限判定需要知道钩子、其所属机制与所属插件。 */
    public static final class HookRef {

        private final LoadedPlugin plugin;
        private final Mechanism mechanism;
        private final Hook hook;

        HookRef(LoadedPlugin plugin, Mechanism mechanism, Hook hook) {
            this.plugin = plugin;
            this.mechanism = mechanism;
            this.hook = hook;
        }

        public LoadedPlugin plugin() {
            return plugin;
        }

        public Mechanism mechanism() {
            return mechanism;
        }

        public Hook hook() {
            return hook;
        }
    }

    private final Map<String, LoadedPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, Mechanism> mechanisms = new LinkedHashMap<>();
    private final Map<String, LoadedPlugin> mechanismOwners = new LinkedHashMap<>();
    private final Map<String, HookRef> hooks = new LinkedHashMap<>();

    // --- 插件 ---

    public void addPlugin(LoadedPlugin plugin) {
        String id = plugin.descriptor().id();
        if (plugins.containsKey(id)) {
            throw new LifeloomException("插件 ID 重复: " + id);
        }
        plugins.put(id, plugin);
    }

    public LoadedPlugin findPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    /** 返回全部插件（装载顺序）。 */
    public Collection<LoadedPlugin> plugins() {
        return plugins.values();
    }

    /**
     * 移除插件及其注册的全部机制与钩子。
     *
     * @return 被移除的插件；不存在时为 null
     */
    public LoadedPlugin removePlugin(String pluginId) {
        LoadedPlugin removed = plugins.remove(pluginId);
        if (removed == null) {
            return null;
        }
        Iterator<Map.Entry<String, Mechanism>> it = mechanisms.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Mechanism> entry = it.next();
            if (mechanismOwners.get(entry.getKey()) == removed) {
                for (Hook hook : entry.getValue().hooks()) {
                    hooks.remove(hook.id());
                }
                mechanismOwners.remove(entry.getKey());
                it.remove();
            }
        }
        return removed;
    }

    // --- 机制与钩子 ---

    /**
     * 注册机制（归属 owner 插件）。做全局唯一性校验：
     * 机制 ID 不得重复；其所有钩子 ID 也不得与已注册项重复。
     */
    public void registerMechanism(LoadedPlugin owner, Mechanism mechanism) {
        if (mechanisms.containsKey(mechanism.id())) {
            throw new LifeloomException("机制 ID 重复: " + mechanism.id());
        }
        for (Hook hook : mechanism.hooks()) {
            if (hooks.containsKey(hook.id())) {
                throw new LifeloomException("钩子 ID 重复: " + hook.id());
            }
        }
        mechanisms.put(mechanism.id(), mechanism);
        mechanismOwners.put(mechanism.id(), owner);
        for (Hook hook : mechanism.hooks()) {
            hooks.put(hook.id(), new HookRef(owner, mechanism, hook));
        }
    }

    public Mechanism findMechanism(String mechanismId) {
        return mechanisms.get(mechanismId);
    }

    /** 机制归属插件；未注册时返回 null。 */
    public LoadedPlugin ownerOf(String mechanismId) {
        return mechanismOwners.get(mechanismId);
    }

    public HookRef findHook(String hookId) {
        return hooks.get(hookId);
    }

    /** 返回全部机制（注册顺序）。 */
    public Collection<Mechanism> mechanisms() {
        return mechanisms.values();
    }

    /** 返回全部钩子引用（注册顺序）。 */
    public Collection<HookRef> hooks() {
        return hooks.values();
    }
}