package dev.lifeloom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件总线（核心原语，M3 调度）：广播式通知通道。
 *
 * <p>钩子用于“被调用执行”；事件用于“发生了什么”的广播：插件发射事件，
 * 所有订阅者按注册顺序收到通知。订阅绑定到插件，插件卸载 / 替换时自动解除；
 * 分发在逻辑线程上同步进行（发射已由核心派发到逻辑线程），单个订阅者抛错只记录、
 * 不影响其余订阅者。
 *
 * <p>发射经核心的执行闸门（替换期间拒绝新发射、替换等待在途派发）；
 * 事件本身不设权限闸门，是否收紧随权限细谈再定。
 */
public final class EventBus {

    /** 一次事件：发射者、事件 ID、文本载荷。 */
    public static final class Event {

        private final String emitter;
        private final String eventId;
        private final String payload;

        Event(String emitter, String eventId, String payload) {
            this.emitter = emitter;
            this.eventId = eventId;
            this.payload = payload;
        }

        /** 发射者插件 ID；核心 / 外壳发射时为 {@code "(core)"}。 */
        public String emitter() {
            return emitter;
        }

        public String eventId() {
            return eventId;
        }

        /** 附带载荷（可为 null）。 */
        public String payload() {
            return payload;
        }
    }

    /** 订阅者回调。 */
    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event);
    }

    private static final class Subscription {

        final LoadedPlugin owner;
        final Listener listener;

        Subscription(LoadedPlugin owner, Listener listener) {
            this.owner = owner;
            this.listener = listener;
        }
    }

    private final Map<String, List<Subscription>> subscriptions = new LinkedHashMap<>();

    /** 订阅事件（绑定插件；卸载 / 替换时自动解除）。同一插件可重复订阅，将收到多次通知。 */
    synchronized void subscribe(LoadedPlugin owner, String eventId, Listener listener) {
        if (eventId == null || eventId.isBlank()) {
            throw new LifeloomException("事件 ID 不能为空");
        }
        if (listener == null) {
            throw new LifeloomException("订阅回调不能为空");
        }
        subscriptions.computeIfAbsent(eventId, key -> new ArrayList<>())
                .add(new Subscription(owner, listener));
    }

    /** 移除某插件的全部订阅（卸载 / 装载失败时调用）。 */
    synchronized void removeSubscriptionsOf(LoadedPlugin plugin) {
        for (List<Subscription> list : subscriptions.values()) {
            list.removeIf(subscription -> subscription.owner == plugin);
        }
        subscriptions.values().removeIf(List::isEmpty);
    }

    /**
     * 发射事件（同步分发给全部订阅者）。
     *
     * @param emitter 发射者（插件）；核心 / 外壳发射时可为 null
     * @param eventId 事件 ID（约定：类包名风格）
     * @param payload 文本载荷（可为 null）
     */
    public void emit(LoadedPlugin emitter, String eventId, String payload) {
        if (eventId == null || eventId.isBlank()) {
            throw new LifeloomException("事件 ID 不能为空");
        }
        List<Subscription> snapshot;
        synchronized (this) {
            List<Subscription> list = subscriptions.get(eventId);
            if (list == null || list.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(list);
        }
        Event event = new Event(
                emitter == null ? "(core)" : emitter.descriptor().id(), eventId, payload);
        for (Subscription subscription : snapshot) {
            try {
                subscription.listener.onEvent(event);
            } catch (Exception e) {
                System.err.println("[core] 事件订阅者异常（" + eventId + "）: " + e);
            }
        }
    }

    /** 某事件当前的订阅者数量（诊断用）。 */
    public synchronized int subscriberCount(String eventId) {
        List<Subscription> list = subscriptions.get(eventId);
        return list == null ? 0 : list.size();
    }
}
