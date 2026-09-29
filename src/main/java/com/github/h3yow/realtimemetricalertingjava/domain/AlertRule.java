package com.github.h3yow.realtimemetricalertingjava.domain;

/**
 * 告警规则。
 *
 * @param id            规则唯一标识
 * @param service       关联服务
 * @param metric        关联指标
 * @param threshold     阈值
 * @param breachForN    触发确认：连续 N 次评估超过阈值才触发
 * @param recoverForN   恢复确认：连续 N 次评估不超过阈值才恢复
 */
public record AlertRule(String id, String service, String metric, double threshold, int breachForN,
                        int recoverForN) {
}
