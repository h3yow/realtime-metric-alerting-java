package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.model.AlertState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单条规则的告警状态机。每次评估输入一个窗口是否越限以及该窗口起点，
 * 窗口起点必须严格递增（内部防御乱序评估）。
 *
 * 状态：
 *   INACTIVE ->(连续 N 个窗口越限)         FIRING（中途任一窗口不越限则确认清零）
 *   FIRING   ->(连续 M 个窗口不越限)       INACTIVE（中途任一窗口越限则确认清零）
 * N、M 相互独立：阈值来回抖动时既不会反复触发，也不会反复恢复。
 *
 * 本类方法 synchronized：同一条规则被并发评估时状态与连续计数作为一个
 * 原子整体更新，杜绝重复告警与状态跳变。
 */
class RuleAlertState {

    private static final Logger log = LoggerFactory.getLogger(RuleAlertState.class);

    private final String ruleId;
    private AlertState state = AlertState.INACTIVE;
    private int consecutiveBreaches;
    private int consecutiveOk;
    private long lastEvaluatedWindowStart = Long.MIN_VALUE;
    private double lastValue;

    RuleAlertState(String ruleId) {
        this.ruleId = ruleId;
    }

    AlertState getState() {
        return state;
    }

    long getConsecutiveBreaches() {
        return consecutiveBreaches;
    }

    long getConsecutiveOk() {
        return consecutiveOk;
    }

    long getLastEvaluatedWindowStart() {
        return lastEvaluatedWindowStart;
    }

    double getLastValue() {
        return lastValue;
    }

    /**
     * 推进状态机。返回 FIRED / RESOLVED / null（null 表示无事件）。
     * 重复评估同一窗口起点直接返回 null（幂等）；更早的窗口直接忽略。
     */
    synchronized String evaluate(boolean breached, long windowStart, double value,
                                 int fireWindows, int resolveWindows, long now) {
        this.lastValue = value;

        // 乱序 / 重复窗口防御：状态机只按窗口时间正向推进。
        if (windowStart < lastEvaluatedWindowStart) {
            log.info("[{}] state-machine SKIP out-of-order window={} (last={}) state={}",
                    ruleId, windowStart, lastEvaluatedWindowStart, state);
            return null;
        }
        if (windowStart == lastEvaluatedWindowStart) {
            log.info("[{}] state-machine SKIP duplicate window={} state={}",
                    ruleId, windowStart, state);
            return null;
        }
        long prevWindow = lastEvaluatedWindowStart;
        lastEvaluatedWindowStart = windowStart;

        AlertState before = state;
        String event = null;

        switch (state) {
            case INACTIVE -> {
                if (breached) {
                    consecutiveBreaches = 1;
                    if (fireWindows <= 1) {
                        state = AlertState.FIRING;
                        event = "FIRED";
                    } else {
                        state = AlertState.PENDING_FIRING;
                    }
                }
            }
            case PENDING_FIRING -> {
                if (breached) {
                    consecutiveBreaches++;
                    if (consecutiveBreaches >= fireWindows) {
                        state = AlertState.FIRING;
                        event = "FIRED";
                    }
                } else {
                    // 抖动：触发确认未完成即出现正常窗口，确认计数清零，不触发。
                    consecutiveBreaches = 0;
                    state = AlertState.INACTIVE;
                }
            }
            case FIRING -> {
                if (!breached) {
                    consecutiveOk = 1;
                    if (resolveWindows <= 1) {
                        state = AlertState.INACTIVE;
                        event = "RESOLVED";
                    } else {
                        state = AlertState.PENDING_RESOLVE;
                    }
                }
            }
            case PENDING_RESOLVE -> {
                if (!breached) {
                    consecutiveOk++;
                    if (consecutiveOk >= resolveWindows) {
                        state = AlertState.INACTIVE;
                        event = "RESOLVED";
                    }
                } else {
                    // 抖动：恢复确认未完成即再次越限，确认计数清零，不产生重复告警。
                    consecutiveOk = 0;
                    state = AlertState.FIRING;
                }
            }
        }

        log.info("[{}] state-machine window={} (after {}) breached={} value={} "
                        + "transition {} -> {} breachStreak={} okStreak={} event={}",
                ruleId, windowStart, prevWindow, breached, value,
                before, state, consecutiveBreaches, consecutiveOk, event);
        return event;
    }
}
