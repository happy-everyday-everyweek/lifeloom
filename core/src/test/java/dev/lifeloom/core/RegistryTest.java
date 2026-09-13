package dev.lifeloom.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * 注册表测试：全局唯一性与卸载清理。
 */
public class RegistryTest {

    @Test(expected = LifeloomException.class)
    public void duplicateMechanismIdRejected() {
        Registry registry = new Registry();
        LoadedPlugin owner = emptyPlugin("a");
        registry.addPlugin(owner);
        registry.registerMechanism(owner, mechanism("m1", "m1.h"));
        registry.registerMechanism(owner, mechanism("m1", "m1.h2"));
    }

    @Test(expected = LifeloomException.class)
    public void duplicateHookIdRejected() {
        Registry registry = new Registry();
        LoadedPlugin owner = emptyPlugin("a");
        registry.addPlugin(owner);
        registry.registerMechanism(owner, mechanism("m1", "shared.h"));
        registry.registerMechanism(owner, mechanism("m2", "shared.h"));
    }

    @Test
    public void removePluginClearsRegistrations() {
        Registry registry = new Registry();
        LoadedPlugin owner = emptyPlugin("a");
        registry.addPlugin(owner);
        registry.registerMechanism(owner, mechanism("m1", "m1.h"));
        assertSame(owner, registry.ownerOf("m1"));

        LoadedPlugin removed = registry.removePlugin("a");
        assertSame(owner, removed);
        assertNull(registry.findMechanism("m1"));
        assertNull(registry.findHook("m1.h"));
        assertNull(registry.ownerOf("m1"));
        assertNotNull(registry.plugins());
    }

    private static LoadedPlugin emptyPlugin(String id) {
        Plugin plugin = new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
            }
        };
        return TestPlugins.loaded(id, "1.0", plugin, id + ".jar");
    }

    private static Mechanism mechanism(String mechanismId, String hookId) {
        return new Mechanism(mechanismId, mechanismId, List.of(new Hook() {
            @Override
            public String id() {
                return hookId;
            }

            @Override
            public void invoke(HookContext context) {
            }
        }));
    }
}