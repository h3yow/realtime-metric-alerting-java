package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.model.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.model.AlertState;
import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.model.WindowKey;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WindowDrivenAlertEvaluator implements AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(WindowDrivenAlertEvaluator.class);

    private final WindowStore windowStore;
    private final Clock clock;
    private final Map<String, RuleAlertState> states = new ConcurrentHashMap<>();
    private final Map<String, List<AlertEvent>> events = new ConcurrentHashMap<>();

    public WindowDrivenAlertEvaluator(WindowStore windowStore, Clock clock) {
        this.windowStore = windowStore;
        this.clock = clock;
    }

    @Override
    public AlertEvaluation evaluateWindow(AlertRule rule, long windowStart) {
        RuleAlertState machine = states.computeIfAbsent(
                rule.getId(), RuleAlertState::new);

        WindowData data = windowStore.getWindow(
                new WindowKey(rule.getService(), rule.getMetricName(), windowStart),
                rule.getWindowMillis());
        if (data == null) {
            // 无数据窗口不参与状态机，避免"没数据"被误判成恢复。
            log.info("[{}] evaluate window={} skipped: no data", rule.getId(), windowStart);
            return new AlertEvaluation(rule.getId(), machine.getState(), windowStart,
                    0.0, true, null);
        }

        double value = data.getAverage();
        boolean breached = rule.breached(value);
        // RuleAlertState.evaluate 自身 synchronized：同一规则并发评估在此串行化，
        // 同一窗口重复评估幂等，杜绝重复告警/状态跳变。
        String type = machine.evaluate(breached, windowStart, value,
                rule.getFireConsecutiveWindows(),
                rule.getResolveConsecutiveWindows(),
                clock.millis());

        AlertEvent event = null;
        if (type != null) {
            event = new AlertEvent();
            event.setRuleId(rule.getId());
            event.setService(rule.getService());
            event.setMetricName(rule.getMetricName());
            event.setType(type);
            event.setState(machine.getState());
            event.setWindowStart(windowStart);
            event.setValue(value);
            event.setEventTime(clock.millis());
            events.computeIfAbsent(rule.getId(), k -> new CopyOnWriteArrayList<>()).add(event);
            log.info("[{}] ALERT EVENT {} value={} threshold={} window={} -> state={}",
                    rule.getId(), type, value, rule.getThreshold(), windowStart, machine.getState());
        }
        return new AlertEvaluation(rule.getId(), machine.getState(), windowStart,
                value, false, event);
    }

    @Override
    public AlertState currentState(String ruleId) {
        RuleAlertState machine = states.get(ruleId);
        return machine == null ? AlertState.INACTIVE : machine.getState();
    }

    @Override
    public List<AlertEvent> events(String ruleId) {
        List<AlertEvent> list = events.get(ruleId);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public List<AlertEvent> allEvents() {
        List<AlertEvent> all = new ArrayList<>();
        events.values().forEach(all::addAll);
        all.sort(Comparator.comparingLong(AlertEvent::getEventTime)
                .thenComparing(AlertEvent::getRuleId));
        return all;
    }
}
