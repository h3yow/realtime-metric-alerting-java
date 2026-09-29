package com.github.h3yow.realtimemetricalertingjava.web;

/** 写入接口的单个数据点请求体。 */
public record PointDto(String service, String metric, Long timestamp, Double value, String pointId) {
}
