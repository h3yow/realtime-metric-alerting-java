package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.domain.AlertEventType;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 阈值反复抖动：触发需连续 3 次确认、恢复需连续 2 次确认，
 * 抖动期间状态不得来回跳变。
 */
class AlertStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(AlertStateMachineTest.class);

    /** breach=true 表示窗口值超过阈值；按 10s 一个窗口推进。 */
    private AlertEventType advance(AlertStateMachine m, long windowIndex, boolean breached, double value) {
        AlertEventType t = m.advance(breached, value, windowIndex * 10_000L);
        log.info("[test-alert] window=[{},{}) value={} breached={} -> event={} state={}",
                windowIndex * 10_000L, (windowIndex + 1) * 10_000L, value, breached, t, m.state());
        return t;
    }

    @Test
    void flappingDoesNotRepeatedlyTriggerAndRecover() {
        AlertStateMachine m = new AlertStateMachine("r1", 3, 2);

        // 超阈值但中途被打断：不触发
        assertNull(advance(m, 0, true, 101));
        assertNull(advance(m, 1, true, 102));
        assertNull(advance(m, 2, false, 99)); // 抖动回落，进度清零
        assertNull(advance(m, 3, true, 103));
        assertNull(advance(m, 4, true, 104));
        assertEquals(AlertState.NORMAL, m.state());

        // 连续 3 次超阈值：触发一次
        assertNull(advance(m, 5, false, 50));
        assertNull(advance(m, 6, true, 110));
        assertNull(advance(m, 7, true, 111));
        assertEquals(AlertEventType.TRIGGERED, advance(m, 8, true, 112));
        assertEquals(AlertState.FIRING, m.state());

        // FIRING 中继续超阈值：不再重复触发
        assertNull(advance(m, 9, true, 130));

        // 恢复需连续 2 次，先 1 次回落再超阈值：保持 FIRING，不产生 RECOVERED
        assertNull(advance(m, 10, false, 80));
        assertNull(advance(m, 11, true, 120));
        assertEquals(AlertState.FIRING, m.state());

        // 连续 2 次回落：恢复一次
        assertNull(advance(m, 12, false, 70));
        assertEquals(AlertEventType.RECOVERED, advance(m, 13, false, 60));
        assertEquals(AlertState.NORMAL, m.state());

        // 恢复后单次超阈值不会再触发
        assertNull(advance(m, 14, true, 150));
        assertEquals(AlertState.NORMAL, m.state());
    }

    @Test
    void exactThresholdIsNotBreach() {
        AlertStateMachine m = new AlertStateMachine("r2", 1, 1);
        // 等于阈值不算 breach（严格大于）
        assertNull(m.advance(false, 100.0, 0L));
        assertEquals(AlertState.NORMAL, m.state());
        assertEquals(AlertEventType.TRIGGERED, m.advance(true, 100.0001, 10_000L));
        assertEquals(AlertEventType.RECOVERED, m.advance(false, 100.0, 20_000L));
    }
}
