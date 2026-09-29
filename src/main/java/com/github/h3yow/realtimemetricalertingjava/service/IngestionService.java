package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.domain.IngestResult;
import com.github.h3yow.realtimemetricalertingjava.domain.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.domain.RejectedPoint;
import com.github.h3yow.realtimemetricalertingjava.domain.RejectReason;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标写入服务。
 * <ul>
 *   <li>逐点校验：空 service/metric、非法时间、未来时间、明显过期、NaN/无穷大一律拒绝，
 *       拒绝结果带 {@link RejectReason}，失败可按原因区分；</li>
 *   <li>合法点按实际 timestamp 落入对应窗口，乱序/迟到数据由 WindowStore 路由到历史窗口；</li>
 *   <li>幂等两层：批次级（相同 batchId 只生效一次）+ 点级（相同 pointId 不会在同一窗口重复计数）。</li>
 * </ul>
 */
public final class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WindowStore store;
    private final long maxFutureMillis;
    private final long maxAgeMillis;

    /** 已处理批次的结果缓存：batchId -> 结果，保证重复提交与只提交一次完全一致。 */
    private final Map<String, IngestResult> processedBatches = new ConcurrentHashMap<>();
    /** 同一批次并发提交时在此锁上排队，防止两个线程同时“首次处理”。 */
    private final Map<String, Object> batchLocks = new ConcurrentHashMap<>();

    public IngestionService(WindowStore store) {
        this(store, 60_000L, 24L * 60 * 60 * 1000);
    }

    public IngestionService(WindowStore store, long maxFutureMillis, long maxAgeMillis) {
        if (maxFutureMillis < 0 || maxAgeMillis <= 0) {
            throw new IllegalArgumentException("invalid time tolerance");
        }
        this.store = store;
        this.maxFutureMillis = maxFutureMillis;
        this.maxAgeMillis = maxAgeMillis;
    }

    /**
     * @param points          数据点批次
     * @param nowMillis       当前时间（可注入，便于测试）
     */
    public IngestResult ingest(List<MetricPoint> points, long nowMillis) {
        if (points == null || points.isEmpty()) {
            return new IngestResult(0, List.of(), false, List.of());
        }
        String batchId = points.get(0).batchId();
        if (batchId != null && !batchId.isBlank()) {
            IngestResult cached = processedBatches.get(batchId);
            if (cached != null) {
                log.info("[ingest] batch={} duplicate submission, replay cached result accepted={} rejected={}",
                        batchId, cached.acceptedCount(), cached.rejected().size());
                return asDuplicate(cached);
            }
            Object lock = batchLocks.computeIfAbsent(batchId, k -> new Object());
            synchronized (lock) {
                try {
                    IngestResult cachedAgain = processedBatches.get(batchId);
                    if (cachedAgain != null) {
                        log.info("[ingest] batch={} duplicate submission (after lock), replay cached result",
                                batchId);
                        return asDuplicate(cachedAgain);
                    }
                    IngestResult result = doIngest(points, nowMillis);
                    processedBatches.put(batchId, result);
                    return result;
                } finally {
                    batchLocks.remove(batchId);
                }
            }
        }
        return doIngest(points, nowMillis);
    }

    /** 重复批次回放：结果数据与首次一致，仅把 duplicate 标记置为 true。 */
    private static IngestResult asDuplicate(IngestResult cached) {
        return new IngestResult(cached.acceptedCount(), cached.rejected(), true, cached.windowStarts());
    }

    private IngestResult doIngest(List<MetricPoint> points, long nowMillis) {
        int accepted = 0;
        List<RejectedPoint> rejected = new ArrayList<>();
        List<Long> windowStarts = new ArrayList<>();

        for (MetricPoint p : points) {
            RejectReason reason = validate(p, nowMillis);
            if (reason != null) {
                rejected.add(new RejectedPoint(p, reason, reason.name()));
                log.warn("[ingest] reject service={} metric={} ts={} reason={}",
                        p.service(), p.metric(), p.timestamp(), reason);
                continue;
            }
            String pointKey = (p.pointId() == null || p.pointId().isBlank())
                    ? null
                    : p.service() + '|' + p.metric() + '|' + p.pointId();
            long start = store.spec().windowStartFor(p.timestamp());
            boolean added = store.add(p.service(), p.metric(), p.timestamp(), p.value(), pointKey);
            if (added) {
                accepted++;
                if (!windowStarts.contains(start)) {
                    windowStarts.add(start);
                }
                continue;
            }
            // 窗口内已存在相同 pointId：计为重复拒绝，不改变任何计数
            rejected.add(new RejectedPoint(p, RejectReason.DUPLICATE_POINT,
                    "pointId already present in target window"));
            log.warn("[ingest] reject service={} metric={} ts={} reason=DUPLICATE_POINT pointId={}",
                    p.service(), p.metric(), p.timestamp(), p.pointId());
        }
        return new IngestResult(accepted, List.copyOf(rejected), false, List.copyOf(windowStarts));
    }

    private RejectReason validate(MetricPoint p, long nowMillis) {
        if (p.service() == null || p.service().isBlank()) {
            return RejectReason.EMPTY_SERVICE;
        }
        if (p.metric() == null || p.metric().isBlank()) {
            return RejectReason.EMPTY_METRIC;
        }
        long ts = p.timestamp();
        if (ts <= 0) {
            return RejectReason.ILLEGAL_TIMESTAMP;
        }
        if (ts > nowMillis + maxFutureMillis) {
            return RejectReason.FUTURE_TIMESTAMP;
        }
        if (ts < nowMillis - maxAgeMillis) {
            return RejectReason.EXPIRED_TIMESTAMP;
        }
        if (Double.isNaN(p.value()) || Double.isInfinite(p.value())) {
            return RejectReason.NAN_OR_INFINITE_VALUE;
        }
        return null;
    }
}
