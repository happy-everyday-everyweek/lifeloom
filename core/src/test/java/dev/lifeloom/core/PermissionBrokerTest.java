package dev.lifeloom.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 权限闸门转发器测试（M2）：系统放行、非系统经闸门、无闸门 fail-closed、全部闸门放行才放行。
 */
public class PermissionBrokerTest {

    @Test
    public void systemCallerAlwaysAllowed() {
        PermissionBroker broker = new PermissionBroker();
        assertTrue("系统插件不受闸门限制（无闸门时也应放行）",
                broker.request(plugin("sys.p", PluginOrigin.SYSTEM), "op", "target", "detail"));

        broker.registerGatekeeper(plugin("sys.gate", PluginOrigin.SYSTEM), request -> false);
        assertTrue("系统插件不受闸门限制（闸门拒绝也不影响）",
                broker.request(plugin("sys.p", PluginOrigin.SYSTEM), "op", "target", "detail"));
    }

    @Test
    public void thirdPartyDeniedWithoutGatekeeper() {
        PermissionBroker broker = new PermissionBroker();
        assertFalse("无闸门时三方操作应 fail-closed",
                broker.request(plugin("tp.p", PluginOrigin.THIRD_PARTY), "op", "target", "detail"));
    }

    @Test
    public void gatekeeperDecisionHonored() {
        PermissionBroker allowBroker = new PermissionBroker();
        allowBroker.registerGatekeeper(plugin("sys.allow", PluginOrigin.SYSTEM), request -> true);
        assertTrue(allowBroker.request(plugin("tp.p", PluginOrigin.THIRD_PARTY), "op", "target", "detail"));

        PermissionBroker denyBroker = new PermissionBroker();
        denyBroker.registerGatekeeper(plugin("sys.deny", PluginOrigin.SYSTEM), request -> false);
        assertFalse(denyBroker.request(plugin("tp.p", PluginOrigin.THIRD_PARTY), "op", "target", "detail"));
    }

    @Test
    public void allGatekeepersMustAllow() {
        PermissionBroker broker = new PermissionBroker();
        broker.registerGatekeeper(plugin("sys.a", PluginOrigin.SYSTEM), request -> true);
        broker.registerGatekeeper(plugin("sys.b", PluginOrigin.SYSTEM), request -> false);
        assertFalse("任一闸门拒绝即拒绝",
                broker.request(plugin("tp.p", PluginOrigin.THIRD_PARTY), "op", "target", "detail"));
    }

    @Test(expected = LifeloomException.class)
    public void thirdPartyCannotRegisterGatekeeper() {
        PermissionBroker broker = new PermissionBroker();
        broker.registerGatekeeper(plugin("tp.p", PluginOrigin.THIRD_PARTY), request -> true);
    }

    @Test
    public void removeGatekeepersRestoresFailClosed() {
        PermissionBroker broker = new PermissionBroker();
        LoadedPlugin sys = plugin("sys.gate", PluginOrigin.SYSTEM);
        broker.registerGatekeeper(sys, request -> true);
        LoadedPlugin tp = plugin("tp.p", PluginOrigin.THIRD_PARTY);
        assertTrue(broker.request(tp, "op", "target", "detail"));

        broker.removeGatekeepersOf(sys);
        assertFalse("闸门移除后应回到 fail-closed",
                broker.request(tp, "op", "target", "detail"));
    }

    @Test
    public void gatekeeperReceivesRequestDetails() {
        PermissionBroker broker = new PermissionBroker();
        StringBuilder seen = new StringBuilder();
        broker.registerGatekeeper(plugin("sys.gate", PluginOrigin.SYSTEM), request -> {
            seen.append(request.pluginId()).append('|')
                    .append(request.operation()).append('|')
                    .append(request.targetId()).append('|')
                    .append(request.detail());
            return true;
        });
        broker.request(plugin("tp.p", PluginOrigin.THIRD_PARTY), "invoke-hook", "h.x", "调用钩子 h.x");
        assertEquals("tp.p|invoke-hook|h.x|调用钩子 h.x", seen.toString());
    }

    private static LoadedPlugin plugin(String id, PluginOrigin origin) {
        Plugin instance = new Plugin() {
            @Override
            public void onLoad(PluginContext context) {
            }
        };
        return TestPlugins.loaded(id, "1.0", instance, id + ".jar", origin);
    }
}
