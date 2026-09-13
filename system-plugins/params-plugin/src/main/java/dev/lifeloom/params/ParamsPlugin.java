package dev.lifeloom.params;

import dev.lifeloom.core.Hook;
import dev.lifeloom.core.HookContext;
import dev.lifeloom.core.LifeloomException;
import dev.lifeloom.core.Mechanism;
import dev.lifeloom.core.MechanismState;
import dev.lifeloom.core.Plugin;
import dev.lifeloom.core.PluginContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 参数插件（系统插件，世界组）：参数系统。
 *
 * <p>职责（v1）：参数注册（基础 / 派生）、数值读写、派生计算与变更通知；
 * 参数可附着于实例（{@code instance:<ID>}）与机制（{@code mechanism:<ID>}）。
 * 派生表达式支持四则运算、括号、一元符号与同属主参数引用；引用只能指向已注册参数，
 * 因此注册序即拓扑序、天然无环。判定规则的惯用口径：派生计算归参数、判定入口归动作。
 *
 * <p>契约（v1）：
 * <ul>
 *   <li>钩子 {@code dev.lifeloom.params.register}：输入
 *       {@code owner=<属主>;name=<名>;type=<base|derived>[;value=<初值>][;expression=<表达式>]}；
 *       输出 {@code registered=<owner>|<名>}</li>
 *   <li>钩子 {@code dev.lifeloom.params.set}：输入 {@code owner=...;name=<名>;value=<数值>}
 *       （仅基础参数）；重算派生并逐个发射变更事件；输出 {@code set=<名>=<值>}</li>
 *   <li>钩子 {@code dev.lifeloom.params.get} / {@code has}：输入 {@code owner=...;name=<名>}；
 *       get 输出 {@code value=<数值>}，has 输出 {@code has=<true|false>}</li>
 *   <li>钩子 {@code dev.lifeloom.params.list}：输入 {@code owner=...}；输出 {@code params=[...]}</li>
 *   <li>钩子 {@code dev.lifeloom.params.remove}：删除参数（被派生引用时拒绝）</li>
 *   <li>钩子 {@code dev.lifeloom.params.clear}：清理某属主全部参数；输出 {@code cleared=<owner>;count=<n>}</li>
 *   <li>事件 {@code dev.lifeloom.params.changed}：载荷 {@code owner=...;name=<名>;value=<新值>}
 *       （仅实际变化时逐参发射）</li>
 *   <li>状态：{@code def|<owner>|<名>} / {@code expr|<owner>|<名>} / {@code val|<owner>|<名>}</li>
 * </ul>
 */
public final class ParamsPlugin implements Plugin {

    /** 机制 ID。 */
    public static final String MECHANISM_ID = "dev.lifeloom.params";
    /** 注册参数。 */
    public static final String HOOK_REGISTER = MECHANISM_ID + ".register";
    /** 设置基础参数值。 */
    public static final String HOOK_SET = MECHANISM_ID + ".set";
    /** 读取参数值。 */
    public static final String HOOK_GET = MECHANISM_ID + ".get";
    /** 参数存在性。 */
    public static final String HOOK_HAS = MECHANISM_ID + ".has";
    /** 列出某属主参数。 */
    public static final String HOOK_LIST = MECHANISM_ID + ".list";
    /** 删除参数。 */
    public static final String HOOK_REMOVE = MECHANISM_ID + ".remove";
    /** 清理某属主全部参数。 */
    public static final String HOOK_CLEAR = MECHANISM_ID + ".clear";
    /** 参数变更事件。 */
    public static final String EVENT_CHANGED = MECHANISM_ID + ".changed";

    private static final String PREFIX_DEF = "def|";
    private static final String PREFIX_EXPR = "expr|";
    private static final String PREFIX_VAL = "val|";

    private PluginContext context;
    private MechanismState state;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        state = context.stateFor(MECHANISM_ID);
        context.registerMechanism(new Mechanism(MECHANISM_ID, "参数", List.of(
                Hook.of(HOOK_REGISTER, this::register),
                Hook.of(HOOK_SET, this::set),
                Hook.of(HOOK_GET, this::get),
                Hook.of(HOOK_HAS, this::has),
                Hook.of(HOOK_LIST, this::list),
                Hook.of(HOOK_REMOVE, this::remove),
                Hook.of(HOOK_CLEAR, this::clear))));
        System.out.println("[params] 参数插件已装载（基础 / 派生、变更通知就绪）");
    }

    @Override
    public void onUnload() {
        System.out.println("[params] 参数插件卸载中（参数数据保留在机制状态）");
    }

    /** register：注册参数（基础 / 派生）。 */
    private String register(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        String name = requireName(fields.get("name"));
        String type = require(fields.get("type"), "类型");
        if (!type.equals("base") && !type.equals("derived")) {
            throw new LifeloomException("参数类型无效（须为 base 或 derived）: " + type);
        }
        if (exists(owner, name)) {
            throw new LifeloomException("参数已存在: " + owner + "|" + name);
        }
        if (type.equals("base")) {
            String raw = fields.get("value");
            double value = raw == null || raw.isBlank() ? 0 : parseValue(raw);
            state.put(defKey(owner, name), "base");
            state.put(valKey(owner, name), format(value));
        } else {
            String expression = require(fields.get("expression"), "表达式");
            Set<String> refs = Expr.refs(expression);
            for (String ref : refs) {
                if (!exists(owner, ref)) {
                    throw new LifeloomException("表达式引用未注册参数: " + ref);
                }
            }
            Map<String, String> values = snapshot(owner);
            double value = Expr.evaluate(expression, ref -> lookupIn(values, ref));
            state.put(defKey(owner, name), "derived");
            state.put(exprKey(owner, name), expression);
            state.put(valKey(owner, name), format(value));
        }
        return "registered=" + owner + "|" + name;
    }

    /** set：设置基础参数并级联重算派生（失败时原子回退：状态不动）。 */
    private String set(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        String name = requireName(fields.get("name"));
        double value = parseValue(require(fields.get("value"), "数值"));
        requireRegistered(owner, name);
        if (!"base".equals(state.get(defKey(owner, name)))) {
            throw new LifeloomException("派生参数只读（由表达式计算）: " + owner + "|" + name);
        }
        Map<String, String> before = snapshot(owner);
        Map<String, String> after = new LinkedHashMap<>(before);
        after.put(name, format(value));
        stabilize(owner, after);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        if (changed.isEmpty()) {
            return "set=" + name + "=" + after.get(name);
        }
        changed.sort(null);
        for (String changedName : changed) {
            state.put(valKey(owner, changedName), after.get(changedName));
        }
        for (String changedName : changed) {
            context.emit(EVENT_CHANGED, "owner=" + owner
                    + ";name=" + changedName + ";value=" + after.get(changedName));
        }
        return "set=" + name + "=" + after.get(name);
    }

    /** get：读取参数值。 */
    private String get(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        String name = requireName(fields.get("name"));
        requireRegistered(owner, name);
        return "value=" + state.get(valKey(owner, name));
    }

    /** has：参数存在性。 */
    private String has(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        String name = requireName(fields.get("name"));
        return "has=" + exists(owner, name);
    }

    /** list：列出某属主参数名（排序）。 */
    private String list(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        List<String> names = allNames(owner);
        names.sort(null);
        return "params=[" + String.join(",", names) + "]";
    }

    /** remove：删除参数；被派生表达式引用时拒绝。 */
    private String remove(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        String name = requireName(fields.get("name"));
        requireRegistered(owner, name);
        for (String derived : derivedNames(owner)) {
            Set<String> refs = Expr.refs(state.get(exprKey(owner, derived)));
            if (refs.contains(name)) {
                throw new LifeloomException("参数被派生引用，不能删除: "
                        + owner + "|" + name + " <- " + derived);
            }
        }
        state.remove(defKey(owner, name));
        state.remove(exprKey(owner, name));
        state.remove(valKey(owner, name));
        return "removed=" + owner + "|" + name;
    }

    /** clear：清理某属主全部参数。 */
    private String clear(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String owner = requireOwner(fields.get("owner"));
        List<String> toRemove = new ArrayList<>();
        int count = 0;
        for (String key : state.keys()) {
            if (isOwnedKey(key, PREFIX_DEF, owner)) {
                count++;
                toRemove.add(key);
            } else if (isOwnedKey(key, PREFIX_EXPR, owner) || isOwnedKey(key, PREFIX_VAL, owner)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            state.remove(key);
        }
        return "cleared=" + owner + ";count=" + count;
    }

    /**
     * 迭代重算派生参数到稳定（链式依赖多轮收敛）。
     *
     * <p>求值只在工作副本 {@code values} 上进行；任一失败抛出时调用方尚未写回状态——
     * 保证 set 的失败原子性。
     */
    private void stabilize(String owner, Map<String, String> values) {
        List<String> derived = derivedNames(owner);
        int guard = derived.size() + 2;
        boolean changed = true;
        while (changed) {
            if (--guard < 0) {
                throw new LifeloomException("参数重算未收敛: " + owner);
            }
            changed = false;
            for (String name : derived) {
                String expression = state.get(exprKey(owner, name));
                double value = Expr.evaluate(expression, ref -> lookupIn(values, ref));
                String formatted = format(value);
                if (!formatted.equals(values.get(name))) {
                    values.put(name, formatted);
                    changed = true;
                }
            }
        }
    }

    private boolean exists(String owner, String name) {
        return state.get(defKey(owner, name)) != null;
    }

    private void requireRegistered(String owner, String name) {
        if (!exists(owner, name)) {
            throw new LifeloomException("参数未注册: " + owner + "|" + name);
        }
    }

    private List<String> allNames(String owner) {
        List<String> names = new ArrayList<>();
        String head = PREFIX_DEF + owner + "|";
        for (String key : state.keys()) {
            if (key.startsWith(head)) {
                names.add(key.substring(head.length()));
            }
        }
        return names;
    }

    private List<String> derivedNames(String owner) {
        List<String> names = new ArrayList<>();
        for (String name : allNames(owner)) {
            if ("derived".equals(state.get(defKey(owner, name)))) {
                names.add(name);
            }
        }
        names.sort(null);
        return names;
    }

    /** 某属主全部参数的当前值快照（名字 → 格式化值）。 */
    private Map<String, String> snapshot(String owner) {
        Map<String, String> values = new LinkedHashMap<>();
        String head = PREFIX_VAL + owner + "|";
        for (String key : state.keys()) {
            if (key.startsWith(head)) {
                values.put(key.substring(head.length()), state.get(key));
            }
        }
        return values;
    }

    private static double lookupIn(Map<String, String> values, String name) {
        String raw = values.get(name);
        if (raw == null) {
            throw new LifeloomException("参数无值: " + name);
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new LifeloomException("参数值非法: " + name);
        }
    }

    private static String defKey(String owner, String name) {
        return PREFIX_DEF + owner + "|" + name;
    }

    private static String exprKey(String owner, String name) {
        return PREFIX_EXPR + owner + "|" + name;
    }

    private static String valKey(String owner, String name) {
        return PREFIX_VAL + owner + "|" + name;
    }

    private static boolean isOwnedKey(String key, String prefix, String owner) {
        return key.startsWith(prefix + owner + "|");
    }

    /** 属主校验：{@code instance:<ID>} 或 {@code mechanism:<ID>}；ID 非空且不含竖线。 */
    private static String requireOwner(String raw) {
        String owner = raw == null ? "" : raw.trim();
        int separator = owner.indexOf(':');
        if (separator <= 0 || owner.indexOf('|') >= 0) {
            throw new LifeloomException("属主无效（须为 instance:<ID> 或 mechanism:<ID>）: " + raw);
        }
        String kind = owner.substring(0, separator).trim();
        String id = owner.substring(separator + 1).trim();
        if ((!kind.equals("instance") && !kind.equals("mechanism")) || id.isEmpty()) {
            throw new LifeloomException("属主无效（须为 instance:<ID> 或 mechanism:<ID>）: " + raw);
        }
        return kind + ":" + id;
    }

    /** 参数名校验：字母 / 下划线开头，随后字母、数字或下划线（含中文）。 */
    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new LifeloomException("参数名无效: " + raw);
        }
        char first = name.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            throw new LifeloomException("参数名无效（须以字母或下划线开头）: " + raw);
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                throw new LifeloomException("参数名无效（含非法字符）: " + raw);
            }
        }
        return name;
    }

    /** 数值解析（拒绝非有限数）。 */
    private static double parseValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LifeloomException("数值无效: " + raw);
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new LifeloomException("数值无效: " + raw);
        }
        if (!Double.isFinite(value)) {
            throw new LifeloomException("数值无效（须为有限数）: " + raw);
        }
        return value;
    }

    /** 数值格式化：整数值输出整数形式，其余双精度原样输出（稳定、可作状态与比较）。 */
    private static String format(double value) {
        if (!Double.isFinite(value)) {
            throw new LifeloomException("参数值非有限数: " + value);
        }
        if (value == Math.rint(value) && Math.abs(value) <= 9.007199254740991E15) {
            return String.valueOf((long) value);
        }
        return Double.toString(value);
    }

    private static Map<String, String> parseFields(String input) {
        Map<String, String> fields = new LinkedHashMap<>();
        String text = input == null ? "" : input;
        for (String segment : text.split(";")) {
            int separator = segment.indexOf('=');
            if (separator < 0) {
                continue;
            }
            fields.put(segment.substring(0, separator).trim(), segment.substring(separator + 1).trim());
        }
        return fields;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LifeloomException("字段无效（" + field + "）: " + value);
        }
        return value;
    }
}
