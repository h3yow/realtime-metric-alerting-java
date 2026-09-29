package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 乱序/迟到数据窗口归属、窗口内去重、并发写入不丢数。 */
class WindowStoreTest {

    private static final Logger log = LoggerFactory.getLogger(WindowStoreTest.class);

    private final WindowSpec spec = new WindowSpec(10_000L, 0L);

    @Test
    void lateAndOutOfOrderDataLandsInActualWindow() {
        WindowStore store = new WindowStore(spec, 100);

        // 先写较新窗口，再补写更老窗口（迟到数据）
        store.add("svc", "qps", 25_000L, 30, null);
        store.add("svc", "qps", 28_000L, 40, null);
        store.add("svc", "qps", 2_000L, 10, null);   // 迟到：属于 [0,10000)
        store.add("svc", "qps", 9_999L, 20, null);   // 乱序：仍属于 [0,10000)
        store.add("svc", "qps", 10_000L, 50, null);  // 边界点必须进入下一窗口

        List<WindowAggregate> windows = store.query("svc", "qps", 0L, 30_000L);
        for (WindowAggregate w : windows) {
            log.info("[test-window] window=[{},{}) count={} sum={} avg={}",
                    w.windowStart(), w.windowEnd(), w.count(), w.sum(), w.avg());
        }

        assertEquals(3, windows.size(), "应有三个不同窗口，迟到数据不能串窗口");
        assertEquals(2L, windows.get(0).count());
        assertEquals(30.0, windows.get(0).sum(), 0.0001);
        assertEquals(1L, windows.get(1).count());
        assertEquals(50.0, windows.get(1).sum(), 0.0001);
        assertEquals(2L, windows.get(2).count());
        assertEquals(70.0, windows.get(2).sum(), 0.0001);
    }

    @Test
    void samePointIdWithinWindowIsDeduplicated() {
        WindowStore store = new WindowStore(spec, 10);
        assertTrue(store.add("svc", "qps", 1_000L, 1, "svc|qps|p1"));
        // 同一 pointId 在同窗口重复提交：忽略；不同窗口各自独立
        assertEquals(false, store.add("svc", "qps", 2_000L, 1, "svc|qps|p1"));
        assertTrue(store.add("svc", "qps", 11_000L, 1, "svc|qps|p1"));
        // 不同 pointId 正常计入
        assertTrue(store.add("svc", "qps", 3_000L, 1, "svc|qps|p2"));

        WindowAggregate w0 = store.aggregateAt("svc", "qps", 0L).orElseThrow();
        assertEquals(2L, w0.count(), "重复 pointId 在同一窗口只能计一次");
        log.info("[test-window] dedup window=[0,10000) count={}", w0.count());
    }

    @Test
    void concurrentWritesAndQueriesLoseNothing() throws Exception {
        WindowStore store = new WindowStore(spec, 100);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 全部落在 [0,10000)
                        store.add("svc", "qps", 1_000L + (base + i) % 8_000L, 1, null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        // 并发热身查询
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    store.query("svc", "qps", 0L, 10_000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.start();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        reader.join();
        pool.shutdown();

        WindowAggregate w = store.aggregateAt("svc", "qps", 0L).orElseThrow();
        log.info("[test-window] concurrent window=[0,10000) expected={} actualCount={}",
                threads * perThread, w.count());
        assertEquals(threads * perThread, w.count(), "并发写入不能丢数据或算错计数");
    }
}
