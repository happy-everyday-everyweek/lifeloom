package dev.lifeloom.core;

/** 默认插件上下文：把注册、状态、闸门、解析器、事件与提示请求转交核心，并绑定到当前插件。 */
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
            return core.params().mechanismState(mechanismId);
        }
        boolean allowed = core.permissions().request(plugin, "access-mechanism-state",
                mechanismId, "访问机制“" + mechanismId + "”的状态数据（属于插件 " + owner.descriptor().id() + "）");
        if (!allowed) {
            throw new LifeloomException("权限未放行：访问机制状态 " + mechanismId);
        }
        return core.params().mechanismState(mechanismId);
    }

    @Override
    public void watchState(String mechanismId, StateChangeListener listener) {
        LoadedPlugin owner = core.registry().ownerOf(mechanismId);
        if (owner != plugin && owner != null) {
            boolean allowed = core.permissions().request(plugin, "watch-mechanism-state",
                    mechanismId, "监听机制“" + mechanismId + "”的状态变更（属于插件 " + owner.descriptor().id() + "）");
            if (!allowed) {
                throw new LifeloomException("权限未放行：监听机制状态 " + mechanismId);
            }
        }
        core.params().watchMechanism(plugin, mechanismId,
                (namespaceId, key, oldValue, newValue) -> listener.onChange(mechanismId, key, oldValue, newValue));
    }

    @Override
    public void invokeHook(String hookId) throws Exception {
        core.invokeHookFrom(plugin, hookId);
    }

    @Override
    public String invokeHook(String hookId, String input) throws Exception {
        return core.invokeHookFrom(plugin, hookId, input);
    }

    @Override
    public void emit(String eventId, String payload) {
        core.emitEvent(plugin, eventId, payload);
    }

    @Override
    public void subscribe(String eventId, EventBus.Listener listener) {
        core.events().subscribe(plugin, eventId, listener);
    }

    @Override
    public void registerGatekeeper(Gatekeeper gatekeeper) {
        core.permissions().registerGatekeeper(plugin, gatekeeper);
    }

    @Override
    public void registerHookResolver(HookResolver resolver) {
        core.hookResolvers().register(plugin, resolver);
    }

    @Override
    public boolean askUser(String message) {
        if (plugin.origin() != PluginOrigin.SYSTEM) {
            throw new LifeloomException("仅系统插件可请求用户提示: " + plugin.descriptor().id());
        }
        return core.userPrompt().confirm(message);
    }
}
