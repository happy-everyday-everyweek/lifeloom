package dev.lifeloom.params;

import dev.lifeloom.core.LifeloomException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 参数插件测试（接缝 = 插件钩子契约：register / set / get / has / list / remove / clear
 * 与 changed 事件）。
 */
public class ParamsPluginTest {

    private static final String M = "dev.lifeloom.params";

    private final FakeContext ctx = new FakeContext("dev.lifeloom.params.plugin");

    private void load() {
        new ParamsPlugin().onLoad(ctx);
    }

    /** 期望一次调用被拒绝（LifeloomException）。 */
    private void expectRejected(String hookId, String input) {
        try {
            ctx.call(hookId, input);
            fail("应拒绝: " + hookId + " <- " + input);
        } catch (LifeloomException expected) {
            // 期望路径
        } catch (Exception e) {
            fail("应为 LifeloomException，实际: " + e);
        }
    }

    private String register(String owner, String name, String type) throws Exception {
        return register(owner, name, type, null, null);
    }

    private String register(String owner, String name, String type, String value, String expression)
            throws Exception {
        StringBuilder input = new StringBuilder("owner=").append(owner)
                .append(";name=").append(name).append(";type=").append(type);
        if (value != null) {
            input.append(";value=").append(value);
        }
        if (expression != null) {
            input.append(";expression=").append(expression);
        }
        return ctx.call(M + ".register", input.toString());
    }

    private String set(String owner, String name, String value) throws Exception {
        return ctx.call(M + ".set", "owner=" + owner + ";name=" + name + ";value=" + value);
    }

    private String get(String owner, String name) throws Exception {
        return ctx.call(M + ".get", "owner=" + owner + ";name=" + name);
    }

    @Test
    public void registerAndGetBase() throws Exception {
        load();
        assertEquals("registered=instance:alice|hp", register("instance:alice", "hp", "base"));
        assertEquals("value=0", get("instance:alice", "hp"));
        assertEquals("registered=instance:alice|mp", register("instance:alice", "mp", "base", "10", null));
        assertEquals("value=10", get("instance:alice", "mp"));
        register("instance:alice", "drift", "base", "-2.5", null);
        assertEquals("value=-2.5", get("instance:alice", "drift"));
    }

    @Test
    public void registerRejectsInvalidInput() throws Exception {
        load();
        expectRejected(M + ".register", null);
        expectRejected(M + ".register", "owner=alice;name=hp;type=base");
        expectRejected(M + ".register", "owner=instance:;name=hp;type=base");
        expectRejected(M + ".register", "owner=instance:alice;name=;type=base");
        expectRejected(M + ".register", "owner=instance:alice;name=1hp;type=base");
        expectRejected(M + ".register", "owner=instance:alice;name=hp;type=weird");
        expectRejected(M + ".register", "owner=instance:alice;name=hp;type=derived");
        register("instance:alice", "hp", "base");
        expectRejected(M + ".register", "owner=instance:alice;name=hp;type=base");
    }

    @Test
    public void derivedComputesExpressions() throws Exception {
        load();
        register("instance:alice", "hp", "base", "10", null);
        register("instance:alice", "armor", "base", "4", null);
        register("instance:alice", "attack", "derived", null, "(hp + armor) * 2 - 1");
        register("instance:alice", "half", "derived", null, "-hp / 2");
        register("instance:alice", "ratio", "derived", null, "armor / 8");
        assertEquals("value=27", get("instance:alice", "attack"));
        assertEquals("value=-5", get("instance:alice", "half"));
        assertEquals("value=0.5", get("instance:alice", "ratio"));
    }

    @Test
    public void setRecomputesDerivedChainAndEmits() throws Exception {
        load();
        register("instance:alice", "hp", "base", "10", null);
        register("instance:alice", "armor", "base", "4", null);
        register("instance:alice", "attack", "derived", null, "(hp + armor) * 2 - 1");

        ctx.events.clear();
        assertEquals("set=hp=20", set("instance:alice", "hp", "20"));
        assertEquals("value=47", get("instance:alice", "attack"));
        assertEquals(List.of(
                M + ".changed|owner=instance:alice;name=attack;value=47",
                M + ".changed|owner=instance:alice;name=hp;value=20"), ctx.events);

        ctx.events.clear();
        assertEquals("set=hp=20", set("instance:alice", "hp", "20"));
        assertEquals(List.of(), ctx.events);
    }

    @Test
    public void setRejectsDerivedAndUnknown() throws Exception {
        load();
        register("instance:alice", "hp", "base");
        register("instance:alice", "d", "derived", null, "hp + 1");
        expectRejected(M + ".set", "owner=instance:alice;name=d;value=5");
        expectRejected(M + ".set", "owner=instance:alice;name=ghost;value=5");
        expectRejected(M + ".set", "owner=instance:alice;name=hp;value=abc");
        expectRejected(M + ".set", "owner=instance:alice;name=hp;value=NaN");
        expectRejected(M + ".set", "owner=instance:alice;name=hp;value=Infinity");
        expectRejected(M + ".set", "owner=instance:alice;name=hp;value=");
    }

    @Test
    public void derivedRejectsUnknownRefsAndSyntax() throws Exception {
        load();
        register("instance:alice", "hp", "base");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=hp + mp");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=1 +");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=(1 + 2");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=1) + 2");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=1 2");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=1 @ 2");
        expectRejected(M + ".register", "owner=instance:alice;name=d;type=derived;expression=");
    }

    @Test
    public void divideByZeroIsAtomic() throws Exception {
        load();
        register("instance:alice", "hp", "base", "1", null);
        register("instance:alice", "half", "derived", null, "10 / hp");
        assertEquals("value=10", get("instance:alice", "half"));

        ctx.events.clear();
        expectRejected(M + ".set", "owner=instance:alice;name=hp;value=0");
        assertEquals("value=1", get("instance:alice", "hp"));
        assertEquals("value=10", get("instance:alice", "half"));
        assertEquals(List.of(), ctx.events);
    }

    @Test
    public void ownersAreIsolated() throws Exception {
        load();
        register("instance:alice", "hp", "base", "10", null);
        register("mechanism:dev.lifeloom.time", "hp", "base", "99", null);
        assertEquals("value=10", get("instance:alice", "hp"));
        assertEquals("value=99", get("mechanism:dev.lifeloom.time", "hp"));
        set("instance:alice", "hp", "20");
        assertEquals("value=99", get("mechanism:dev.lifeloom.time", "hp"));
    }

    @Test
    public void removeAndReferencedProtection() throws Exception {
        load();
        register("instance:alice", "hp", "base", "10", null);
        register("instance:alice", "boost", "derived", null, "hp + 1");
        expectRejected(M + ".remove", "owner=instance:alice;name=hp");
        assertEquals("removed=instance:alice|boost",
                ctx.call(M + ".remove", "owner=instance:alice;name=boost"));
        expectRejected(M + ".get", "owner=instance:alice;name=boost");
        assertEquals("removed=instance:alice|hp",
                ctx.call(M + ".remove", "owner=instance:alice;name=hp"));
        assertEquals("has=false", ctx.call(M + ".has", "owner=instance:alice;name=hp"));
    }

    @Test
    public void clearOwnerRemovesAll() throws Exception {
        load();
        register("instance:alice", "hp", "base");
        register("instance:alice", "mp", "base");
        register("instance:alice", "sum", "derived", null, "hp + mp");
        assertEquals("cleared=instance:alice;count=3",
                ctx.call(M + ".clear", "owner=instance:alice"));
        assertEquals("params=[]", ctx.call(M + ".list", "owner=instance:alice"));
        assertEquals("has=false", ctx.call(M + ".has", "owner=instance:alice;name=hp"));
    }

    @Test
    public void hasAndList() throws Exception {
        load();
        register("instance:alice", "b", "base");
        register("instance:alice", "a", "base");
        register("instance:alice", "c", "base");
        assertEquals("params=[a,b,c]", ctx.call(M + ".list", "owner=instance:alice"));
        assertEquals("has=true", ctx.call(M + ".has", "owner=instance:alice;name=a"));
        assertEquals("has=false", ctx.call(M + ".has", "owner=instance:alice;name=z"));
    }

    @Test
    public void getRejectsUnknown() throws Exception {
        load();
        expectRejected(M + ".get", "owner=instance:alice;name=ghost");
    }

    @Test
    public void reloadKeepsData() throws Exception {
        load();
        register("instance:alice", "hp", "base", "10", null);
        register("instance:alice", "d", "derived", null, "hp * 2");
        set("instance:alice", "hp", "7");
        assertEquals("value=14", get("instance:alice", "d"));

        ctx.unregisterAll();
        load();

        assertEquals("value=7", get("instance:alice", "hp"));
        assertEquals("value=14", get("instance:alice", "d"));
        set("instance:alice", "hp", "9");
        assertEquals("value=18", get("instance:alice", "d"));
    }

    @Test
    public void chainedDerived() throws Exception {
        load();
        register("instance:alice", "c", "base", "2", null);
        register("instance:alice", "b", "derived", null, "c * 3");
        register("instance:alice", "a", "derived", null, "b + 1");
        assertEquals("value=6", get("instance:alice", "b"));
        assertEquals("value=7", get("instance:alice", "a"));

        ctx.events.clear();
        set("instance:alice", "c", "5");
        assertEquals("value=15", get("instance:alice", "b"));
        assertEquals("value=16", get("instance:alice", "a"));
        assertEquals(List.of(
                M + ".changed|owner=instance:alice;name=a;value=16",
                M + ".changed|owner=instance:alice;name=b;value=15",
                M + ".changed|owner=instance:alice;name=c;value=5"), ctx.events);
    }
}
