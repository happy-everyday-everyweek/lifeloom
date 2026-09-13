package dev.lifeloom.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 插件清单：描述一个插件包的基本信息。
 *
 * <p>M0 清单格式（暂定）：插件包内 {@code META-INF/lifeloom-plugin.properties}：
 *
 * <pre>
 * id=dev.lifeloom.demo
 * name=Demo Plugin
 * version=0.1.0
 * mainClass=dev.lifeloom.demo.DemoPlugin
 * </pre>
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

    public PluginDescriptor(String id, String name, String version, String mainClass) {
        this.id = requireNonBlank(id, "id");
        this.name = requireNonBlank(name, "name");
        this.version = requireNonBlank(version, "version");
        this.mainClass = requireNonBlank(mainClass, "mainClass");
    }

    /** 从清单输入流读取。 */
    public static PluginDescriptor read(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return new PluginDescriptor(
                props.getProperty("id"),
                props.getProperty("name"),
                props.getProperty("version"),
                props.getProperty("mainClass"));
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("插件清单缺少字段: " + field);
        }
        return value.trim();
    }
}