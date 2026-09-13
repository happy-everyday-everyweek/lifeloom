package dev.lifeloom.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * 插件清单：描述一个插件包的基本信息。
 *
 * <p>当前清单格式（暂定）：插件包内 {@code META-INF/lifeloom-plugin.properties}：
 *
 * <pre>
 * id=dev.lifeloom.demo
 * name=Demo Plugin
 * version=0.1.0
 * mainClass=dev.lifeloom.demo.DemoPlugin
 * origin=third-party
 * </pre>
 *
 * <p>{@code origin} 取 {@code system} 或 {@code third-party}（缺省 third-party，fail-safe）。
 * 当前为清单声明占位：正式的系统 / 三方辨识与完整性校验随签名机制接入
 * （见 ADR-0003、ADR-0005）。
 *
 * <p>打包、签名与完整清单格式待“插件细谈”后定（见 docs/核心设计.md 未决点）。
 */
public final class PluginDescriptor {

    /** 清单在插件包内的固定路径。 */
    public static final String MANIFEST_PATH = "META-INF/lifeloom-plugin.properties";

    private final String id;
    private final String name;
    private final String version;
    private final String mainClass;
    private final PluginOrigin origin;

    /** 缺省来源为三方（fail-safe：未声明一律按三方处理）。 */
    public PluginDescriptor(String id, String name, String version, String mainClass) {
        this(id, name, version, mainClass, PluginOrigin.THIRD_PARTY);
    }

    public PluginDescriptor(String id, String name, String version, String mainClass, PluginOrigin origin) {
        this.id = requireNonBlank(id, "id");
        this.name = requireNonBlank(name, "name");
        this.version = requireNonBlank(version, "version");
        this.mainClass = requireNonBlank(mainClass, "mainClass");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /** 从清单输入流读取。 */
    public static PluginDescriptor read(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        PluginOrigin origin = "system".equalsIgnoreCase(props.getProperty("origin", "").trim())
                ? PluginOrigin.SYSTEM
                : PluginOrigin.THIRD_PARTY;
        return new PluginDescriptor(
                props.getProperty("id"),
                props.getProperty("name"),
                props.getProperty("version"),
                props.getProperty("mainClass"),
                origin);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String mainClass() {
        return mainClass;
    }

    /** 插件来源（声明值；正式辨识待签名机制接入）。 */
    public PluginOrigin origin() {
        return origin;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("插件清单缺少字段: " + field);
        }
        return value.trim();
    }
}
