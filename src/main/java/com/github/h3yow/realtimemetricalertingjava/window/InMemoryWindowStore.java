package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.model.WindowKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 纯内存窗口存储，无外部依赖。
 * 按 WindowKey 分段加锁（锁聚合对象本身），不同窗口可并发写入，
 * 同一窗口的写入串行化，保证高并发下不丢点、不重复计数。
 */
@Component
public class InMemoryWindowStore implements WindowStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryWindowStore.class);

    private final Map<WindowKey, WindowAggregate> windows = new ConcurrentHashMap<>();

    static long alignWindowStart(long timestamp, long windowMillis) {
        return Math.floorDiv(timestamp, windowMillis) * windowMillis;
    }

    @Override
    public WriteStatus add(String service, String metricName, long timestamp,
                           double value, String pointId, long windowMillis) {
        long windowStart = alignWindowStart(timestamp, windowMillis);
        WindowKey key = new WindowKey(service, metricName, windowStart);
        WindowAggregate aggregate = windows.computeIfAbsent(key, k -> new WindowAggregate());
        boolean firstWrite;
        synchronized (aggregate) {
            firstWrite = aggregate.add(value, pointId);
        }
        if (firstWrite) {
            log.debug("window write service={} metric={} window=[{} - {}) value={} pointId={} -> count={}",
                    service, metricName, windowStart, windowStart + windowMillis,
                    value, pointId, aggregate.getCount());
            return WriteStatus.ACCEPTED;
        }
        log.debug("duplicate point ignored service={} metric={} window=[{} - {}) pointId={}",
                service, metricName, windowStart, windowStart + windowMillis, pointId);
        return WriteStatus.DUPLICATE;
    }

    @Override
    public List<WindowData> query(String service, String metricName,
                                  long from, long to, long windowMillis) {
        List<WindowData> result = new ArrayList<>();
        if (from >= to) {
            return result;
        }
        // 查询边界同样对齐：覆盖到数据实际落入的窗口，绝不把不同时间点混进同一窗口。
        long start = alignWindowStart(from, windowMillis);
        long end = alignWindowStart(to - 1, windowMillis);
        for (long w = start; w <= end; w += windowMillis) {
            WindowKey key = new WindowKey(service, metricName, w);
            WindowAggregate aggregate = windows.get(key);
            if (aggregate != null) {
                synchronized (aggregate) {
                    result.add(aggregate.toWindowData(service, metricName, w, windowMillis));
                }
            }
        }
        return result;
    }

    @Override
    public WindowData getWindow(WindowKey key, long windowMillis) {
        WindowAggregate aggregate = windows.get(key);
        if (aggregate == null) {
            return null;
        }
        synchronized (aggregate) {
            return aggregate.toWindowData(key.getService(), key.getMetricName(),
                    key.getWindowStart(), windowMillis);
        }
    }

    @Override
    public void purgeBefore(long cutoffMillis, long windowMillis) {
        long cutoffWindow = alignWindowStart(cutoffMillis, windowMillis);
        int removed = 0;
        for (WindowKey key : windows.keySet()) {
            if (key.getWindowStart() < cutoffWindow && windows.remove(key) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("purged {} windows before {}", removed, cutoffWindow);
        }
    }
}
