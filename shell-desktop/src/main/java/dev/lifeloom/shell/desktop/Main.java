package dev.lifeloom.shell.desktop;

import dev.lifeloom.core.Core;
import dev.lifeloom.core.Hook;
import dev.lifeloom.core.JvmPluginLoader;
import dev.lifeloom.core.LoadedPlugin;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.Registry;
import dev.lifeloom.core.SwapManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面外壳（M1 最小版）：命令行演示核心的完整流程。
 *
 * <p>用法：
 * <pre>
 * gradle :shell-desktop:run --args="--plugins &lt;dir&gt; [操作...]"
 * </pre>
 *
 * <p>操作按出现顺序执行：{@code --invoke <hookId>} 调用钩子；
 * {@code --replace <pluginId>=<jarFile>} 热替换（升级）插件。
 * 结束后默认卸载全部插件并退出（演示用；机制状态保留）。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path pluginsDir = null;
        List<Op> ops = new ArrayList<>();

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
                ops.add(Op.invoke(args[++i]));
            } else if ("--replace".equals(arg)) {
                if (i + 1 >= args.length) {
                    usage("--replace 需要一个参数");
                }
                String spec = args[++i];
                int eq = spec.indexOf('=');
                if (eq <= 0 || eq == spec.length() - 1) {
                    usage("--replace 参数格式应为 <pluginId>=<jarFile>");
                }
                ops.add(Op.replace(spec.substring(0, eq), Paths.get(spec.substring(eq + 1))));
            } else {
                usage("未知参数: " + arg);
            }
        }

        System.out.println("=== Lifeloom 核心 M1 演示 ===");
        Core core = new Core(new JvmPluginLoader());

        if (pluginsDir != null) {
            System.out.println("插件目录: " + pluginsDir.toAbsolutePath());
            int loaded = core.loadPluginsFrom(pluginsDir);
            System.out.println();
            System.out.println("已装载插件 " + loaded + " 个:");
            for (LoadedPlugin plugin : core.registry().plugins()) {
                System.out.println("  [" + plugin.descriptor().id() + "] "
                        + plugin.descriptor().name() + " v" + plugin.descriptor().version());
            }
        }

        System.out.println();
        printMechanisms(core.registry());

        for (Op op : ops) {
            System.out.println();
            if (op.kind == Kind.INVOKE) {
                System.out.println("调用钩子: " + op.hookId);
                try {
                    core.invokeHook(op.hookId);
                } catch (Exception e) {
                    System.err.println("调用失败: " + e.getMessage());
                }
            } else {
                System.out.println("热替换: " + op.pluginId + " -> " + op.jarPath);
                try {
                    SwapManager.Result result = core.replacePlugin(op.pluginId, op.jarPath);
                    if (result.isUpgrade()) {
                        System.out.println("替换成功: " + op.pluginId
                                + " v" + result.oldVersion() + " -> v" + result.newVersion());
                    } else {
                        System.out.println("新装成功: " + op.pluginId + " v" + result.newVersion());
                    }
                } catch (Exception e) {
                    System.err.println("替换失败: " + e.getMessage());
                }
            }
        }

        // 收尾：卸载全部（演示用；机制状态保留，体现“数据不断档”）。
        System.out.println();
        List<LoadedPlugin> snapshot = new ArrayList<>(core.registry().plugins());
        for (LoadedPlugin plugin : snapshot) {
            String id = plugin.descriptor().id();
            boolean unloaded = core.unloadPlugin(id);
            System.out.println((unloaded ? "已卸载: " : "卸载失败: ") + id);
        }
        System.out.println();
        System.out.println("收尾：已注册机制 " + core.registry().mechanisms().size() + " 个（机制状态仍保留）");
    }

    private static void printMechanisms(Registry registry) {
        System.out.println("已注册机制 " + registry.mechanisms().size() + " 个:");
        for (Mechanism mechanism : registry.mechanisms()) {
            System.out.println("  " + mechanism.id() + "（" + mechanism.displayName() + "）");
            for (Hook hook : mechanism.hooks()) {
                System.out.println("     钩子: " + hook.id());
            }
        }
    }

    private static void usage(String message) {
        System.err.println(message);
        System.err.println("用法: --plugins <dir> [--invoke <hookId>]... [--replace <pluginId>=<jarFile>]...");
        System.exit(2);
    }

    private enum Kind { INVOKE, REPLACE }

    private static final class Op {

        final Kind kind;
        final String hookId;
        final String pluginId;
        final Path jarPath;

        private Op(Kind kind, String hookId, String pluginId, Path jarPath) {
            this.kind = kind;
            this.hookId = hookId;
            this.pluginId = pluginId;
            this.jarPath = jarPath;
        }

        static Op invoke(String hookId) {
            return new Op(Kind.INVOKE, hookId, null, null);
        }

        static Op replace(String pluginId, Path jarPath) {
            return new Op(Kind.REPLACE, null, pluginId, jarPath);
        }
    }
}