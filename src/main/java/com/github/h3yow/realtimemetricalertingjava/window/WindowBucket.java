package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;

import java.util.HashSet;
import java.util.Set;

/**
 * 单个窗口内的可变聚合状态：维护 count/sum/min/max 以及已见过的数据点键。
 * 所有方法只在 WindowStore 持有该序列分段锁的期间被调用，自身不再加锁。
 */
public final class WindowBucket {

    private final long windowStart;
    private long count;
    private double sum;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private final Set<String> pointKeys = new HashSet<>();

    public WindowBucket(long windowStart) {
        this.windowStart = windowStart;
    }

    /**
     * 向窗口写入一个值。
     *
     * @param value    指标值
     * @param pointKey 数据点唯一键（service|metric|pointId|windowStart），
     *                 非空时用于窗口内幂等去重
     * @return true 表示真正计入；false 表示重复数据点被忽略
     */
    public boolean add(double value, String pointKey) {
        if (pointKey != null && !pointKeys.add(pointKey)) {
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

    public long count() {
        return count;
    }

    public long windowStart() {
        return windowStart;
    }

    public WindowAggregate toAggregate(String service, String metric, long windowEnd) {
        double avg = count == 0 ? 0.0 : sum / count;
        return new WindowAggregate(service, metric, windowStart, windowEnd, count, sum, min, max, avg);
    }
}
