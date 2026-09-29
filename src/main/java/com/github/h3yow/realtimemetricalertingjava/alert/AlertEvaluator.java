package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.model.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.model.AlertState;
import java.util.List;

/** 告警评估器：读取窗口聚合，推进规则状态机。 */
public interface AlertEvaluator {

    /** 评估单条规则在指定窗口起点的状态。窗口无数据时返回 skipped。 */
    AlertEvaluation evaluateWindow(AlertRule rule, long windowStart);

    /** 当前规则状态。 */
    AlertState currentState(String ruleId);

    /** 已产生的告警事件（FIRED/RESOLVED），按时间升序。 */
    List<AlertEvent> events(String ruleId);

    /** 全部事件。 */
    List<AlertEvent> allEvents();
}
