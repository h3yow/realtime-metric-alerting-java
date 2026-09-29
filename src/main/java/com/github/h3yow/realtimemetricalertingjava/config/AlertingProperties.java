package com.github.h3yow.realtimemetricalertingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 本地可配置项：窗口大小、数据保留时长、允许的未来/迟到时间。 */
@Component
@ConfigurationProperties(prefix = "alerting")
public class AlertingProperties {

    private long windowMillis = 60_000L;
    private long retentionMillis = 3_600_000L;
    /** 超过当前时间多少毫秒视为未来数据（拒绝）。 */
    private long maxFutureMillis = 5_000L;
    /** 早于当前时间多久视为明显过期（拒绝）。 */
    private long maxLateMillis = 3_600_000L;
    private boolean autoEvaluate = true;

    public long getWindowMillis() { return windowMillis; }
    public void setWindowMillis(long v) { this.windowMillis = v; }
    public long getRetentionMillis() { return retentionMillis; }
    public void setRetentionMillis(long v) { this.retentionMillis = v; }
    public long getMaxFutureMillis() { return maxFutureMillis; }
    public void setMaxFutureMillis(long v) { this.maxFutureMillis = v; }
    public long getMaxLateMillis() { return maxLateMillis; }
    public void setMaxLateMillis(long v) { this.maxLateMillis = v; }
    public boolean isAutoEvaluate() { return autoEvaluate; }
    public void setAutoEvaluate(boolean v) { this.autoEvaluate = v; }
}
