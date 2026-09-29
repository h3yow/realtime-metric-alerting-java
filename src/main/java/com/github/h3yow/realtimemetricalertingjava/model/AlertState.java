package com.github.h3yow.realtimemetricalertingjava.model;

/**
 * 告警状态机四态：
 * INACTIVE         正常（未触发）
 * PENDING_FIRING   出现越限窗口，触发确认中（连续越限计数未达标）
 * FIRING           已触发
 * PENDING_RESOLVE  出现恢复窗口，恢复确认中（连续不越限计数未达标）
 */
public enum AlertState {
    INACTIVE,
    PENDING_FIRING,
    FIRING,
    PENDING_RESOLVE
}
