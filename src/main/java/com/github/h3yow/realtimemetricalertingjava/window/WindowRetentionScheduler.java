package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.config.AlertingProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期清理早于保留时长的旧窗口，避免内存无限增长。
 * 清理只影响存储，不会改动告警状态机。
 */
@Component
public class WindowRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WindowRetentionScheduler.class);

    private final WindowStore windowStore;
    private final AlertingProperties properties;
    private final Clock clock;

    public WindowRetentionScheduler(WindowStore windowStore, AlertingProperties properties,
                                    Clock clock) {
        this.windowStore = windowStore;
        this.properties = properties;
        this.clock = clock;
    }

    /** 默认每 60 秒执行一次，可通过 alerting.purge-fixed-delay-ms 调整。 */
    @Scheduled(fixedDelayString = "${alerting.purge-fixed-delay-ms:60000}")
    public void purge() {
        try {
            long cutoff = clock.millis() - properties.getRetentionMillis();
            windowStore.purgeBefore(cutoff, properties.getWindowMillis());
        } catch (RuntimeException e) {
            log.warn("window purge failed", e);
        }
    }
}
