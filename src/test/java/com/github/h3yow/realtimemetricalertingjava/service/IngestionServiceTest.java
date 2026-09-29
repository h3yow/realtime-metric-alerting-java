package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.domain.IngestResult;
import com.github.h3yow.realtimemetricalertingjava.domain.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.domain.RejectReason;
import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import com.github.h3yow.realtimemetricalertingjava.window.WindowSpec;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IngestionServiceTest.class);

    private static final long NOW = 1_000_000L;
    private static final long WINDOW = 10_000L;

    private IngestionService newService(WindowStore store) {
        // 未来容忍 1s，最大年龄 100s
        return new IngestionService(store, 60_000L, 100_000L);
    }

    private MetricPoint p(String svc, String metric, long ts, double v, String batchId, String pointId) {
        return new MetricPoint(svc, metric, ts, v, batchId, pointId);
    }

    private Map<RejectReason, Integer> countByReason(IngestResult r) {
        Map<RejectReason, Integer> map = new EnumMap<>(RejectReason.class);
        r.rejected().forEach(rp -> map.merge(rp.reason(), 1, Integer::sum));
        return map;
    }

    @Test
    void rejectsIllegalPointsByDistinctReason() {
        WindowStore store = new WindowStore(new WindowSpec(WINDOW, 0L), 10);
        IngestionService svc = newService(store);

        List<MetricPoint> batch = List.of(
                p("", "qps", NOW, 1, null, null),
                p("svc", "", NOW, 1, null, null),
                p("svc", "qps", 0L, 1, null, null),
                p("svc", "qps", NOW + 100_000L, 1, null, null),  // 未来时间（超出 60s 容忍）
                p("svc", "qps", NOW - 200_000L, 1, null, null),  // 明显过期
                p("svc", "qps", NOW, Double.NaN, null, null),
                p("svc", "qps", NOW, 1.0, null, null)            // 唯一合法点
        );
        IngestResult r = svc.ingest(batch, NOW);
        Map<RejectReason, Integer> byReason = countByReason(r);
        byReason.forEach((reason, cnt) ->
                log.info("[test-ingest] reject reason={} count={}", reason, cnt));

        assertEquals(1, r.acceptedCount());
        assertEquals(1, byReason.get(RejectReason.EMPTY_SERVICE));
        assertEquals(1, byReason.get(RejectReason.EMPTY_METRIC));
        assertEquals(1, byReason.get(RejectReason.ILLEGAL_TIMESTAMP));
        assertEquals(1, byReason.get(RejectReason.FUTURE_TIMESTAMP));
        assertEquals(1, byReason.get(RejectReason.EXPIRED_TIMESTAMP));
        assertEquals(1, byReason.get(RejectReason.NAN_OR_INFINITE_VALUE));
    }

    @Test
    void duplicateBatchSubmissionIsEquivalentToSingleSubmission() {
        WindowStore store = new WindowStore(new WindowSpec(WINDOW, 0L), 10);
        IngestionService svc = newService(store);

        List<MetricPoint> batch = List.of(
                p("svc", "qps", NOW, 10, "batch-1", "p1"),
                p("svc", "qps", NOW + 1_000L, 20, "batch-1", "p2"),
                p("svc", "qps", NOW - 5_000L, 30, "batch-1", "p3"));

        IngestResult first = svc.ingest(batch, NOW);
        IngestResult second = svc.ingest(batch, NOW);
        IngestResult third = svc.ingest(batch, NOW);

        log.info("[test-ingest] first accepted={}, duplicate submissions accepted={},{}",
                first.acceptedCount(), second.acceptedCount(), third.acceptedCount());

        assertEquals(3, first.acceptedCount());
        assertFalse(first.duplicate());
        assertEquals(first.acceptedCount(), second.acceptedCount());
        assertEquals(first.rejected().size(), second.rejected().size());
        assertTrue(second.duplicate() && third.duplicate(), "重复批次必须标记为 duplicate");

        WindowAggregate w = store.aggregateAt("svc", "qps", NOW).orElseThrow();
        log.info("[test-ingest] window=[{},{}) count={} sum={}",
                w.windowStart(), w.windowEnd(), w.count(), w.sum());
        // p1,p2 同窗口，p3 在上一窗口；重复提交后计数与只提交一次一致
        assertEquals(2L, w.count());
        assertEquals(30.0, w.sum(), 0.0001);
        WindowAggregate older = store.aggregateAt("svc", "qps", NOW - 5_000L).orElseThrow();
        assertEquals(1L, older.count());
        assertEquals(30.0, older.sum(), 0.0001);
    }

    @Test
    void duplicatePointIdsAcrossBatchesDoNotDoubleCount() {
        WindowStore store = new WindowStore(new WindowSpec(WINDOW, 0L), 10);
        IngestionService svc = newService(store);

        svc.ingest(List.of(p("svc", "qps", NOW, 10, "a", "k1")), NOW);
        // 不同批次但相同 pointId、同一窗口：必须拒绝为 DUPLICATE_POINT
        IngestResult r = svc.ingest(List.of(p("svc", "qps", NOW + 2_000L, 99, "b", "k1")), NOW);
        assertEquals(0, r.acceptedCount());
        assertEquals(RejectReason.DUPLICATE_POINT, r.rejected().get(0).reason());

        WindowAggregate w = store.aggregateAt("svc", "qps", NOW).orElseThrow();
        assertEquals(1L, w.count());
        assertEquals(10.0, w.sum(), 0.0001);
        log.info("[test-ingest] cross-batch same pointId -> count={} sum={}", w.count(), w.sum());
    }

    @Test
    void concurrentDuplicateBatchSubmissionsCountOnce() throws Exception {
        WindowStore store = new WindowStore(new WindowSpec(WINDOW, 0L), 10);
        IngestionService svc = newService(store);

        int threads = 12;
        List<MetricPoint> batch = List.of(
                p("svc", "qps", NOW, 1, "hot-batch", "x1"),
                p("svc", "qps", NOW + 1_000L, 1, "hot-batch", "x2"),
                p("svc", "qps", NOW + 2_000L, 1, "hot-batch", "x3"));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger firstSeen = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IngestResult r = svc.ingest(batch, NOW);
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    } else {
                        firstSeen.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[test-ingest] concurrent same-batch: firstSeen={} duplicates={}",
                firstSeen.get(), duplicates.get());
        assertEquals(1, firstSeen.get(), "并发重复提交只能有一个线程真正写入");
        assertEquals(threads - 1, duplicates.get());
        WindowAggregate w = store.aggregateAt("svc", "qps", NOW).orElseThrow();
        assertEquals(3L, w.count(), "窗口计数必须与单次提交一致");
    }
}
