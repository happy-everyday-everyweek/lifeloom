package dev.lifeloom.core;

import java.nio.file.Path;

/**
 * 热替换管理器：整体替换（升级）已装插件，数据不断档（见 ADR-0002）。
 *
 * <p>drain（停止接受新的执行请求、等待执行完成）由 {@link Core} 的执行闸门负责；
 * 本类负责替换本身的编排：先预检新包（不动旧插件），校验包 ID，卸载旧插件
 * （机制状态保留），再装载并激活新插件；失败时尽力恢复旧插件。
 */
public final class SwapManager {

    private final Core core;

    SwapManager(Core core) {
        this.core = core;
    }

    /** 替换结果摘要。 */
    public static final class Result {

        private final String pluginId;
        private final String oldVersion;
        private final String newVersion;

        Result(String pluginId, String oldVersion, String newVersion) {
            this.pluginId = pluginId;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
        }

        public String pluginId() {
            return pluginId;
        }

        /** 替换前版本；本次为“新装”时为 null。 */
        public String oldVersion() {
            return oldVersion;
        }

        public String newVersion() {
            return newVersion;
        }

        /** 是否为升级（替换了已有的同名插件）。 */
        public boolean isUpgrade() {
            return oldVersion != null;
        }
    }

    Result swap(String pluginId, Path newPackage) throws Exception {
        // 1. 预检：先装载新包（失败不影响旧插件）。
        LoadedPlugin fresh = core.loader().load(newPackage);
        if (!fresh.descriptor().id().equals(pluginId)) {
            core.safeUnload(fresh);
            throw new LifeloomException("替换包 ID 不符：期望 " + pluginId
                    + "，实际 " + fresh.descriptor().id());
        }

        LoadedPlugin old = core.registry().findPlugin(pluginId);
        String oldVersion = old == null ? null : old.descriptor().version();
        Path oldPath = old == null ? null : old.sourcePath();

        // 2. 卸载旧插件（机制状态保留，保证数据不断档）。
        if (old != null) {
            core.detachPlugin(pluginId);
        }

        // 3. 激活新插件；失败时尽力回滚到旧版本。
        try {
            core.attachPlugin(fresh);
        } catch (Exception e) {
            if (oldPath != null) {
                try {
                    core.loadPlugin(oldPath);
                } catch (Exception rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw new LifeloomException("替换失败（已尝试恢复旧版本）: " + pluginId, e);
            }
            throw new LifeloomException("替换失败: " + pluginId, e);
        }

        return new Result(pluginId, oldVersion, fresh.descriptor().version());
    }
}