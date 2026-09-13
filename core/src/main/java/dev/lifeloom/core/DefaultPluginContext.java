package dev.lifeloom.core;

/** 默认插件上下文：把注册、状态、闸门与提示请求转交核心，并绑定到当前插件。 */
final class DefaultPluginContext implements PluginContext {

    private final LoadedPlugin plugin;
    private final Core core;

    DefaultPluginContext(LoadedPlugin plugin, Core core) {
        this.plugin = plugin;
        this.core = core;
    }

    @Override
    public String pluginId() {
        return plugin.descriptor().id();
    }

    @Override
    public void registerMechanism(Mechanism mechanism) {
        core.registry().registerMechanism(plugin, mechanism);
    }

    @Override
    public MechanismState stateFor(String mechanismId) {
        LoadedPlugin owner = core.registry().ownerOf(mechanismId);
        if (owner == plugin || owner == null) {
            return core.states().stateFor(mechanismId);
        }
        boolean allowed = core.permissions().request(plugin, "access-mechanism-state",
                mechanismId, "访问机制“" + mechanismId + "”的状态数据（属于插件 " + owner.descriptor().id() + "）");
        if (!allowed) {
            throw new LifeloomException("权限未放行：访问机制状态 " + mechanismId);
        }
        return core.states().stateFor(mechanismId);
    }

    @Override
    public void invokeHook(String hookId) throws Exception {
        core.invokeHookFrom(plugin, hookId);
    }

    @Override
    public void registerGatekeeper(Gatekeeper gatekeeper) {
        core.permissions().registerGatekeeper(plugin, gatekeeper);
    }

    @Override
    public boolean askUser(String message) {
        if (plugin.origin() != PluginOrigin.SYSTEM) {
            throw new LifeloomException("仅系统插件可请求用户提示: " + plugin.descriptor().id());
        }
        return core.userPrompt().confirm(message);
    }
}