package com.github.h3yow.realtimemetricalertingjava.web;

import com.github.h3yow.realtimemetricalertingjava.domain.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertStatusView;
import com.github.h3yow.realtimemetricalertingjava.service.AlertEvaluationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 告警评估、当前状态与事件历史查询接口。 */
@RestController
public class AlertController {

    private final AlertEvaluationService alertEvaluationService;

    public AlertController(AlertEvaluationService alertEvaluationService) {
        this.alertEvaluationService = alertEvaluationService;
    }

    /**
     * 评估某条规则在 at 所属窗口的表现。同一窗口重复评估幂等，不会产生重复事件。
     * 不传 at 时默认当前时间。
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/alerts/evaluate")
    public Map<String, Object> evaluate(@RequestParam String ruleId,
                                        @RequestParam(required = false) Long at) {
        long atMillis = at == null ? System.currentTimeMillis() : at;
        AlertEvent event = alertEvaluationService.evaluate(ruleId, atMillis);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("evaluatedWindow", atMillis);
        resp.put("event", event);
        resp.put("status", alertEvaluationService.status(ruleId));
        return resp;
    }

    @GetMapping("/api/alerts/status")
    public AlertStatusView status(@RequestParam String ruleId) {
        return alertEvaluationService.status(ruleId);
    }

    @GetMapping("/api/alerts/events")
    public List<AlertEvent> events(@RequestParam String ruleId) {
        return alertEvaluationService.events(ruleId);
    }
}
