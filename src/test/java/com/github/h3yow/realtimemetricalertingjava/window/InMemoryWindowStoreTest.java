package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.model.WindowKey;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 乱序 / 迟到数据必须按实际发生时间落到对应窗口，不能混入其他窗口。
 */
class InMemoryWindowStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryWindowStoreTest.class);
    private static final long W = 60_000L;

    private void logWindows(String title, List<WindowData> windows) {
        log.info("==== {} ====", title);
        for (WindowData d : windows) {
            log.info("window [{} - {}) count={} sum={} avg={} min={} max={}",
                    d.getWindowStart(), d.getWindowEnd(),
                    d.getCount(), d.getSum(), d.getAverage(), d.getMin(), d.getMax());
        }
    }

    @Test
    void outOfOrderAndLateDataLandInActualWindow() {
        InMemoryWindowStore store = new InMemoryWindowStore();
        long base = 1_000_000_000_000L;
        long w0Start = Math.floorDiv(base + 30_000L, W) * W; // 含 base+30s 的窗口起点

        // 先写窗口 1 的数据，再"迟到"补窗口 0 的数据
        store.add("svc-a", "cpu", base + 90_000L, 10, "p2", W);
        store.add("svc-a", "cpu", base + 30_000L, 5, "p1", W);
        // 同一窗口内的另一个点
        store.add("svc-a", "cpu", base + 59_000L, 7, "p3", W);

        List<WindowData> windows = store.query("svc-a", "cpu", w0Start, w0Start + 2 * W, W);
        logWindows("out-of-order/late landing", windows);

        assertEquals(2, windows.size(), "迟到数据必须形成独立的前一个窗口");
        WindowData first = windows.get(0);
        WindowData second = windows.get(1);
        assertEquals(w0Start, first.getWindowStart());
        assertEquals(w0Start + W, second.getWindowStart());
        assertEquals(2, first.getCount());
        assertEquals(12.0, first.getSum(), 1e-9);
        assertEquals(1, second.getCount());
        assertEquals(10.0, second.getSum(), 1e-9);
        // 不能把不同时间点的数据算进同一个窗口
        assertNotEquals(first.getWindowStart(), second.getWindowStart());
    }

    @Test
    void sameWindowBoundaryIsExclusiveOnRight() {
        InMemoryWindowStore store = new InMemoryWindowStore();
        long start = 60_000L; // 恰好窗口边界
        store.add("s", "m", start, 1, "a", W);
        store.add("s", "m", start + W - 1, 2, "b", W);
        store.add("s", "m", start + W, 3, "c", W);

        List<WindowData> windows = store.query("s", "m", start, start + 2 * W, W);
        logWindows("right-exclusive boundary", windows);
        assertEquals(2, windows.size());
        assertEquals(2, windows.get(0).getCount());
        assertEquals(1, windows.get(1).getCount());
    }

    @Test
    void duplicatePointIdCountsOnce() {
        InMemoryWindowStore store = new InMemoryWindowStore();
        long ts = 120_000L;
        assertEquals(WindowStore.WriteStatus.ACCEPTED, store.add("s", "m", ts, 5, "dup", W));
        assertEquals(WindowStore.WriteStatus.DUPLICATE, store.add("s", "m", ts, 999, "dup", W));

        WindowData d = store.getWindow(new WindowKey("s", "m", 120_000L), W);
        logWindows("duplicate id", List.of(d));
        assertEquals(1, d.getCount());
        assertEquals(5.0, d.getSum(), 1e-9);
    }

    @Test
    void concurrentWritesLoseNoPoints() throws InterruptedException {
        InMemoryWindowStore store = new InMemoryWindowStore();
        int threads = 16;
        int perThread = 1_000;
        long ts = 300_000L;
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    store.add("s", "m", ts, 1, "id-" + (base + i), W);
                }
            });
        }
        for (Thread th : pool) th.start();
        for (Thread th : pool) th.join();

        WindowData d = store.getWindow(new WindowKey("s", "m", 300_000L), W);
        log.info("concurrent writes result: count={}", d.getCount());
        assertEquals(threads * perThread, d.getCount());
    }
}
