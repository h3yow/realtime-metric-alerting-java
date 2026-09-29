package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.model.AlertRule;
import java.util.Collection;

/** 规则注册表（线程安全）。 */
public interface AlertRuleRegistry {

    /** 注册或替换一条规则。非法规则抛 IllegalArgumentException。 */
    AlertRule register(AlertRule rule);

    /** 删除规则及其状态。 */
    boolean remove(String ruleId);

    AlertRule get(String ruleId);

    Collection<AlertRule> all();
}
