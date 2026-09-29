package com.github.h3yow.realtimemetricalertingjava.domain;

/** 告警事件类型：触发 / 恢复，各自只在独立确认条件满足时产生一次。 */
public enum AlertEventType {
    TRIGGERED,
    RECOVERED
}
