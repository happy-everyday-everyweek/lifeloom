package dev.lifeloom.core;

import java.util.List;
import java.util.Objects;

/**
 * 机制：功能与权限的最小单元。
 *
 * <p>机制拥有全局唯一 ID（类包名风格，如 {@code dev.lifeloom.demo.greeting}），内含若干钩子；
 * 机制归属其注册插件（由核心在注册时记录）。
 *
 * <p>M2 起将加入签名、权限声明、跨机制修改校验等（见 ADR-0003）。
 */
public final class Mechanism {

    private final String id;
    private final String displayName;
    private final List<Hook> hooks;

    /**
     * @param id          全局唯一机制 ID（类包名风格）
     * @param displayName 展示名（面向用户/调试）
     * @param hooks       机制内的钩子（至少一个）
     */
    public Mechanism(String id, String displayName, List<Hook> hooks) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks"));
        if (this.hooks.isEmpty()) {
            throw new IllegalArgumentException("机制至少需要一个钩子: " + id);
        }
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public List<Hook> hooks() {
        return hooks;
    }
}