package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.alert.InMemoryAlertRuleRegistry;
import com.github.h3yow.realtimemetricalertingjava.alert.WindowDrivenAlertEvaluator;
import com.github.h3yow.realtimemetricalertingjava.config.AlertingProperties;
import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.model.AlertState;
import com.github.h3yow.realtimemetricalertingjava.model.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.model.MetricWriteResult;
import com.github.h3yow.realtimemetricalertingjava.model.RejectReason;
import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.window.InMemoryWindowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖：非法/过期/未来数据按原因拒绝；乱序迟到落实际窗口；
 * 同一批数据重复提交结果与提交一次一致；高并发写入计数正确。
 */
class MetricServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MetricServiceTest.class);

    private static final long NOW = 1_700_000_000_000L;
    private static final long W = 60_000L;

    private InMemoryWindowStore store;
    private MetricService service;

    private MetricPoint point(String id, String service, String metric, double v, long ts) {
        MetricPoint p = new MetricPoint();
        p.setId(id);
        p.setService(service);
        p.setMetricName(metric);
        p.setValue(v);
        p.setTimestamp(ts);
        return p;
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        store = new InMemoryWindowStore();
        InMemoryAlertRuleRegistry registry = new InMemoryAlertRuleRegistry();
        WindowDrivenAlertEvaluator evaluator = new WindowDrivenAlertEvaluator(store, clock);
        AlertingProperties props = new AlertingProperties();
        props.setWindowMillis(W);
        props.setMaxFutureMillis(5_000L);
        props.setMaxLateMillis(300_000L); // 只接受 5 分钟内的迟到数据
        props.setAutoEvaluate(false);
        service = new MetricService(store, registry, evaluator, props, clock);
    }

    @Test
    void rejectsInvalidDataByDistinctReasons() {
        assertReason(null, RejectReason.INVALID_VALUE);
        assertReason(point("1", "", "cpu", 1, NOW), RejectReason.INVALID_SERVICE);
        assertReason(point("1", "s", " ", 1, NOW), RejectReason.INVALID_METRIC_NAME);
        assertReason(point("1", "s", "cpu", Double.NaN, NOW), RejectReason.INVALID_VALUE);
        assertReason(point("1", "s", "cpu", 1, 0), RejectReason.INVALID_TIMESTAMP);
        assertReason(point("1", "s", "cpu", 1, NOW + 60_000L), RejectReason.FUTURE_TIMESTAMP);
        assertReason(point("1", "s", "cpu", 1, NOW - 301_000L), RejectReason.EXPIRED_TIMESTAMP);
    }

    private void assertReason(MetricPoint p, RejectReason expected) {
        MetricWriteResult r = service.write(p);
        assertFalse(r.isAccepted());
        assertEquals(expected, r.getReason(),
                "失败必须按原因区分, got message=" + r.getMessage());
        log.info("rejected as expected: reason={} message={}", r.getReason(), r.getMessage());
    }

    @Test
    void acceptsLateDataWithinGraceIntoActualWindow() {
        // 3 分钟前的数据仍然合法，必须落到它自己的窗口
        MetricWriteResult r = service.write(point("late-1", "s", "cpu", 9, NOW - 180_000L));
        assertTrue(r.isAccepted());
        long expectedWindow = Math.floorDiv(NOW - 180_000L, W) * W;
        assertEquals(expectedWindow, r.getWindowStart());
        log.info("late point accepted into window [{} - {})",
                expectedWindow, expectedWindow + W);

        List<WindowData> windows = service.query("s", "cpu", expectedWindow, expectedWindow + W);
        assertEquals(1, windows.size());
        assertEquals(1, windows.get(0).getCount());
    }

    @Test
    void duplicateSubmissionIsIdempotent() {
        List<MetricPoint> batch = List.of(
                point("b1", "s", "cpu", 1, NOW - 10_000L),
                point("b2", "s", "cpu", 2, NOW - 9_000L),
                point("b3", "s", "cpu", 3, NOW - 8_000L));

        List<MetricWriteResult> first = service.writeBatch(batch);
        List<MetricWriteResult> second = service.writeBatch(batch);
        List<MetricWriteResult> third = service.writeBatch(batch);

        assertEquals(3, first.stream().filter(MetricWriteResult::isAccepted).count());
        for (MetricWriteResult r : second) {
            assertTrue(r.isAccepted());
            assertEquals(RejectReason.DUPLICATE_POINT, r.getReason());
        }
        for (MetricWriteResult r : third) {
            assertEquals(RejectReason.DUPLICATE_POINT, r.getReason());
        }

        long from = Math.floorDiv(NOW - 10_000L, W) * W;
        List<WindowData> windows = service.query("s", "cpu", from, from + W);
        WindowData w = windows.get(0);
        log.info("after 3x duplicate submission window [{} - {}): count={} sum={} avg={}",
                w.getWindowStart(), w.getWindowEnd(), w.getCount(), w.getSum(), w.getAverage());
        // 重复提交 3 次与只提交 1 次结果一致
        assertEquals(3, w.getCount());
        assertEquals(6.0, w.getSum(), 1e-9);
    }

    @Test
    void concurrentWritersAndReadersStayConsistent() throws InterruptedException {
        int writerThreads = 12;
        int pointsPerWriter = 500;
        Thread[] writers = new Thread[writerThreads];
        for (int t = 0; t < writerThreads; t++) {
            final int base = t * pointsPerWriter;
            writers[t] = new Thread(() -> {
                for (int i = 0; i < pointsPerWriter; i++) {
                    service.write(point("c-" + (base + i), "s", "qps", 1, NOW - 1000L));
                }
            });
        }
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                long from = Math.floorDiv(NOW - 60_000L, W) * W;
                List<WindowData> data = service.query("s", "qps", from, from + 2 * W);
                for (WindowData d : data) {
                    // 读取过程中 count/sum 必须自洽
                    assertTrue(d.getCount() >= 0);
                    assertTrue(d.getSum() >= 0);
                }
            }
        });
        for (Thread th : writers) th.start();
        reader.start();
        for (Thread th : writers) th.join();
        reader.join();

        long from = Math.floorDiv(NOW - 1000L, W) * W;
        WindowData w = service.query("s", "qps", from, from + W).get(0);
        log.info("concurrent writers+readers final window [{} - {}): count={}",
                w.getWindowStart(), w.getWindowEnd(), w.getCount());
        assertEquals(writerThreads * pointsPerWriter, w.getCount(), "并发写入不能丢点");
    }

    @Test
    void autoEvaluationDrivesAlertStateAcrossWindows() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        InMemoryWindowStore s2 = new InMemoryWindowStore();
        InMemoryAlertRuleRegistry registry = new InMemoryAlertRuleRegistry();
        WindowDrivenAlertEvaluator evaluator = new WindowDrivenAlertEvaluator(s2, clock);
        AlertingProperties props = new AlertingProperties();
        props.setWindowMillis(W);
        props.setMaxFutureMillis(300_000L);
        props.setMaxLateMillis(300_000L);
        props.setAutoEvaluate(true);
        MetricService svc = new MetricService(s2, registry, evaluator, props, clock);

        AlertRule rule = new AlertRule();
        rule.setId("r1");
        rule.setService("s");
        rule.setMetricName("cpu");
        rule.setOperator("GT");
        rule.setThreshold(0.8);
        rule.setFireConsecutiveWindows(2);
        rule.setResolveConsecutiveWindows(2);
        rule.setWindowMillis(W);
        registry.register(rule);

        // 按窗口时间先后写入（全部在 NOW 之前）：
        // 连续两个窗口正常 -> 连续两个窗口越限(FIRED) -> 连续两个窗口正常(RESOLVED)
        long w0 = Math.floorDiv(NOW, W) * W;
        svc.write(point("r1", "s", "cpu", 0.1, w0 - 3 * W + 10_000L)); // 正常
        svc.write(point("r2", "s", "cpu", 0.2, w0 - 2 * W + 10_000L)); // 正常 -> INACTIVE
        svc.write(point("a1", "s", "cpu", 0.9, w0 - W + 10_000L));     // 越限
        assertEquals(AlertState.PENDING_FIRING, evaluator.currentState("r1"));
        svc.write(point("a2", "s", "cpu", 0.95, w0 + 10_000L));        // 越限 -> FIRED
        assertEquals(AlertState.FIRING, evaluator.currentState("r1"));
        log.info("alert fired, events={}", evaluator.events("r1"));

        // 更晚的连续两个正常窗口 -> RESOLVED（时间在 NOW 允许的未来偏差内）
        svc.write(point("a3", "s", "cpu", 0.1, w0 + W + 1_000L));
        assertEquals(AlertState.PENDING_RESOLVE, evaluator.currentState("r1"));
        svc.write(point("a4", "s", "cpu", 0.2, w0 + 2 * W + 1_000L));
        assertEquals(AlertState.INACTIVE, evaluator.currentState("r1"));
        assertEquals(2, evaluator.events("r1").size());
        log.info("alert resolved, events={}", evaluator.events("r1"));
    }
}
