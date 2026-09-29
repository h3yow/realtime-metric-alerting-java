package com.github.h3yow.realtimemetricalertingjava.config;

import com.github.h3yow.realtimemetricalertingjava.alert.AlertEngine;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.service.AlertEvaluationService;
import com.github.h3yow.realtimemetricalertingjava.service.IngestionService;
import com.github.h3yow.realtimemetricalertingjava.service.QueryService;
import com.github.h3yow.realtimemetricalertingjava.window.WindowSpec;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本地组件装配：窗口存储、告警引擎、服务全部为进程内内存实现，不依赖外部监控组件。 */
@Configuration
public class AppConfig {

    /** 窗口大小 10s；迟到容忍只影响拒绝边界，窗口本身可无限迟到补写（在保留期内）。 */
    @Bean
    public WindowSpec windowSpec() {
        return new WindowSpec(10_000L, 30_000L);
    }

    @Bean
    public WindowStore windowStore(WindowSpec spec) {
        return new WindowStore(spec, 10_000);
    }

    @Bean
    public AlertEngine alertEngine() {
        AlertEngine engine = new AlertEngine();
        // 默认演示规则：order-service 的 qps 均值连续 3 个窗口超过 100 触发，连续 2 个窗口回落恢复
        engine.register(new AlertRule("qps-high", "order-service", "qps", 100.0, 3, 2));
        return engine;
    }

    @Bean
    public IngestionService ingestionService(WindowStore store) {
        // 允许 60s 时钟漂移；超过 24h 的数据视为明显过期拒绝
        return new IngestionService(store, 60_000L, 24L * 60 * 60 * 1000);
    }

    @Bean
    public QueryService queryService(WindowStore store) {
        return new QueryService(store);
    }

    @Bean
    public AlertEvaluationService alertEvaluationService(WindowStore store, AlertEngine engine) {
        return new AlertEvaluationService(store, engine);
    }
}
