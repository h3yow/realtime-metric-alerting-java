package com.github.h3yow.realtimemetricalertingjava.window;

/**
 * 固定滚动窗口（tumbling window）规格，窗口边界按 epoch 0 对齐：
 * 第 n 个窗口覆盖时间区间 [n * sizeMillis, (n+1) * sizeMillis)，左闭右开。
 * 因此无论数据何时到达，都只会按“实际发生时间”落到唯一窗口，
 * 乱序或迟到的数据天然与同窗口的其它数据聚合在一起。
 */
public final class WindowSpec {

    private final long sizeMillis;
    private final long allowedLatenessMillis;

    public WindowSpec(long sizeMillis, long allowedLatenessMillis) {
        if (sizeMillis <= 0) {
            throw new IllegalArgumentException("window size must be positive");
        }
        if (allowedLatenessMillis < 0) {
            throw new IllegalArgumentException("allowed lateness must be non-negative");
        }
        this.sizeMillis = sizeMillis;
        this.allowedLatenessMillis = allowedLatenessMillis;
    }

    public long sizeMillis() {
        return sizeMillis;
    }

    public long allowedLatenessMillis() {
        return allowedLatenessMillis;
    }

    /** 返回 timestamp 实际所属窗口的起始时间（按 epoch 对齐的向下取整边界）。 */
    public long windowStartFor(long timestamp) {
        // Math.floorDiv 保证 epoch 之前的负数时间也能正确向下取整
        return Math.floorDiv(timestamp, sizeMillis) * sizeMillis;
    }

    /** 返回窗口的结束边界（不含）。 */
    public long windowEndFor(long windowStart) {
        return windowStart + sizeMillis;
    }
}
