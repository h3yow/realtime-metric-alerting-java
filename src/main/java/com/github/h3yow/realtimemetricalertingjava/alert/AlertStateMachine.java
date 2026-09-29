package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.domain.AlertEventType;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单条规则的告警状态机，仅在 {@link AlertEngine} 的规则级锁内访问，因此自身无需同步。
 * <p>
 * 状态流转（阈值用严格大于表示 breach）：
 * <pre>
 *   NORMAL -- 连续 breachForN 次 breach --> FIRING（产生一次 TRIGGERED）
 *   FIRING -- 连续 recoverForN 次非 breach --> NORMAL（产生一次 RECOVERED）
 * </pre>
 * 两侧计数器互相独立：阈值附近来回抖动时，只要未连续达到各自的确认次数，
 * 状态就保持不变，杜绝“反复触发/恢复”。
 */
public final class AlertStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AlertStateMachine.class);

    private final String ruleId;
    private final int breachForN;
    private final int recoverForN;

    private AlertState state = AlertState.NORMAL;
    private int consecutiveBreaches;
    private int consecutiveRecoveries;
    private long version;
    private Double lastValue;
    private Long lastWindowStart;

    public AlertStateMachine(String ruleId, int breachForN, int recoverForN) {
        if (breachForN <= 0 || recoverForN <= 0) {
            throw new IllegalArgumentException("confirmation counts must be positive");
        }
        this.ruleId = ruleId;
        this.breachForN = breachForN;
        this.recoverForN = recoverForN;
    }

    public AlertState state() {
        return state;
    }

    public int consecutiveBreaches() {
        return consecutiveBreaches;
    }

    public int consecutiveRecoveries() {
        return consecutiveRecoveries;
    }

    public long version() {
        return version;
    }

    public Double lastValue() {
        return lastValue;
    }

    public Long lastWindowStart() {
        return lastWindowStart;
    }

    /**
     * 用一次窗口评估结果推进状态机。
     *
     * @param breached    本次窗口值是否严格超过阈值
     * @param value       本次窗口聚合值
     * @param windowStart 本次窗口起始时间
     * @return 发生状态流转时返回事件类型，否则返回 null
     */
    public AlertEventType advance(boolean breached, double value, long windowStart) {
        lastValue = value;
        lastWindowStart = windowStart;
        version++;

        AlertEventType transition = null;
        if (state == AlertState.NORMAL) {
            if (breached) {
                consecutiveBreaches++;
                consecutiveRecoveries = 0;
                if (consecutiveBreaches >= breachForN) {
                    state = AlertState.FIRING;
                    consecutiveBreaches = 0;
                    transition = AlertEventType.TRIGGERED;
                }
            } else {
                // 抖动打断确认进度，但状态不变
                consecutiveBreaches = 0;
            }
        } else {
            if (!breached) {
                consecutiveRecoveries++;
                consecutiveBreaches = 0;
                if (consecutiveRecoveries >= recoverForN) {
                    state = AlertState.NORMAL;
                    consecutiveRecoveries = 0;
                    transition = AlertEventType.RECOVERED;
                }
            } else {
                // 告警中再次 breach，恢复确认被打断，仍保持 FIRING
                consecutiveRecoveries = 0;
            }
        }

        if (transition != null) {
            log.info("[alert] rule={} window={} value={} STATE CHANGE -> {} version={}",
                    ruleId, windowStart, value,
                    transition == AlertEventType.TRIGGERED ? "FIRING" : "NORMAL", version);
        } else {
            log.info("[alert] rule={} window={} value={} breached={} state={} breachStreak={} recoverStreak={} version={}",
                    ruleId, windowStart, value, breached, state,
                    consecutiveBreaches, consecutiveRecoveries, version);
        }
        return transition;
    }
}
