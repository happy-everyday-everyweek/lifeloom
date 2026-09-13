package dev.lifeloom.core;

import java.util.Objects;

/**
 * 已装载插件的运行时句柄：清单 + 类加载器 + 插件实例 + 状态。
 *
 * <p>由 {@link PluginLoader} 实现创建，核心编排其生命周期。
 * 类加载器的关闭由装载器实现负责（{@link PluginLoader#unload(LoadedPlugin)}）。
 */
public final class LoadedPlugin {

    /** 插件生命周期状态。 */
    public enum State {
        /** 已装载、正在执行 onLoad/注册。 */
        LOADING,
        /** 已装载且 onLoad 成功。 */
        ACTIVE,
        /** 已卸载。 */
        UNLOADED,
        /** 装载失败。 */
        FAILED
    }

    private final PluginDescriptor descriptor;
    private final ClassLoader classLoader;
    private final Plugin instance;
    private State state = State.LOADING;

    public LoadedPlugin(PluginDescriptor descriptor, ClassLoader classLoader, Plugin instance) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    public Plugin instance() {
        return instance;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = Objects.requireNonNull(state, "state");
    }
}