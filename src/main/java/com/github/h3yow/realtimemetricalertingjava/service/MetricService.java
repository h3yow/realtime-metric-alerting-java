package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.alert.AlertEvaluator;
import com.github.h3yow.realtimemetricalertingjava.alert.AlertRuleRegistry;
import com.github.h3yow.realtimemetricalertingjava.config.AlertingProperties;
import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.model.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.model.MetricWriteResult;
import com.github.h3yow.realtimemetricalertingjava.model.RejectReason;
import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 指标写入与查询入口：负责合法性/时效性校验、按窗口写入、幂等去重，
 * 以及写入后触发匹配规则的告警评估。
 */
@Service
public class MetricService {

    private static final Logger log = LoggerFactory.getLogger(MetricService.class);

    private final WindowStore windowStore;
    private final AlertRuleRegistry ruleRegistry;
    private final AlertEvaluator alertEvaluator;
    private final AlertingProperties properties;
    private final Clock clock;

    public MetricService(WindowStore windowStore, AlertRuleRegistry ruleRegistry,
                         AlertEvaluator alertEvaluator, AlertingProperties properties, Clock clock) {
        this.windowStore = windowStore;
        this.ruleRegistry = ruleRegistry;
        this.alertEvaluator = alertEvaluator;
        this.properties = properties;
        this.clock = clock;
    }

    /** 写入单点，返回带原因的结果。 */
    public MetricWriteResult write(MetricPoint point) {
        if (point == null) {
            return MetricWriteResult.rejected(RejectReason.INVALID_VALUE, "point must not be null");
        }
        if (point.getService() == null || point.getService().isBlank()) {
            return reject(point, RejectReason.INVALID_SERVICE, "service must not be blank");
        }
        if (point.getMetricName() == null || point.getMetricName().isBlank()) {
            return reject(point, RejectReason.INVALID_METRIC_NAME, "metricName must not be blank");
        }
        if (!Double.isFinite(point.getValue())) {
            return reject(point, RejectReason.INVALID_VALUE, "value must be finite, got " + point.getValue());
        }
        long ts = point.getTimestamp();
        if (ts <= 0) {
            return reject(point, RejectReason.INVALID_TIMESTAMP,
                    "timestamp must be positive epoch millis, got " + ts);
        }

        long now = clock.millis();
        if (ts > now + properties.getMaxFutureMillis()) {
            return reject(point, RejectReason.FUTURE_TIMESTAMP,
                    "timestamp " + ts + " is more than " + properties.getMaxFutureMillis()
                            + "ms in the future (now=" + now + ")");
        }
        if (ts < now - properties.getMaxLateMillis()) {
            return reject(point, RejectReason.EXPIRED_TIMESTAMP,
                    "timestamp " + ts + " is older than maxLateMillis="
                            + properties.getMaxLateMillis() + " (now=" + now + ")");
        }

        long windowMillis = properties.getWindowMillis();
        long windowStart = Math.floorDiv(ts, windowMillis) * windowMillis;
        WindowStore.WriteStatus status = windowStore.add(
                point.getService(), point.getMetricName(), ts,
                point.getValue(), point.getId(), windowMillis);

        if (status == WindowStore.WriteStatus.DUPLICATE) {
            return MetricWriteResult.duplicate(point.getId(), windowStart);
        }

        MetricWriteResult result = MetricWriteResult.accepted(point.getId(), windowStart);
        log.info("accepted point service={} metric={} ts={} -> window=[{} - {})",
                point.getService(), point.getMetricName(), ts,
                windowStart, windowStart + windowMillis);

        if (properties.isAutoEvaluate()) {
            evaluateMatchingRules(point, windowStart);
        }
        return result;
    }

    /**
     * 写入后评估匹配该 service+metric 的规则。迟到数据写入的是过去窗口，
     * 状态机会把早于已评估窗口的评估直接忽略，因此不会用旧窗口污染当前状态。
     */
    private void evaluateMatchingRules(MetricPoint point, long windowStart) {
        for (AlertRule rule : ruleRegistry.all()) {
            if (rule.getService().equals(point.getService())
                    && rule.getMetricName().equals(point.getMetricName())) {
                try {
                    alertEvaluator.evaluateWindow(rule, windowStart);
                } catch (RuntimeException e) {
                    log.error("rule evaluation failed ruleId={} window={}",
                            rule.getId(), windowStart, e);
                }
            }
        }
    }

    private MetricWriteResult reject(MetricPoint point, RejectReason reason, String message) {
        log.info("rejected point service={} metric={} ts={} reason={} detail={}",
                point.getService(), point.getMetricName(), point.getTimestamp(), reason, message);
        return MetricWriteResult.rejected(reason, message);
    }

    /** 批量写入（含重复提交幂等）：逐点处理，结果与提交次数无关。 */
    public List<MetricWriteResult> writeBatch(List<MetricPoint> points) {
        List<MetricWriteResult> results = new ArrayList<>();
        if (points == null) {
            return results;
        }
        for (MetricPoint p : points) {
            results.add(write(p));
        }
        return results;
    }

    /** 查询给定时间范围内覆盖到的窗口（边界按窗口对齐）。 */
    public List<WindowData> query(String service, String metricName, long from, long to) {
        if (service == null || service.isBlank() || metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("service and metricName must not be blank");
        }
        if (from <= 0 || to <= 0 || from >= to) {
            throw new IllegalArgumentException("invalid time range: from=" + from + ", to=" + to);
        }
        return windowStore.query(service, metricName, from, to, properties.getWindowMillis());
    }
}
