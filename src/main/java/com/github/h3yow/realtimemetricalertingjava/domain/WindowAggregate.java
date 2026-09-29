package com.github.h3yow.realtimemetricalertingjava.domain;

/** 某个 (service, metric) 在某个固定时间窗口内的聚合结果。 */
public record WindowAggregate(String service, String metric, long windowStart, long windowEnd, long count,
                              double sum, double min, double max, double avg) {
}
