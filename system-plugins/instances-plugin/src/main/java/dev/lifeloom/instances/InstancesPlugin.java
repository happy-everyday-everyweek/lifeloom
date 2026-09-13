package dev.lifeloom.instances;

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
import java.util.Set;

/**
 * 实例插件（系统插件，世界组）：世界对象模型。
 *
 * <p>职责（v1）：实例创建 / 销毁、分类（人 / 动物 / 物品，不能新增顶层分类）、
 * 种标记、实例间关系（容纳 contain / 持有 hold，被容纳 / 被持有方唯一）、
 * “被体验实例”登记（enter / active）。更细的生命周期与关系语义随逐组细谈推进。
 *
 * <p>契约（v1）：
 * <ul>
 *   <li>钩子 {@code dev.lifeloom.instances.create}：输入 {@code id=<实例ID>;category=<人|动物|物品>;species=<种ID>}；输出 {@code created=<ID>}</li>
 *   <li>钩子 {@code dev.lifeloom.instances.destroy}：输入实例 ID；同时清理其关系与被体验登记</li>
 *   <li>钩子 {@code dev.lifeloom.instances.get}：输入实例 ID；输出 {@code category=<类别>;species=<种>}</li>
 *   <li>钩子 {@code dev.lifeloom.instances.relate} / {@code unrelate}：输入 {@code type=<contain|hold>;a=<ID>;b=<ID>}</li>
 *   <li>钩子 {@code dev.lifeloom.instances.relations}：输出 {@code contains=[...];inside=<ID|->;holds=[...];heldBy=<ID|->}</li>
 *   <li>钩子 {@code dev.lifeloom.instances.enter}（仅人）/ {@code active}：被体验实例登记 / 查询</li>
 *   <li>状态：{@code category:<ID>} / {@code species:<ID>} / {@code contain:<A>:<B>} / {@code hold:<A>:<B>} / {@code active}</li>
 * </ul>
 */
public final class InstancesPlugin implements Plugin {

    /** 机制 ID。 */
    public static final String MECHANISM_ID = "dev.lifeloom.instances";
    /** 创建实例。 */
    public static final String HOOK_CREATE = MECHANISM_ID + ".create";
    /** 销毁实例。 */
    public static final String HOOK_DESTROY = MECHANISM_ID + ".destroy";
    /** 读取实例。 */
    public static final String HOOK_GET = MECHANISM_ID + ".get";
    /** 建立关系。 */
    public static final String HOOK_RELATE = MECHANISM_ID + ".relate";
    /** 解除关系。 */
    public static final String HOOK_UNRELATE = MECHANISM_ID + ".unrelate";
    /** 查询关系。 */
    public static final String HOOK_RELATIONS = MECHANISM_ID + ".relations";
    /** 进入（登记被体验实例；仅人）。 */
    public static final String HOOK_ENTER = MECHANISM_ID + ".enter";
    /** 查询被体验实例。 */
    public static final String HOOK_ACTIVE = MECHANISM_ID + ".active";

    private static final Set<String> CATEGORIES = Set.of("人", "动物", "物品");
    private static final String PREFIX_CATEGORY = "category:";
    private static final String PREFIX_SPECIES = "species:";
    private static final String PREFIX_CONTAIN = "contain:";
    private static final String PREFIX_HOLD = "hold:";
    private static final String KEY_ACTIVE = "active";

    private MechanismState state;

    @Override
    public void onLoad(PluginContext context) {
        state = context.stateFor(MECHANISM_ID);
        context.registerMechanism(new Mechanism(MECHANISM_ID, "实例", List.of(
                Hook.of(HOOK_CREATE, this::create),
                Hook.of(HOOK_DESTROY, this::destroy),
                Hook.of(HOOK_GET, this::get),
                Hook.of(HOOK_RELATE, this::relate),
                Hook.of(HOOK_UNRELATE, this::unrelate),
                Hook.of(HOOK_RELATIONS, this::relations),
                Hook.of(HOOK_ENTER, this::enter),
                Hook.of(HOOK_ACTIVE, this::active))));
        System.out.println("[instances] 实例插件已装载：创建 / 分类 / 关系 / 被体验登记就绪");
    }

    @Override
    public void onUnload() {
        System.out.println("[instances] 实例插件卸载中：实例数据保留在机制状态");
    }

    /** 创建实例：id=<实例ID>;category=<人|动物|物品>;species=<种ID>。 */
    private String create(HookContext hookContext) {
        Map<String, String> fields = parseFields(hookContext.input());
        String id = require(fields.get("id"), "实例 ID");
        String category = require(fields.get("category"), "分类");
        if (!CATEGORIES.contains(category)) {
            throw new LifeloomException("分类无效（须为人 / 动物 / 物品）: " + category);
        }
        String species = require(fields.get("species"), "种");
        if (exists(id)) {
            throw new LifeloomException("实例已存在: " + id);
        }
        state.put(PREFIX_CATEGORY + id, category);
        state.put(PREFIX_SPECIES + id, species);
        return "created=" + id;
    }

    /** 销毁实例，并清理其关系与被体验登记。 */
    private String destroy(HookContext hookContext) {
        String id = requireId(hookContext.input());
        requireInstance(id);
        state.remove(PREFIX_CATEGORY + id);
        state.remove(PREFIX_SPECIES + id);
        List<String> related = new ArrayList<>();
        for (String key : state.keys()) {
            if (isRelationOf(key, PREFIX_CONTAIN, id) || isRelationOf(key, PREFIX_HOLD, id)) {
                related.add(key);
            }
        }
        for (String key : related) {
            state.remove(key);
        }
        if (id.equals(state.get(KEY_ACTIVE))) {
            state.remove(KEY_ACTIVE);
        }
        return "destroyed=" + id;
    }

    /** 读取实例：输出 category=<类别>;species=<种>。 */
    private String get(HookContext hookContext) {
        String id = requireId(hookContext.input());
        requireInstance(id);
        return "category=" + state.get(PREFIX_CATEGORY + id)
                + ";species=" + state.get(PREFIX_SPECIES + id);
    }

    /** 建立关系：type=<contain|hold>;a=<ID>;b=<ID>。 */
    private String relate(HookContext hookContext) {
        return setRelation(hookContext.input(), true);
    }

    /** 解除关系（幂等）。 */
    private String unrelate(HookContext hookContext) {
        return setRelation(hookContext.input(), false);
    }

    private String setRelation(String input, boolean link) {
        Map<String, String> fields = parseFields(input);
        String type = require(fields.get("type"), "关系类型");
        if (!type.equals("contain") && !type.equals("hold")) {
            throw new LifeloomException("关系类型无效（须为 contain 或 hold）: " + type);
        }
        String a = require(fields.get("a"), "实例 a");
        String b = require(fields.get("b"), "实例 b");
        requireInstance(a);
        requireInstance(b);
        if (a.equals(b)) {
            throw new LifeloomException("关系两端不能是同一实例: " + a);
        }
        String prefix = relationPrefix(type);
        String key = prefix + a + ":" + b;
        if (link) {
            String existing = findCounterpart(prefix, b);
            if (existing != null && !existing.equals(a)) {
                throw new LifeloomException("目标已被关联（" + type + "）: " + b + " <- " + existing);
            }
            state.put(key, "true");
            return "related=" + type + ":" + a + ":" + b;
        }
        state.remove(key);
        return "unrelated=" + type + ":" + a + ":" + b;
    }

    /** 查询关系：contains=[...];inside=<ID|->;holds=[...];heldBy=<ID|->。 */
    private String relations(HookContext hookContext) {
        String id = requireId(hookContext.input());
        requireInstance(id);
        String inside = findCounterpart(PREFIX_CONTAIN, id);
        String heldBy = findCounterpart(PREFIX_HOLD, id);
        return "contains=" + targetsOf(PREFIX_CONTAIN, id)
                + ";inside=" + (inside == null ? "-" : inside)
                + ";holds=" + targetsOf(PREFIX_HOLD, id)
                + ";heldBy=" + (heldBy == null ? "-" : heldBy);
    }

    /** 进入：登记被体验实例（仅人）。 */
    private String enter(HookContext hookContext) {
        String id = requireId(hookContext.input());
        requireInstance(id);
        if (!"人".equals(state.get(PREFIX_CATEGORY + id))) {
            throw new LifeloomException("只有人可以成为被体验实例: " + id);
        }
        state.put(KEY_ACTIVE, id);
        return "entered=" + id;
    }

    /** 查询被体验实例：active=<ID|->。 */
    private String active(HookContext hookContext) {
        String active = state.get(KEY_ACTIVE);
        return "active=" + (active == null ? "-" : active);
    }

    private boolean exists(String id) {
        return state.get(PREFIX_CATEGORY + id) != null;
    }

    private void requireInstance(String id) {
        if (!exists(id)) {
            throw new LifeloomException("实例不存在: " + id);
        }
    }

    /** 某方向关系的全部目标（排序）。 */
    private List<String> targetsOf(String prefix, String id) {
        List<String> targets = new ArrayList<>();
        String head = prefix + id + ":";
        for (String key : state.keys()) {
            if (key.startsWith(head)) {
                targets.add(key.substring(head.length()));
            }
        }
        targets.sort(null);
        return targets;
    }

    /** 反向关系（谁容纳 / 持有了 id；排序取首个，唯一性由关联时保证）。 */
    private String findCounterpart(String prefix, String id) {
        String tail = ":" + id;
        List<String> found = new ArrayList<>();
        for (String key : state.keys()) {
            if (key.startsWith(prefix) && key.endsWith(tail)) {
                found.add(key.substring(prefix.length(), key.length() - tail.length()));
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        found.sort(null);
        return found.get(0);
    }

    private static boolean isRelationOf(String key, String prefix, String id) {
        return key.startsWith(prefix + id + ":") || (key.startsWith(prefix) && key.endsWith(":" + id));
    }

    private static String relationPrefix(String type) {
        return type.equals("hold") ? PREFIX_HOLD : PREFIX_CONTAIN;
    }

    private static String requireId(String input) {
        String id = input == null ? "" : input.trim();
        if (id.isEmpty()) {
            throw new LifeloomException("实例 ID 无效: " + input);
        }
        return id;
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
