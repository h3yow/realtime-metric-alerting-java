package com.github.h3yow.realtimemetricalertingjava.domain;

/**
 * 单条指标数据点。
 *
 * @param service   服务维度标识
 * @param metric    指标名称
 * @param timestamp 实际发生时间（epoch millis）
 * @param value     指标值
 * @param batchId   提交批次号，用于批次幂等（可为空）
 * @param pointId   数据点唯一标识，用于窗口内去重（可为空）
 */
public record MetricPoint(String service, String metric, long timestamp, double value, String batchId,
                          String pointId) {
}
