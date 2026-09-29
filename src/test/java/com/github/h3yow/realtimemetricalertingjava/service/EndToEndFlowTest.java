package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.alert.AlertEngine;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertEventType;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertState;
import com.github.h3yow.realtimemetricalertingjava.domain.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import com.github.h3yow.realtimemetricalertingjava.window.WindowSpec;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 端到端：乱序/迟到写入 -> 窗口聚合 -> 阈值抖动下的状态机流转，全部在进程内完成。
 */
class EndToEndFlowTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndFlowTest.class);

    private MetricPoint mp(long ts, double v) {
        return new MetricPoint("svc", "cpu", ts, v, null, null);
    }

    @Test
    void outOfOrderIngestionThenFlappingAlertLifecycle() {
        WindowSpec spec = new WindowSpec(10_000L, 60_000L);
        WindowStore store = new WindowStore(spec, 100);
        IngestionService ingest = new IngestionService(store, 60_000L, 3_600_000L);
        AlertEngine engine = new AlertEngine();
        engine.register(new AlertRule("cpu-hot", "svc", "cpu", 80.0, 2, 2));
        AlertEvaluationService eval = new AlertEvaluationService(store, engine);

        // 以最近时间为基准，保证数据不被判为过期
        long now = System.currentTimeMillis();
        long base = spec.windowStartFor(now - 60_000L);
        long nowForIngest = base + 70_000L;

        // 1) 乱序/迟到写入：先写第 4 个窗口，再倒序补前 3 个窗口，每个窗口 2 个点
        List<MetricPoint> points = new ArrayList<>();
        double[] windowAvg = {90, 70, 90, 90, 70, 70};
        for (int w = 5; w >= 0; w--) {
            points.add(mp(base + w * 10_000L + 1_000L, windowAvg[w] - 5));
            points.add(mp(base + w * 10_000L + 2_000L, windowAvg[w] + 5));
        }
        var result = ingest.ingest(points, nowForIngest);
        assertEquals(12, result.acceptedCount(), "所有乱序/迟到点都应被接受");

        // 2) 验证窗口边界与聚合：迟到数据必须在其实际窗口里
        List<WindowAggregate> windows = store.query("svc", "cpu", base, base + 60_000L);
        assertEquals(6, windows.size());
        for (WindowAggregate w : windows) {
            log.info("[test-e2e] window=[{},{}) count={} avg={}",
                    w.windowStart(), w.windowEnd(), w.count(), w.avg());
            assertEquals(2L, w.count(), "每个窗口恰好 2 个点，迟到数据不能串窗口");
        }

        // 3) 评估顺序沿窗口推进，触发需连续 2 次 breach，恢复需连续 2 次回落
        AlertEvent e0 = eval.evaluate("cpu-hot", base + 1_000L);      // avg 90 breach
        assertNull(e0);
        AlertEvent e1 = eval.evaluate("cpu-hot", base + 11_000L);     // avg 70 ok -> 进度清零
        assertNull(e1);
        AlertEvent e2 = eval.evaluate("cpu-hot", base + 21_000L);     // avg 90 breach
        assertNull(e2);
        AlertEvent trigger = eval.evaluate("cpu-hot", base + 31_000L);// avg 90 breach -> TRIGGERED
        assertNotNull(trigger);
        assertEquals(AlertEventType.TRIGGERED, trigger.type());
        log.info("[test-e2e] TRIGGERED at window={}", trigger.windowStart());

        AlertEvent e4 = eval.evaluate("cpu-hot", base + 41_000L);     // avg 70 一次回落不够
        assertNull(e4);
        assertEquals(AlertState.FIRING, eval.status("cpu-hot").state());
        AlertEvent recover = eval.evaluate("cpu-hot", base + 51_000L);// avg 70 再次回落 -> RECOVERED
        assertNotNull(recover);
        assertEquals(AlertEventType.RECOVERED, recover.type());
        log.info("[test-e2e] RECOVERED at window={}, total events={}",
                recover.windowStart(), eval.events("cpu-hot").size());

        // 4) 重复评估历史窗口不产生新事件（状态不跳变）
        assertNull(eval.evaluate("cpu-hot", base + 31_000L));
        assertEquals(2, eval.events("cpu-hot").size());
        assertEquals(AlertState.NORMAL, eval.status("cpu-hot").state());
    }
}
