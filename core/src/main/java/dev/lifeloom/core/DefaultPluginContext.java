package dev.lifeloom.core;

/** 默认插件上下文：把注册请求转交给注册表，并绑定到当前插件。 */
final class DefaultPluginContext implements PluginContext {

    private final LoadedPlugin plugin;
    private final Registry registry;

    DefaultPluginContext(LoadedPlugin plugin, Registry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public String pluginId() {
        return plugin.descriptor().id();
    }

    @Override
    public void registerMechanism(Mechanism mechanism) {
        registry.registerMechanism(plugin, mechanism);
    }
}