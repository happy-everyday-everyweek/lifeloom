package dev.lifeloom.core;

/**
 * 闸门：操作放行决策者。
 *
 * <p>非系统插件经核心原语发起的操作，会转发给已注册闸门逐一决策，
 * 任一闸门拒绝即拒绝。权限插件即一个闸门实现（系统插件）；
 * 决策逻辑（放行、提示用户等）全部由闸门侧承担，核心只负责转发。
 */
public interface Gatekeeper {

    /**
     * 对一次操作请求作出放行决定。
     *
     * @return 是否放行
     */
    boolean decide(PermissionRequest request);
}