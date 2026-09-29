package com.github.h3yow.realtimemetricalertingjava.domain;

/** 告警状态机的状态。 */
public enum AlertState {
    /** 正常（未告警），需要连续 breachForN 次超阈值才进入告警。 */
    NORMAL,
    /** 告警中，需要连续 recoverForN 次回到阈值之下才恢复。 */
    FIRING
}
