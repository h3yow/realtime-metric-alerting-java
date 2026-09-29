package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.domain.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertState;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警引擎。
 * <ul>
 *   <li>每条规则一把锁：同一规则被并发评估时串行执行，不会出现重复告警或状态跳变；
 *       不同规则之间互不阻塞。</li>
 *   <li>以“窗口起始时间”为评估幂等键：同一窗口无论被评估多少次，只推进一次状态机，
 *       因此重复提交/重复评估不会产生重复事件。</li>
 * </ul>
 */
public final class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private static final class RuleHolder {
        final AlertRule rule;
        final AlertStateMachine machine;
        final Object lock = new Object();
        /** 已评估过的窗口起始时间，保证窗口级评估幂等。 */
        final java.util.Set<Long> evaluatedWindows = new java.util.HashSet<>();
        final List<AlertEvent> events = new ArrayList<>();

        RuleHolder(AlertRule rule) {
            this.rule = rule;
            this.machine = new AlertStateMachine(rule.id(), rule.breachForN(), rule.recoverForN());
        }
    }

    private final Map<String, RuleHolder> rules = new ConcurrentHashMap<>();

    public AlertEngine() {
    }

    public void register(AlertRule rule) {
        RuleHolder holder = new RuleHolder(rule);
        if (rules.putIfAbsent(rule.id(), holder) != null) {
            throw new IllegalStateException("rule already registered: " + rule.id());
        }
        log.info("[alert] registered rule={} service={} metric={} threshold={} breachForN={} recoverForN={}",
                rule.id(), rule.service(), rule.metric(), rule.threshold(),
                rule.breachForN(), rule.recoverForN());
    }

    /**
     * 评估某条规则在指定窗口的表现。
     *
     * @param windowStart 窗口起始时间（调用方负责对齐）
     * @param value       该窗口的聚合值
     * @return 本次新产生的事件；重复窗口/无流转返回 null
     */
    public AlertEvent evaluate(String ruleId, long windowStart, double value) {
        RuleHolder holder = rules.get(ruleId);
        if (holder == null) {
            throw new IllegalArgumentException("unknown rule: " + ruleId);
        }
        synchronized (holder.lock) {
            if (!holder.evaluatedWindows.add(windowStart)) {
                log.info("[alert] rule={} window={} already evaluated, skip (idempotent)",
                        ruleId, windowStart);
                return null;
            }
            boolean breached = value > holder.rule.threshold();
            com.github.h3yow.realtimemetricalertingjava.domain.AlertEventType type =
                    holder.machine.advance(breached, value, windowStart);
            if (type == null) {
                return null;
            }
            AlertEvent event = new AlertEvent(ruleId, holder.rule.service(), holder.rule.metric(),
                    type, windowStart, value, holder.rule.threshold(), System.currentTimeMillis());
            holder.events.add(event);
            return event;
        }
    }

    public AlertStatusView statusOf(String ruleId) {
        RuleHolder holder = rules.get(ruleId);
        if (holder == null) {
            throw new IllegalArgumentException("unknown rule: " + ruleId);
        }
        synchronized (holder.lock) {
            AlertState s = holder.machine.state();
            return new AlertStatusView(ruleId, s, holder.machine.consecutiveBreaches(),
                    holder.machine.consecutiveRecoveries(), holder.machine.lastValue(),
                    holder.machine.lastWindowStart(), holder.machine.version());
        }
    }

    public List<AlertEvent> eventsOf(String ruleId) {
        RuleHolder holder = rules.get(ruleId);
        if (holder == null) {
            throw new IllegalArgumentException("unknown rule: " + ruleId);
        }
        synchronized (holder.lock) {
            return new ArrayList<>(holder.events);
        }
    }

    /** 返回全部已注册规则（供批量评估使用）。 */
    public List<AlertRule> registeredRules() {
        List<AlertRule> all = new ArrayList<>();
        for (RuleHolder h : rules.values()) {
            all.add(h.rule);
        }
        return List.copyOf(all);
    }
}
