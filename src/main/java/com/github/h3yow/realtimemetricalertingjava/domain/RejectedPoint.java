package com.github.h3yow.realtimemetricalertingjava.domain;

/** 被拒绝的数据点及其原因。 */
public record RejectedPoint(MetricPoint point, RejectReason reason, String detail) {
}
