package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 (service, metric) 序列维护窗口桶的内存存储。
 * <ul>
 *   <li>数据按实际 timestamp 经 {@link WindowSpec} 对齐到窗口，迟到/乱序数据直接进入历史窗口，
 *       绝不会与其它时间点的数据混入同一窗口；</li>
 *   <li>每个序列一把锁、序列之间完全并发，桶内计数在锁内更新，高并发读写不丢数、不串桶；</li>
 *   <li>每个序列最多保留 maxBucketsPerSeries 个窗口，超出后淘汰最老窗口。</li>
 * </ul>
 */
public final class WindowStore {

    private static final Logger log = LoggerFactory.getLogger(WindowStore.class);

    private final WindowSpec spec;
    private final int maxBucketsPerSeries;

    /** 每个序列的窗口桶，key 为窗口起始时间。 */
    private final Map<String, TreeMap<Long, WindowBucket>> series = new ConcurrentHashMap<>();
    /** 每个序列一把分段锁，避免全局锁竞争。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public WindowStore(WindowSpec spec, int maxBucketsPerSeries) {
        if (maxBucketsPerSeries <= 0) {
            throw new IllegalArgumentException("maxBucketsPerSeries must be positive");
        }
        this.spec = spec;
        this.maxBucketsPerSeries = maxBucketsPerSeries;
    }

    public WindowSpec spec() {
        return spec;
    }

    private static String seriesKey(String service, String metric) {
        return service + '|' + metric;
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 将值加入 timestamp 实际所属的窗口。
     *
     * @param pointKey 非空时在窗口内去重
     * @return true 表示真正计入；false 表示重复点
     */
    public boolean add(String service, String metric, long timestamp, double value, String pointKey) {
        long start = spec.windowStartFor(timestamp);
        long end = spec.windowEndFor(start);
        String key = seriesKey(service, metric);
        synchronized (lockFor(key)) {
            TreeMap<Long, WindowBucket> buckets =
                    series.computeIfAbsent(key, k -> new TreeMap<>());
            WindowBucket bucket = buckets.get(start);
            if (bucket == null) {
                bucket = new WindowBucket(start);
                buckets.put(start, bucket);
                log.info("[window] open series={} metric={} window=[{},{}) (timestamp={} lands here)",
                        service, metric, start, end, timestamp);
                while (buckets.size() > maxBucketsPerSeries) {
                    Map.Entry<Long, WindowBucket> oldest = buckets.pollFirstEntry();
                    log.info("[window] evict series={} metric={} oldest window=[{},{}) count={}",
                            service, metric, oldest.getKey(),
                            spec.windowEndFor(oldest.getKey()), oldest.getValue().count());
                }
            }
            boolean accepted = bucket.add(value, pointKey);
            if (!accepted) {
                log.info("[window] duplicate point ignored series={} metric={} window=[{},{}) pointKey={}",
                        service, metric, start, end, pointKey);
            }
            return accepted;
        }
    }

    /**
     * 查询时间范围 [fromMillis, toMillis] 内与窗口有交集的全部窗口聚合，按窗口起始升序。
     */
    public List<WindowAggregate> query(String service, String metric, long fromMillis, long toMillis) {
        if (toMillis <= fromMillis) {
            return List.of();
        }
        String key = seriesKey(service, metric);
        synchronized (lockFor(key)) {
            TreeMap<Long, WindowBucket> buckets = series.get(key);
            if (buckets == null || buckets.isEmpty()) {
                return List.of();
            }
            // 起点对齐到 from 所在窗口；窗口左闭右开，start >= toMillis 即停止
            long firstStart = spec.windowStartFor(fromMillis);
            List<WindowAggregate> result = new ArrayList<>();
            for (Map.Entry<Long, WindowBucket> e : buckets.tailMap(firstStart, true).entrySet()) {
                long start = e.getKey();
                if (start >= toMillis) {
                    break;
                }
                result.add(e.getValue().toAggregate(service, metric, spec.windowEndFor(start)));
            }
            log.info("[window] query series={} metric={} range=[{},{}) -> {} windows",
                    service, metric, fromMillis, toMillis, result.size());
            return result;
        }
    }

    /** 取 timestamp 所属窗口的聚合；窗口尚不存在时返回空。 */
    public Optional<WindowAggregate> aggregateAt(String service, String metric, long timestamp) {
        long start = spec.windowStartFor(timestamp);
        String key = seriesKey(service, metric);
        synchronized (lockFor(key)) {
            TreeMap<Long, WindowBucket> buckets = series.get(key);
            if (buckets == null) {
                return Optional.empty();
            }
            WindowBucket bucket = buckets.get(start);
            if (bucket == null) {
                return Optional.empty();
            }
            return Optional.of(bucket.toAggregate(service, metric, spec.windowEndFor(start)));
        }
    }
}
