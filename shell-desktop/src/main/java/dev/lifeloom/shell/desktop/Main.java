package dev.lifeloom.shell.desktop;

import dev.lifeloom.core.Core;
import dev.lifeloom.core.Hook;
import dev.lifeloom.core.JvmPluginLoader;
import dev.lifeloom.core.LoadedPlugin;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.PluginOrigin;
import dev.lifeloom.core.Registry;
import dev.lifeloom.core.SwapManager;
import dev.lifeloom.core.UserPrompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 桌面外壳（命令行演示）：核心装载 / 调用 / 热替换、M2 权限闸门与 M3 调度存储原语的演示入口。
 *
 * <p>用法：
 * <pre>
 * shell-desktop --plugins &lt;dir&gt; [操作...]
 * </pre>
 *
 * <p>操作按出现顺序执行：{@code --invoke <hookId>[=<输入>]} 调用钩子（打印输出）；
 * {@code --emit <事件ID>[=<载荷>]} 发射事件；{@code --state <机制ID>} 打印机制状态；
 * {@code --watch <机制ID>} 监听机制状态变更（本次运行内持续）；
 * {@code --replace <pluginId>=<jarFile>} 热替换（升级）插件。
 * 结束后默认卸载全部插件并退出（演示用；机制状态保留）。
 *
 * <p>已注入控制台提示通道：权限插件询问时从标准输入读取 y/n（EOF 视为拒绝）。
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
            } else if ("--emit".equals(arg)) {
                if (i + 1 >= args.length) {
                    usage("--emit 需要一个参数");
                }
                ops.add(Op.emit(args[++i]));
            } else if ("--state".equals(arg)) {
                if (i + 1 >= args.length) {
                    usage("--state 需要一个参数");
                }
                ops.add(Op.state(args[++i]));
            } else if ("--watch".equals(arg)) {
                if (i + 1 >= args.length) {
                    usage("--watch 需要一个参数");
                }
                ops.add(Op.watch(args[++i]));
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

        System.out.println("=== Lifeloom 桌面外壳（M3：逻辑线程、调度与存储原语） ===");
        Core core = new Core(new JvmPluginLoader());
        core.setUserPrompt(new ConsolePrompt());
        System.out.println("逻辑线程: " + core.logicThreadName());

        if (pluginsDir != null) {
            System.out.println("插件目录: " + pluginsDir.toAbsolutePath());
            int loaded = core.loadPluginsFrom(pluginsDir);
            System.out.println();
            System.out.println("已装载插件 " + loaded + " 个:");
            for (LoadedPlugin plugin : core.registry().plugins()) {
                System.out.println("  [" + originLabel(plugin.origin()) + "] "
                        + plugin.descriptor().id() + " v" + plugin.descriptor().version());
            }
            System.out.println("权限闸门: " + (core.permissions().hasGatekeeper()
                    ? "已注册（放行决策由闸门承担）"
                    : "未注册（三方插件的跨边界操作将被拒绝）"));
        }

        System.out.println();
        printMechanisms(core.registry());

        List<AutoCloseable> watchers = new ArrayList<>();
        try {
            for (Op op : ops) {
                System.out.println();
                switch (op.kind) {
                    case INVOKE:
                        System.out.println("调用钩子: " + op.target
                                + (op.payload != null ? "（输入: " + op.payload + "）" : ""));
                        try {
                            String output = core.invokeHook(op.target, op.payload);
                            if (output != null) {
                                System.out.println("钩子输出: " + output);
                            }
                        } catch (Exception e) {
                            System.err.println("调用失败: " + e.getMessage());
                        }
                        break;
                    case EMIT:
                        System.out.println("发射事件: " + op.target
                                + (op.payload != null ? "（载荷: " + op.payload + "）" : ""));
                        try {
                            core.emitEvent(null, op.target, op.payload);
                        } catch (Exception e) {
                            System.err.println("发射失败: " + e.getMessage());
                        }
                        break;
                    case STATE:
                        printState(core, op.target);
                        break;
                    case WATCH:
                        System.out.println("开始监听状态变更: " + op.target);
                        watchers.add(core.watchState(op.target, (mechanismId, key, oldValue, newValue) ->
                                System.out.println("[watch] " + mechanismId + "." + key
                                        + ": " + oldValue + " -> " + newValue)));
                        break;
                    case REPLACE:
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
                        break;
                    default:
                        break;
                }
            }
        } finally {
            closeQuietly(watchers);
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
        core.close();
        System.out.println("逻辑线程已停止。");
    }

    private static String originLabel(PluginOrigin origin) {
        return origin == PluginOrigin.SYSTEM ? "系统" : "三方";
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

    private static void printState(Core core, String mechanismId) {
        MechanismState state = core.stateOf(mechanismId);
        Set<String> keys = state.keys();
        System.out.println("机制状态 " + mechanismId + "（" + keys.size() + " 项）:");
        for (String key : keys) {
            System.out.println("  " + key + " = " + state.get(key));
        }
    }

    private static void closeQuietly(List<AutoCloseable> closables) {
        for (AutoCloseable closable : closables) {
            try {
                closable.close();
            } catch (Exception e) {
                System.err.println("监听取消失败: " + e.getMessage());
            }
        }
    }

    private static void usage(String message) {
        System.err.println(message);
        System.err.println("用法: --plugins <dir> [--invoke <hookId>[=<输入>]]... [--emit <事件ID>[=<载荷>]]...");
        System.err.println("      [--replace <pluginId>=<jarFile>]... [--state <机制ID>]... [--watch <机制ID>]...");
        System.exit(2);
    }

    private enum Kind { INVOKE, EMIT, STATE, WATCH, REPLACE }

    private static final class Op {

        final Kind kind;
        final String target;
        final String payload;
        final String pluginId;
        final Path jarPath;

        private Op(Kind kind, String target, String payload, String pluginId, Path jarPath) {
            this.kind = kind;
            this.target = target;
            this.payload = payload;
            this.pluginId = pluginId;
            this.jarPath = jarPath;
        }

        static Op invoke(String spec) {
            Spec parsed = Spec.parse(spec);
            return new Op(Kind.INVOKE, parsed.name, parsed.value, null, null);
        }

        static Op emit(String spec) {
            Spec parsed = Spec.parse(spec);
            return new Op(Kind.EMIT, parsed.name, parsed.value, null, null);
        }

        static Op state(String mechanismId) {
            return new Op(Kind.STATE, mechanismId, null, null, null);
        }

        static Op watch(String mechanismId) {
            return new Op(Kind.WATCH, mechanismId, null, null, null);
        }

        static Op replace(String pluginId, Path jarPath) {
            return new Op(Kind.REPLACE, null, null, pluginId, jarPath);
        }
    }

    /** 解析 {@code name[=value]} 形式的参数。 */
    private static final class Spec {

        final String name;
        final String value;

        private Spec(String name, String value) {
            this.name = name;
            this.value = value;
        }

        static Spec parse(String raw) {
            int eq = raw.indexOf('=');
            if (eq < 0) {
                return new Spec(raw, null);
            }
            return new Spec(raw.substring(0, eq), raw.substring(eq + 1));
        }
    }

    /** 控制台提示通道：提示输出到标准输出；询问从标准输入读取（y/yes 为同意，EOF 或读取失败视为拒绝）。 */
    private static final class ConsolePrompt implements UserPrompt {

        private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        @Override
        public void inform(String message) {
            System.out.println("[提示] " + message);
        }

        @Override
        public boolean confirm(String message) {
            System.out.print("[询问] " + message + " [y/N] ");
            System.out.flush();
            try {
                String line = reader.readLine();
                if (line == null) {
                    System.out.println("（无输入，按拒绝处理）");
                    return false;
                }
                String answer = line.trim().toLowerCase();
                return answer.equals("y") || answer.equals("yes");
            } catch (IOException e) {
                System.out.println("（读取失败，按拒绝处理）");
                return false;
            }
        }
    }
}
