package com.github.h3yow.realtimemetricalertingjava.model;

import java.util.Objects;

/** 窗口唯一键：服务 + 指标名 + 窗口起点（epoch millis，已对齐）。 */
public final class WindowKey {
    private final String service;
    private final String metricName;
    private final long windowStart;

    public WindowKey(String service, String metricName, long windowStart) {
        this.service = service;
        this.metricName = metricName;
        this.windowStart = windowStart;
    }

    public String getService() { return service; }
    public String getMetricName() { return metricName; }
    public long getWindowStart() { return windowStart; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WindowKey other)) return false;
        return windowStart == other.windowStart
                && Objects.equals(service, other.service)
                && Objects.equals(metricName, other.metricName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, metricName, windowStart);
    }

    @Override
    public String toString() {
        return "WindowKey{" + service + "/" + metricName + " @" + windowStart + "}";
    }
}
