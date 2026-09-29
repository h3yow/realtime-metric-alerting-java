package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.model.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.model.AlertState;

/** 一次评估的结果：事件可能为 null。 */
public class AlertEvaluation {
    private final String ruleId;
    private final AlertState state;
    private final long windowStart;
    private final double value;
    private final boolean skipped; // 跳过（无数据窗口 / 乱序窗口）
    private final AlertEvent event;

    public AlertEvaluation(String ruleId, AlertState state, long windowStart,
                           double value, boolean skipped, AlertEvent event) {
        this.ruleId = ruleId;
        this.state = state;
        this.windowStart = windowStart;
        this.value = value;
        this.skipped = skipped;
        this.event = event;
    }

    public String getRuleId() { return ruleId; }
    public AlertState getState() { return state; }
    public long getWindowStart() { return windowStart; }
    public double getValue() { return value; }
    public boolean isSkipped() { return skipped; }
    public AlertEvent getEvent() { return event; }
}
