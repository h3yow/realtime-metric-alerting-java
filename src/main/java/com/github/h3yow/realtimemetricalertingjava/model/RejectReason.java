package com.github.h3yow.realtimemetricalertingjava.model;

/** 数据被拒绝的具体原因，便于失败按原因区分。 */
public enum RejectReason {
    INVALID_SERVICE,
    INVALID_METRIC_NAME,
    INVALID_VALUE,
    INVALID_TIMESTAMP,
    FUTURE_TIMESTAMP,
    EXPIRED_TIMESTAMP,
    DUPLICATE_POINT
}
