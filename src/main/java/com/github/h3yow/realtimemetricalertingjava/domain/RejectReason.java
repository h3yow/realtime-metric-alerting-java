package com.github.h3yow.realtimemetricalertingjava.domain;

/** 数据点被拒绝的原因，失败按原因区分。 */
public enum RejectReason {
    EMPTY_SERVICE,
    EMPTY_METRIC,
    ILLEGAL_TIMESTAMP,
    FUTURE_TIMESTAMP,
    EXPIRED_TIMESTAMP,
    NAN_OR_INFINITE_VALUE,
    DUPLICATE_POINT
}
