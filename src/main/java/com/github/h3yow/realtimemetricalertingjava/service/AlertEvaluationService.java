package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.alert.AlertEngine;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertStatusView;
import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 告警评估服务：从窗口存储取聚合值（默认用窗口内平均值）喂给告警引擎。
 * 引擎以窗口起始时间做幂等，同一窗口重复评估不会重复告警或重复恢复。
 */
public final class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final WindowStore store;
    private final AlertEngine engine;

    public AlertEvaluationService(WindowStore store, AlertEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    /**
     * 评估指定规则在 atMillis 所属窗口的表现。
     *
     * @return 本次产生的事件；窗口尚无数据/重复窗口/无状态流转时返回 null
     */
    public AlertEvent evaluate(String ruleId, long atMillis) {
        AlertRule rule = engine.registeredRules().stream()
                .filter(r -> r.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown rule: " + ruleId));
        long windowStart = store.spec().windowStartFor(atMillis);
        Optional<WindowAggregate> agg = store.aggregateAt(rule.service(), rule.metric(), atMillis);
        if (agg.isEmpty()) {
            log.info("[alert-eval] rule={} window={} no data, skip evaluation", ruleId, windowStart);
            return null;
        }
        double value = agg.get().avg();
        log.info("[alert-eval] rule={} window=[{},{}) avg={} count={}",
                ruleId, windowStart, store.spec().windowEndFor(windowStart), value, agg.get().count());
        return engine.evaluate(ruleId, windowStart, value);
    }

    public AlertStatusView status(String ruleId) {
        return engine.statusOf(ruleId);
    }

    public List<AlertEvent> events(String ruleId) {
        return engine.eventsOf(ruleId);
    }
}
