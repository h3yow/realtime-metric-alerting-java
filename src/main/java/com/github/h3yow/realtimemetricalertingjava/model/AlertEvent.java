package com.github.h3yow.realtimemetricalertingjava.model;

/** 状态机真正完成触发/恢复时产生的事件。 */
public class AlertEvent {
    private String ruleId;
    private String service;
    private String metricName;
    private String type; // FIRED / RESOLVED
    private AlertState state;
    private long windowStart;
    private double value;
    private long eventTime;

    public AlertEvent() {
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public AlertState getState() { return state; }
    public void setState(AlertState state) { this.state = state; }
    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public long getEventTime() { return eventTime; }
    public void setEventTime(long eventTime) { this.eventTime = eventTime; }
}
