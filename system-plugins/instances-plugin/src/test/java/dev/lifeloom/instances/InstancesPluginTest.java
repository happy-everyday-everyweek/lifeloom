package dev.lifeloom.instances;

import dev.lifeloom.core.LifeloomException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 实例插件测试（接缝 = 插件钩子契约：create / destroy / get / relate / unrelate /
 * relations / enter / active）。
 */
public class InstancesPluginTest {

    private static final String M = "dev.lifeloom.instances";

    private final FakeContext ctx = new FakeContext("dev.lifeloom.instances.plugin");

    private void load() {
        new InstancesPlugin().onLoad(ctx);
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

    private void create(String id, String category, String species) throws Exception {
        ctx.call(M + ".create", "id=" + id + ";category=" + category + ";species=" + species);
    }

    @Test
    public void createAndGet() throws Exception {
        load();
        assertEquals("created=alice", ctx.call(M + ".create", "id=alice;category=人;species=human"));
        assertEquals("category=人;species=human", ctx.call(M + ".get", "alice"));
    }

    @Test
    public void createRejectsInvalidInput() throws Exception {
        load();
        expectRejected(M + ".create", null);
        expectRejected(M + ".create", "id=x;category=植物;species=s");
        expectRejected(M + ".create", "id=x;category=人");
        expectRejected(M + ".create", "category=人;species=s");
        create("alice", "人", "human");
        expectRejected(M + ".create", "id=alice;category=人;species=human");
    }

    @Test
    public void destroyCleansUpRelationsAndActive() throws Exception {
        load();
        create("alice", "人", "human");
        create("cup", "物品", "cup");
        ctx.call(M + ".relate", "type=hold;a=alice;b=cup");
        ctx.call(M + ".enter", "alice");

        assertEquals("destroyed=alice", ctx.call(M + ".destroy", "alice"));
        expectRejected(M + ".get", "alice");
        assertEquals("contains=[];inside=-;holds=[];heldBy=-", ctx.call(M + ".relations", "cup"));
        assertEquals("active=-", ctx.call(M + ".active", null));
    }

    @Test
    public void containQueriesBothDirections() throws Exception {
        load();
        create("room", "物品", "room");
        create("ball", "物品", "ball");
        assertEquals("related=contain:room:ball", ctx.call(M + ".relate", "type=contain;a=room;b=ball"));
        assertEquals("contains=[ball];inside=-;holds=[];heldBy=-", ctx.call(M + ".relations", "room"));
        assertEquals("contains=[];inside=room;holds=[];heldBy=-", ctx.call(M + ".relations", "ball"));
    }

    @Test
    public void holdQueriesBothDirections() throws Exception {
        load();
        create("alice", "人", "human");
        create("cup", "物品", "cup");
        assertEquals("related=hold:alice:cup", ctx.call(M + ".relate", "type=hold;a=alice;b=cup"));
        assertEquals("contains=[];inside=-;holds=[cup];heldBy=-", ctx.call(M + ".relations", "alice"));
        assertEquals("contains=[];inside=-;holds=[];heldBy=alice", ctx.call(M + ".relations", "cup"));
    }

    @Test
    public void relateRejectsInvalidInput() throws Exception {
        load();
        create("alice", "人", "human");
        expectRejected(M + ".relate", "type=eats;a=alice;b=alice");
        expectRejected(M + ".relate", "type=hold;a=alice;b=alice");
        expectRejected(M + ".relate", "type=hold;a=alice;b=ghost");
        expectRejected(M + ".relate", "a=alice;b=alice");
        expectRejected(M + ".relate", null);
    }

    @Test
    public void containUniquenessEnforced() throws Exception {
        load();
        create("room1", "物品", "room");
        create("room2", "物品", "room");
        create("ball", "物品", "ball");
        ctx.call(M + ".relate", "type=contain;a=room1;b=ball");
        expectRejected(M + ".relate", "type=contain;a=room2;b=ball");
        assertEquals("contains=[];inside=room1;holds=[];heldBy=-", ctx.call(M + ".relations", "ball"));
    }

    @Test
    public void unrelateIsIdempotent() throws Exception {
        load();
        create("room", "物品", "room");
        create("ball", "物品", "ball");
        ctx.call(M + ".relate", "type=contain;a=room;b=ball");
        assertEquals("unrelated=contain:room:ball", ctx.call(M + ".unrelate", "type=contain;a=room;b=ball"));
        assertEquals("contains=[];inside=-;holds=[];heldBy=-", ctx.call(M + ".relations", "room"));
        assertEquals("unrelated=contain:room:ball", ctx.call(M + ".unrelate", "type=contain;a=room;b=ball"));
    }

    @Test
    public void enterRules() throws Exception {
        load();
        create("alice", "人", "human");
        create("cat", "动物", "cat");
        assertEquals("entered=alice", ctx.call(M + ".enter", "alice"));
        assertEquals("active=alice", ctx.call(M + ".active", null));
        expectRejected(M + ".enter", "cat");
        expectRejected(M + ".enter", "ghost");
    }

    @Test
    public void destroyRejectsMissing() throws Exception {
        load();
        expectRejected(M + ".destroy", "ghost");
    }

    @Test
    public void reloadKeepsData() throws Exception {
        load();
        create("alice", "人", "human");
        create("cup", "物品", "cup");
        ctx.call(M + ".relate", "type=hold;a=alice;b=cup");
        ctx.call(M + ".enter", "alice");

        ctx.unregisterAll();
        load();

        assertEquals("category=人;species=human", ctx.call(M + ".get", "alice"));
        assertEquals("contains=[];inside=-;holds=[cup];heldBy=-", ctx.call(M + ".relations", "alice"));
        assertEquals("active=alice", ctx.call(M + ".active", null));
    }
}
