package dev.lifeloom.core;

/** 默认插件上下文：把注册与状态访问请求转交给核心，并绑定到当前插件。 */
final class DefaultPluginContext implements PluginContext {

    private final LoadedPlugin plugin;
    private final Registry registry;
    private final MechanismStateStore states;

    DefaultPluginContext(LoadedPlugin plugin, Registry registry, MechanismStateStore states) {
        this.plugin = plugin;
        this.registry = registry;
        this.states = states;
    }

    @Override
    public String pluginId() {
        return plugin.descriptor().id();
    }

    @Override
    public void registerMechanism(Mechanism mechanism) {
        registry.registerMechanism(plugin, mechanism);
    }

    @Override
    public MechanismState stateFor(String mechanismId) {
        LoadedPlugin owner = registry.ownerOf(mechanismId);
        if (owner != null && owner != plugin) {
            throw new LifeloomException("跨机制访问未授权（授权流程随权限里程碑加入）: " + mechanismId);
        }
        return states.stateFor(mechanismId);
    }
}