package dev.lifeloom.core;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Hook.of：从函数快速构造钩子（返回值作为 reply；异常向上传播）。
 */
public class HookFactoryTest {

    @Test
    public void factoryRepliesWithBodyResult() throws Exception {
        Hook hook = Hook.of("t.m.h", context -> "out:" + context.input());
        assertEquals("t.m.h", hook.id());

        HookContext context = new HookContext("t.m.h", "t.m", new MapState(), "x");
        hook.invoke(context);
        assertEquals("out:x", context.output());
    }

    @Test
    public void factoryNullResultMeansNoOutput() throws Exception {
        Hook hook = Hook.of("t.m.h", context -> null);
        HookContext context = new HookContext("t.m.h", "t.m", new MapState(), null);
        hook.invoke(context);
        assertNull(context.output());
    }

    @Test
    public void factoryPropagatesBodyException() throws Exception {
        Hook hook = Hook.of("t.m.h", context -> {
            throw new LifeloomException("动作逻辑失败");
        });
        try {
            hook.invoke(new HookContext("t.m.h", "t.m", new MapState(), null));
            fail("异常应向上传播");
        } catch (LifeloomException expected) {
            assertEquals("动作逻辑失败", expected.getMessage());
        }
    }

    /** 内存键值状态（测试夹具）。 */
    private static final class MapState implements MechanismState {

        private final Map<String, String> map = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }

        @Override
        public String remove(String key) {
            return map.remove(key);
        }

        @Override
        public Set<String> keys() {
            return new LinkedHashSet<>(map.keySet());
        }
    }
}
