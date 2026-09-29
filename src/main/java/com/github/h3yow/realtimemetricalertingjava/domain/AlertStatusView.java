package com.github.h3yow.realtimemetricalertingjava.domain;

/** 某条规则当前的告警状态视图。 */
public record AlertStatusView(String ruleId, AlertState state, int consecutiveBreaches,
                              int consecutiveRecoveries, Double lastValue, Long lastWindowStart,
                              long version) {
}
