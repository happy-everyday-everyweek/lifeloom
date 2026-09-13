package dev.lifeloom.time;

import dev.lifeloom.core.LifeloomException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 时间插件测试（接缝 = 插件钩子契约：now / advance / set-scale 与 advanced 事件）。
 */
public class TimePluginTest {

    private static final String M = "dev.lifeloom.time";

    private final FakeContext ctx = new FakeContext("dev.lifeloom.time.plugin");

    private void load() {
        new TimePlugin().onLoad(ctx);
    }

    @Test
    public void nowStartsAtZeroAndScaleOne() throws Exception {
        load();
        assertEquals("now=0;scale=1", ctx.call(M + ".now", null));
    }

    @Test
    public void advanceIncreasesClockAndEmitsEvent() throws Exception {
        load();
        assertEquals("now=30;scale=1", ctx.call(M + ".advance", "30"));
        assertEquals("30", ctx.stateFor(M).get("now"));
        assertEquals(List.of(M + ".advanced|30"), ctx.emits);
        assertEquals("now=30;scale=1", ctx.call(M + ".now", null));
    }

    @Test
    public void advanceAccumulates() throws Exception {
        load();
        ctx.call(M + ".advance", "30");
        assertEquals("now=42;scale=1", ctx.call(M + ".advance", "12"));
    }

    @Test
    public void advanceZeroIsNoOpWithoutEvent() throws Exception {
        load();
        assertEquals("now=0;scale=1", ctx.call(M + ".advance", "0"));
        assertTrue("推进量为 0 不应发射事件", ctx.emits.isEmpty());
    }

    @Test
    public void advanceRejectsInvalidInput() throws Exception {
        load();
        String[] invalid = {"-5", "abc", "", null};
        for (String bad : invalid) {
            try {
                ctx.call(M + ".advance", bad);
                fail("应拒绝推进量: " + bad);
            } catch (LifeloomException expected) {
                // 期望路径
            }
        }
        assertEquals("now=0;scale=1", ctx.call(M + ".now", null));
        assertTrue(ctx.emits.isEmpty());
    }

    @Test
    public void setScaleValidatesAndStores() throws Exception {
        load();
        assertEquals("scale=2", ctx.call(M + ".set-scale", "2"));
        assertEquals("2", ctx.stateFor(M).get("scale"));
        assertEquals("now=0;scale=2", ctx.call(M + ".now", null));
        for (String bad : new String[]{"0", "-1", "x", ""}) {
            try {
                ctx.call(M + ".set-scale", bad);
                fail("应拒绝倍率: " + bad);
            } catch (LifeloomException expected) {
                // 期望路径
            }
        }
        assertEquals("scale=2", ctx.call(M + ".set-scale", "2"));
    }

    @Test
    public void reloadKeepsClockAcrossInstances() throws Exception {
        load();
        ctx.call(M + ".advance", "42");

        ctx.unregisterAll();
        new TimePlugin().onLoad(ctx);

        assertEquals("now=42;scale=1", ctx.call(M + ".now", null));
    }
}
