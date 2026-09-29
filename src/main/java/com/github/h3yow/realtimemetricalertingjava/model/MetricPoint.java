package com.github.h3yow.realtimemetricalertingjava.model;

/** 单个指标数据点，timestamp 为 epoch millis。id 为幂等键（可空，由调用方提供）。 */
public class MetricPoint {
    private String id;
    private String service;
    private String metricName;
    private double value;
    private long timestamp;

    public MetricPoint() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
