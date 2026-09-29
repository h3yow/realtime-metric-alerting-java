package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryAlertRuleRegistry implements AlertRuleRegistry {

    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();

    @Override
    public AlertRule register(AlertRule rule) {
        validate(rule);
        if (rule.getOperator() == null || rule.getOperator().isBlank()) {
            rule.setOperator("GT");
        }
        rules.put(rule.getId(), rule);
        return rule;
    }

    private void validate(AlertRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        if (rule.getId() == null || rule.getId().isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        if (rule.getService() == null || rule.getService().isBlank()) {
            throw new IllegalArgumentException("rule service must not be blank");
        }
        if (rule.getMetricName() == null || rule.getMetricName().isBlank()) {
            throw new IllegalArgumentException("rule metricName must not be blank");
        }
        String op = rule.getOperator();
        if (op != null && !op.isBlank() && !op.equalsIgnoreCase("GT") && !op.equalsIgnoreCase("GTE")) {
            throw new IllegalArgumentException("operator must be GT or GTE, got " + op);
        }
        if (rule.getFireConsecutiveWindows() < 1) {
            throw new IllegalArgumentException("fireConsecutiveWindows must be >= 1");
        }
        if (rule.getResolveConsecutiveWindows() < 1) {
            throw new IllegalArgumentException("resolveConsecutiveWindows must be >= 1");
        }
        if (rule.getWindowMillis() <= 0) {
            throw new IllegalArgumentException("windowMillis must be > 0");
        }
    }

    @Override
    public boolean remove(String ruleId) {
        return rules.remove(ruleId) != null;
    }

    @Override
    public AlertRule get(String ruleId) {
        return rules.get(ruleId);
    }

    @Override
    public Collection<AlertRule> all() {
        return List.copyOf(rules.values());
    }
}
