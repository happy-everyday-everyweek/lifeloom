package dev.lifeloom.hooks;

import dev.lifeloom.core.LifeloomException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 钩子管理插件测试（接缝 = 插件钩子契约与钩子解析器语义：
 * disable / enable / replace / restore / status 与解析输出）。
 */
public class HooksPluginTest {

    private static final String M = "dev.lifeloom.hooks";

    private final FakeContext ctx = new FakeContext("dev.lifeloom.hooks.plugin");

    private void load() {
        new HooksPlugin().onLoad(ctx);
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
        assertEquals("disabled=[];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void disableMakesResolutionSkip() throws Exception {
        load();
        assertEquals("disabled=demo.a", ctx.call(M + ".disable", "demo.a"));
        assertNull("被禁用的钩子应解析为跳过", ctx.resolve("demo.a"));
        assertEquals("未登记的钩子解析结果原样", "demo.b", ctx.resolve("demo.b"));
        assertEquals("disabled=[demo.a];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void disableIsIdempotent() throws Exception {
        load();
        ctx.call(M + ".disable", "demo.a");
        assertEquals("disabled=demo.a", ctx.call(M + ".disable", "demo.a"));
        assertEquals("disabled=[demo.a];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void enableRestoresResolution() throws Exception {
        load();
        ctx.call(M + ".disable", "demo.a");
        assertEquals("enabled=demo.a", ctx.call(M + ".enable", "demo.a"));
        assertEquals("解除禁用后原样解析", "demo.a", ctx.resolve("demo.a"));
        assertEquals("disabled=[];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void replaceRedirectsResolution() throws Exception {
        load();
        assertEquals("replaced=demo.a->demo.b", ctx.call(M + ".replace", "demo.a=demo.b"));
        assertEquals("替代后解析到新钩子", "demo.b", ctx.resolve("demo.a"));
        assertEquals("目标钩子自身不受影响", "demo.b", ctx.resolve("demo.b"));
        assertEquals("disabled=[];replaced=[demo.a->demo.b]", ctx.call(M + ".status", null));
    }

    @Test
    public void restoreRemovesRedirect() throws Exception {
        load();
        ctx.call(M + ".replace", "demo.a=demo.b");
        assertEquals("restored=demo.a", ctx.call(M + ".restore", "demo.a"));
        assertEquals("解除替代后原样解析", "demo.a", ctx.resolve("demo.a"));
        assertEquals("disabled=[];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void disableWinsOverReplace() throws Exception {
        load();
        ctx.call(M + ".replace", "demo.a=demo.b");
        ctx.call(M + ".disable", "demo.a");
        assertNull("禁用优先：替代目标不应被执行", ctx.resolve("demo.a"));
        ctx.call(M + ".enable", "demo.a");
        assertEquals("解除禁用后替代继续生效", "demo.b", ctx.resolve("demo.a"));
    }

    @Test
    public void redirectChainComposes() throws Exception {
        load();
        ctx.call(M + ".replace", "demo.a=demo.b");
        ctx.call(M + ".replace", "demo.b=demo.c");
        assertEquals("多段替代应沿链解析到最终目标", "demo.c", ctx.resolve("demo.a"));
    }

    @Test
    public void rejectsInvalidInput() throws Exception {
        load();
        expectRejected(M + ".disable", "");
        expectRejected(M + ".disable", "   ");
        expectRejected(M + ".disable", null);
        expectRejected(M + ".enable", "");
        expectRejected(M + ".replace", "noeq");
        expectRejected(M + ".replace", "x=x");
        expectRejected(M + ".replace", "=y");
        expectRejected(M + ".replace", "x=");
        expectRejected(M + ".replace", null);
        expectRejected(M + ".restore", "");
        assertEquals("非法输入不应留下登记", "disabled=[];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void ownHooksAreProtected() throws Exception {
        load();
        try {
            ctx.call(M + ".disable", M + ".status");
            fail("不应允许禁用自身钩子");
        } catch (LifeloomException e) {
            assertTrue("应说明不能管理自身钩子: " + e.getMessage(), e.getMessage().contains("自身"));
        }
        expectRejected(M + ".replace", M + ".enable=demo.b");
        expectRejected(M + ".replace", "demo.a=" + M + ".status");
        assertEquals("自身钩子不应留下登记", "disabled=[];replaced=[]", ctx.call(M + ".status", null));
    }

    @Test
    public void reloadKeepsRegistrations() throws Exception {
        load();
        ctx.call(M + ".disable", "demo.a");
        ctx.call(M + ".replace", "demo.b=demo.c");

        ctx.unregisterAll();
        load();

        assertNull("替换后禁用登记应保留", ctx.resolve("demo.a"));
        assertEquals("替换后替代登记应保留", "demo.c", ctx.resolve("demo.b"));
        assertEquals("disabled=[demo.a];replaced=[demo.b->demo.c]", ctx.call(M + ".status", null));
    }
}
