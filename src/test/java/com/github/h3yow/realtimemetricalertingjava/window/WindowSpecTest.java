package com.github.h3yow.realtimemetricalertingjava.window;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证滚动窗口按 epoch 对齐，左闭右开。 */
class WindowSpecTest {

    private static final Logger log = LoggerFactory.getLogger(WindowSpecTest.class);

    private final WindowSpec spec = new WindowSpec(10_000L, 0L);

    @Test
    void windowBoundariesAreEpochAlignedAndHalfOpen() {
        long[] timestamps = {0L, 1L, 9_999L, 10_000L, 25_000L, 29_999L};
        for (long ts : timestamps) {
            long start = spec.windowStartFor(ts);
            long end = spec.windowEndFor(start);
            log.info("[test-window] ts={} -> window=[{},{})", ts, start, end);
            assertEquals(0L, start % 10_000L, "窗口起点必须对齐到 10s 边界");
            assertEquals(start + 10_000L, end, "窗口为左闭右开区间");
        }
        // 边界两侧必须落在不同窗口，不能混算
        assertEquals(0L, spec.windowStartFor(9_999L));
        assertEquals(10_000L, spec.windowStartFor(10_000L));
        assertEquals(20_000L, spec.windowStartFor(29_999L));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec(0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec(1_000L, -1L));
    }
}
