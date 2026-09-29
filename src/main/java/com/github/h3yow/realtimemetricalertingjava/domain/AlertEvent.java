package com.github.h3yow.realtimemetricalertingjava.domain;

/** 一次告警状态流转产生的不可变事件。 */
public record AlertEvent(String ruleId, String service, String metric, AlertEventType type, long windowStart,
                         double value, double threshold, long evaluatedAtMillis) {
}
