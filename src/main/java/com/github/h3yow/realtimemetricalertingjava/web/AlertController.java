package com.github.h3yow.realtimemetricalertingjava.web;

import com.github.h3yow.realtimemetricalertingjava.alert.AlertEvaluation;
import com.github.h3yow.realtimemetricalertingjava.alert.AlertEvaluator;
import com.github.h3yow.realtimemetricalertingjava.alert.AlertRuleRegistry;
import com.github.h3yow.realtimemetricalertingjava.config.AlertingProperties;
import com.github.h3yow.realtimemetricalertingjava.model.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.model.AlertState;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRuleRegistry ruleRegistry;
    private final AlertEvaluator alertEvaluator;
    private final AlertingProperties properties;

    public AlertController(AlertRuleRegistry ruleRegistry, AlertEvaluator alertEvaluator,
                           AlertingProperties properties) {
        this.ruleRegistry = ruleRegistry;
        this.alertEvaluator = alertEvaluator;
        this.properties = properties;
    }

    @PostMapping("/rules")
    public AlertRule registerRule(@RequestBody AlertRule rule) {
        if (rule.getWindowMillis() <= 0) {
            rule.setWindowMillis(properties.getWindowMillis());
        }
        return ruleRegistry.register(rule);
    }

    @GetMapping("/rules")
    public List<AlertRule> listRules() {
        return List.copyOf(ruleRegistry.all());
    }

    @DeleteMapping("/rules/{id}")
    public boolean removeRule(@PathVariable String id) {
        return ruleRegistry.remove(id);
    }

    /** 手动评估某规则在某窗口的状态。 */
    @PostMapping("/rules/{id}/evaluate")
    public AlertEvaluation evaluate(@PathVariable String id,
                                    @RequestParam long windowStart) {
        AlertRule rule = ruleRegistry.get(id);
        if (rule == null) {
            throw new IllegalArgumentException("rule not found: " + id);
        }
        return alertEvaluator.evaluateWindow(rule, windowStart);
    }

    @GetMapping("/rules/{id}/state")
    public AlertState state(@PathVariable String id) {
        return alertEvaluator.currentState(id);
    }

    @GetMapping("/rules/{id}/events")
    public List<AlertEvent> events(@PathVariable String id) {
        return alertEvaluator.events(id);
    }
}
