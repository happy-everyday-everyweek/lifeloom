package dev.lifeloom.core;

import java.util.Objects;

/**
 * 钩子解析器（核心原语）：在钩子调用前解析目标的执行方式——直接执行 / 跳过 / 重定向。
 *
 * <p>由系统插件注册（如钩子管理插件，承担“禁用原钩子、替代登记”）；核心在每次
 * 钩子调用前询问解析器，并沿重定向链查找最终目标（限制链长、检测成环）。
 * 无解析器时一切照旧（直接执行）。解析随其注册插件的卸载 / 替换自动移除。
 */
public interface HookResolver {

    /** 解析一次钩子调用。 */
    Resolution resolve(String hookId);

    /** 解析结果：执行原样 / 跳过 / 重定向到另一钩子。 */
    final class Resolution {

        private static final Resolution EXECUTE = new Resolution(false, null);
        private static final Resolution SKIP = new Resolution(true, null);

        private final boolean skip;
        private final String redirectTarget;

        private Resolution(boolean skip, String redirectTarget) {
            this.skip = skip;
            this.redirectTarget = redirectTarget;
        }

        /** 直接执行（原样）。 */
        public static Resolution execute() {
            return EXECUTE;
        }

        /** 跳过（不执行）。 */
        public static Resolution skip() {
            return SKIP;
        }

        /** 重定向到另一钩子。 */
        public static Resolution redirect(String targetHookId) {
            return new Resolution(false, Objects.requireNonNull(targetHookId, "targetHookId"));
        }

        /** 是否应跳过。 */
        public boolean isSkip() {
            return skip;
        }

        /** 重定向目标；无重定向时为 null。 */
        public String redirectTarget() {
            return redirectTarget;
        }
    }
}
