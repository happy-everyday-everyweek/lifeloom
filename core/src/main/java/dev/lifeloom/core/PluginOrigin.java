package dev.lifeloom.core;

/** 插件来源：辨识系统插件（平台自带）与三方插件（用户安装）。 */
public enum PluginOrigin {
    /** 系统插件：随平台分发，不受权限闸门限制。 */
    SYSTEM,
    /** 三方插件：其操作需经权限闸门决策。 */
    THIRD_PARTY
}