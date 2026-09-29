package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单个窗口的累加聚合。并发写入由 WindowStore 对同一 WindowKey 加锁保护，
 * 因此本类自身不做同步。
 */
class WindowAggregate {
    private long count;
    private double sum;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private final Set<String> pointIds = new LinkedHashSet<>();

    /** 返回 true 表示首次写入；false 表示幂等重复。pointId 为 null 时不去重。 */
    boolean add(double value, String pointId) {
        if (pointId != null && !pointIds.add(pointId)) {
            return false;
        }
        count++;
        sum += value;
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
        return true;
    }

    boolean containsPoint(String pointId) {
        return pointId != null && pointIds.contains(pointId);
    }

    long getCount() {
        return count;
    }

    WindowData toWindowData(String service, String metricName,
                            long windowStart, long windowMillis) {
        WindowData d = new WindowData();
        d.setService(service);
        d.setMetricName(metricName);
        d.setWindowStart(windowStart);
        d.setWindowEnd(windowStart + windowMillis);
        d.setCount(count);
        d.setSum(sum);
        d.setMin(count == 0 ? 0.0 : min);
        d.setMax(count == 0 ? 0.0 : max);
        return d;
    }
}
