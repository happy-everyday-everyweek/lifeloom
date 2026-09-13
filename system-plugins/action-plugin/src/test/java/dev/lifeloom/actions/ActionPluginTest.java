package dev.lifeloom.actions;

import dev.lifeloom.core.LifeloomException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 动作插件测试（接缝 = 插件钩子契约：register / can / execute / status，
 * 以及与时间插件的驱动契约 time.advance）。
 */
public class ActionPluginTest {

    private static final String M = "dev.lifeloom.actions";

    private final FakeContext ctx = new FakeContext("dev.lifeloom.actions.plugin");

    private void load() {
        new ActionPlugin().onLoad(ctx);
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

    @Test
    public void statusStartsEmpty() throws Exception {
        load();
        assertEquals("actions=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void registerStoresAction() throws Exception {
        load();
        assertEquals("registered=demo.eat", ctx.call(M + ".register", "id=demo.eat;minutes=15"));
        assertEquals("actions=[demo.eat(15)]", ctx.call(M + ".status", null));
    }

    @Test
    public void registerOverwritesExisting() throws Exception {
        load();
        ctx.call(M + ".register", "id=demo.eat;minutes=15");
        assertEquals("registered=demo.eat", ctx.call(M + ".register", "id=demo.eat;minutes=30"));
        assertEquals("actions=[demo.eat(30)]", ctx.call(M + ".status", null));
    }

    @Test
    public void rejectsInvalidInput() throws Exception {
        load();
        expectRejected(M + ".register", null);
        expectRejected(M + ".register", "");
        expectRejected(M + ".register", "minutes=5");
        expectRejected(M + ".register", "id=demo.eat");
        expectRejected(M + ".register", "id=demo.eat;minutes=abc");
        expectRejected(M + ".register", "id=demo.eat;minutes=-1");
        expectRejected(M + ".register", "id=demo.eat;minutes=1.5");
        expectRejected(M + ".register", "id=;minutes=5");
        expectRejected(M + ".register", "id=demo.eat;minutes=");
        expectRejected(M + ".can", "");
        expectRejected(M + ".execute", "  ");
        expectRejected(M + ".execute", null);
        assertEquals("非法输入不应留下动作", "actions=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void canReportsRegistration() throws Exception {
        load();
        ctx.call(M + ".register", "id=demo.eat;minutes=15");
        assertEquals("can=true", ctx.call(M + ".can", "demo.eat"));
        assertEquals("can=false", ctx.call(M + ".can", "demo.unknown"));
    }

    @Test
    public void executeRunsLogicThenAdvancesTime() throws Exception {
        load();
        ctx.call(M + ".register", "id=demo.eat;minutes=15");
        List<String> trace = new ArrayList<>();
        ctx.stub("demo.eat", input -> {
            trace.add("logic");
            return null;
        });
        ctx.stub(ActionPlugin.TIME_ADVANCE, input -> {
            trace.add("advance:" + input);
            return "now=15;scale=1";
        });

        assertEquals("executed=demo.eat", ctx.call(M + ".execute", "demo.eat"));
        assertEquals("先执行逻辑、后推进时间", List.of("logic", "advance:15"), trace);
    }

    @Test
    public void executeZeroMinutesSkipsTimeAdvance() throws Exception {
        load();
        ctx.call(M + ".register", "id=demo.wait;minutes=0");
        List<String> trace = new ArrayList<>();
        ctx.stub("demo.wait", input -> {
            trace.add("logic");
            return null;
        });
        ctx.stub(ActionPlugin.TIME_ADVANCE, input -> {
            trace.add("advance:" + input);
            return null;
        });

        assertEquals("executed=demo.wait", ctx.call(M + ".execute", "demo.wait"));
        assertEquals("零时长不应推进时间", List.of("logic"), trace);
    }

    @Test
    public void executeUnknownActionIsRejected() throws Exception {
        load();
        try {
            ctx.call(M + ".execute", "demo.unknown");
            fail("未登记的动作不应可执行");
        } catch (LifeloomException e) {
            assertTrue("应说明未登记: " + e.getMessage(), e.getMessage().contains("未登记"));
        } catch (Exception e) {
            fail("应为 LifeloomException，实际: " + e);
        }
    }

    @Test
    public void executeFailureSkipsTimeAdvance() throws Exception {
        load();
        ctx.call(M + ".register", "id=demo.eat;minutes=15");
        List<String> trace = new ArrayList<>();
        ctx.stub("demo.eat", input -> {
            trace.add("logic");
            throw new LifeloomException("动作逻辑失败");
        });
        ctx.stub(ActionPlugin.TIME_ADVANCE, input -> {
            trace.add("advance:" + input);
            return null;
        });

        try {
            ctx.call(M + ".execute", "demo.eat");
            fail("动作逻辑失败应向上传播");
        } catch (LifeloomException expected) {
            // 期望路径
        }
        assertEquals("逻辑失败不应推进时间", List.of("logic"), trace);
    }

    @Test
    public void reloadKeepsActions() throws Exception {
        load();
        ctx.call(M + ".register", "id=demo.eat;minutes=15");
        ctx.call(M + ".register", "id=demo.drink;minutes=0");

        ctx.unregisterAll();
        load();

        assertEquals("替换后登记应保留", "actions=[demo.drink(0), demo.eat(15)]", ctx.call(M + ".status", null));
        ctx.stub("demo.eat", input -> null);
        ctx.stub(ActionPlugin.TIME_ADVANCE, input -> "now=15;scale=1");
        assertEquals("替换后仍可执行", "executed=demo.eat", ctx.call(M + ".execute", "demo.eat"));
    }
}
