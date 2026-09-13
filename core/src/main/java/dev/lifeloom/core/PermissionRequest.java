package dev.lifeloom.core;

/** 一次操作放行请求（由核心转发给闸门，供其决策）。 */
public final class PermissionRequest {

    private final String pluginId;
    private final String operation;
    private final String targetId;
    private final String detail;

    public PermissionRequest(String pluginId, String operation, String targetId, String detail) {
        this.pluginId = pluginId;
        this.operation = operation;
        this.targetId = targetId;
        this.detail = detail;
    }

    /** 发起操作的插件 ID。 */
    public String pluginId() {
        return pluginId;
    }

    /** 操作类别（如 access-mechanism-state、invoke-hook）。 */
    public String operation() {
        return operation;
    }

    /** 操作目标（机制 ID / 钩子 ID 等）。 */
    public String targetId() {
        return targetId;
    }

    /** 面向用户的描述（权限实现可据此提示）。 */
    public String detail() {
        return detail;
    }
}