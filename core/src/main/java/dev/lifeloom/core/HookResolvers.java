package dev.lifeloom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钩子解析器登记簿（核心原语，极薄）：解析由已注册的解析器承担。
 *
 * <p>仅系统插件可注册；解析随其注册插件的卸载（或替换）移除。
 * 解析链：对当前钩子依次询问全部解析器（注册顺序）——重定向则沿链继续、
 * 跳过则终止；限制链长并检测成环。
 */
public final class HookResolvers {

    /** 替代链的最大解析步数（防止过长或成环）。 */
    private static final int MAX_CHAIN = 8;

    private final Map<LoadedPlugin, List<HookResolver>> resolvers = new LinkedHashMap<>();

    /** 注册解析器；仅系统插件可注册。 */
    synchronized void register(LoadedPlugin registrant, HookResolver resolver) {
        if (registrant.origin() != PluginOrigin.SYSTEM) {
            throw new LifeloomException("仅系统插件可注册钩子解析器: " + registrant.descriptor().id());
        }
        resolvers.computeIfAbsent(registrant, key -> new ArrayList<>()).add(resolver);
    }

    /** 移除某插件注册的全部解析器（卸载 / 替换时调用）。 */
    synchronized void removeResolversOf(LoadedPlugin plugin) {
        resolvers.remove(plugin);
    }

    /** 是否已有解析器注册（诊断用）。 */
    public synchronized boolean hasResolver() {
        return !resolvers.isEmpty();
    }

    /**
     * 解析钩子调用：跟随替代链。
     *
     * @return 最终要执行的钩子 ID；被判定为跳过时为 null
     */
    String resolveChain(String hookId) {
        List<HookResolver> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>();
            for (List<HookResolver> list : resolvers.values()) {
                snapshot.addAll(list);
            }
        }
        if (snapshot.isEmpty()) {
            return hookId;
        }
        String current = hookId;
        Set<String> seen = new LinkedHashSet<>();
        for (int step = 0; step <= MAX_CHAIN; step++) {
            if (!seen.add(current)) {
                throw new LifeloomException("钩子替代链成环: " + hookId);
            }
            String redirected = null;
            for (HookResolver resolver : snapshot) {
                HookResolver.Resolution resolution = resolver.resolve(current);
                if (resolution == null) {
                    continue;
                }
                if (resolution.isSkip()) {
                    return null;
                }
                if (resolution.redirectTarget() != null) {
                    redirected = resolution.redirectTarget();
                    break;
                }
            }
            if (redirected == null) {
                return current;
            }
            current = redirected;
        }
        throw new LifeloomException("钩子替代链过深: " + hookId);
    }
}
