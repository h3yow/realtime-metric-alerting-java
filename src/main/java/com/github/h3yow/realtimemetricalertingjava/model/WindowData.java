package com.github.h3yow.realtimemetricalertingjava.model;

/** 一个窗口内的聚合结果。 */
public class WindowData {
    private String service;
    private String metricName;
    private long windowStart;
    private long windowEnd;
    private long count;
    private double sum;
    private double min;
    private double max;

    public WindowData() {
    }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }
    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long windowEnd) { this.windowEnd = windowEnd; }
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
    public double getSum() { return sum; }
    public void setSum(double sum) { this.sum = sum; }
    public double getMin() { return min; }
    public void setMin(double min) { this.min = min; }
    public double getMax() { return max; }
    public void setMax(double max) { this.max = max; }

    public double getAverage() {
        return count == 0 ? 0.0 : sum / count;
    }
}
