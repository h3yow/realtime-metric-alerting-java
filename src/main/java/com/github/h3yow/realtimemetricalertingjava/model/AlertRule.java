package com.github.h3yow.realtimemetricalertingjava.model;

/**
 * 告警规则：按 service + metricName 的窗口聚合值与阈值比较。
 * fireConsecutiveWindows / resolveConsecutiveWindows 分别是触发和恢复需要的
 * 独立连续确认窗口数，阈值来回抖动时不会反复触发/恢复。
 */
public class AlertRule {
    private String id;
    private String service;
    private String metricName;
    private String operator; // GT / GTE
    private double threshold;
    private int fireConsecutiveWindows;
    private int resolveConsecutiveWindows;
    private long windowMillis;

    public AlertRule() {
    }

    public boolean breached(double value) {
        if ("GTE".equalsIgnoreCase(operator)) {
            return value >= threshold;
        }
        // 默认 GT
        return value > threshold;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public int getFireConsecutiveWindows() { return fireConsecutiveWindows; }
    public void setFireConsecutiveWindows(int n) { this.fireConsecutiveWindows = n; }
    public int getResolveConsecutiveWindows() { return resolveConsecutiveWindows; }
    public void setResolveConsecutiveWindows(int n) { this.resolveConsecutiveWindows = n; }
    public long getWindowMillis() { return windowMillis; }
    public void setWindowMillis(long windowMillis) { this.windowMillis = windowMillis; }
}
