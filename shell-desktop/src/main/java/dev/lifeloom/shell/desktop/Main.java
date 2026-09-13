package dev.lifeloom.shell.desktop;

import dev.lifeloom.core.Core;
import dev.lifeloom.core.Hook;
import dev.lifeloom.core.JvmPluginLoader;
import dev.lifeloom.core.LoadedPlugin;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.Registry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面外壳（M0 最小版）：命令行演示“装载 → 注册 → 调用 → 卸载”全流程。
 *
 * <p>用法：
 * <pre>
 * gradle :shell-desktop:run --args="--plugins &lt;dir&gt; [--invoke &lt;hookId&gt;]..."
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path pluginsDir = Paths.get("plugins");
        List<String> invokeHooks = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--plugins".equals(arg)) {
                if (i + 1 >= args.length) {
                    usage("--plugins 需要一个参数");
                }
                pluginsDir = Paths.get(args[++i]);
            } else if ("--invoke".equals(arg)) {
                if (i + 1 >= args.length) {
                    usage("--invoke 需要一个参数");
                }
                invokeHooks.add(args[++i]);
            } else {
                usage("未知参数: " + arg);
            }
        }

        System.out.println("=== Lifeloom 核心 M0 演示 ===");
        System.out.println("插件目录: " + pluginsDir.toAbsolutePath());

        Core core = new Core(new JvmPluginLoader());
        int loaded = core.loadPluginsFrom(pluginsDir);
        Registry registry = core.registry();

        System.out.println();
        System.out.println("已装载插件 " + loaded + " 个:");
        for (LoadedPlugin plugin : registry.plugins()) {
            System.out.println("  [" + plugin.descriptor().id() + "] "
                    + plugin.descriptor().name() + " v" + plugin.descriptor().version());
        }

        System.out.println();
        System.out.println("已注册机制 " + registry.mechanisms().size() + " 个:");
        for (Mechanism mechanism : registry.mechanisms()) {
            System.out.println("  " + mechanism.id() + "（" + mechanism.displayName() + "）");
            for (Hook hook : mechanism.hooks()) {
                System.out.println("     钩子: " + hook.id());
            }
        }

        if (!invokeHooks.isEmpty()) {
            System.out.println();
            for (String hookId : invokeHooks) {
                System.out.println("调用钩子: " + hookId);
                try {
                    core.invokeHook(hookId);
                } catch (Exception e) {
                    System.err.println("调用失败: " + e.getMessage());
                }
            }
        }

        // 演示卸载（M1 热替换的基础）。
        System.out.println();
        List<LoadedPlugin> snapshot = new ArrayList<>(registry.plugins());
        for (LoadedPlugin plugin : snapshot) {
            String id = plugin.descriptor().id();
            boolean unloaded = core.unloadPlugin(id);
            System.out.println((unloaded ? "已卸载: " : "卸载失败: ") + id);
        }
        System.out.println();
        System.out.println("剩余已注册机制: " + registry.mechanisms().size() + " 个");
    }

    private static void usage(String message) {
        System.err.println(message);
        System.err.println("用法: --plugins <dir> [--invoke <hookId>]...");
        System.exit(2);
    }
}