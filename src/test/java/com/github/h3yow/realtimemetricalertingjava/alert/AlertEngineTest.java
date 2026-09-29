package com.github.h3yow.realtimemetricalertingjava.alert;

import com.github.h3yow.realtimemetricalertingjava.domain.AlertEvent;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertEventType;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertRule;
import com.github.h3yow.realtimemetricalertingjava.domain.AlertState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEngineTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineTest.class);

    @Test
    void sameWindowEvaluatedTwiceIsIdempotent() {
        AlertEngine engine = new AlertEngine();
        engine.register(new AlertRule("r", "svc", "m", 100.0, 1, 1));

        AlertEvent first = engine.evaluate("r", 0L, 150.0);
        assertEquals(AlertEventType.TRIGGERED, first.type());
        // 同一窗口重复评估：直接跳过，不产生第二个事件
        assertNull(engine.evaluate("r", 0L, 150.0));
        assertEquals(1, engine.eventsOf("r").size());
        log.info("[test-alert] duplicate window evaluation produced only {} event(s)",
                engine.eventsOf("r").size());
    }

    @Test
    void concurrentEvaluationOfSameRuleProducesSingleTrigger() throws Exception {
        AlertEngine engine = new AlertEngine();
        // breachForN=3：需要 3 个不同窗口都超阈值
        engine.register(new AlertRule("r", "svc", "m", 100.0, 3, 2));

        int threadsPerWindow = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerWindow);
        AtomicInteger returnedEvents = new AtomicInteger();

        // 三个窗口，每个窗口被 16 个线程并发评估
        for (long w = 0; w < 3; w++) {
            long windowStart = w * 10_000L;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadsPerWindow);
            for (int t = 0; t < threadsPerWindow; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        AlertEvent e = engine.evaluate("r", windowStart, 150.0);
                        if (e != null && e.type() == AlertEventType.TRIGGERED) {
                            returnedEvents.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            log.info("[test-alert] window={} done, TRIGGERED events so far={}",
                    windowStart, returnedEvents.get());
        }
        pool.shutdown();

        assertEquals(1, returnedEvents.get(), "并发评估只能有一次触发");
        assertEquals(1, engine.eventsOf("r").size(), "事件历史中只能有一条 TRIGGERED");
        assertEquals(AlertState.FIRING, engine.statusOf("r").state());
        log.info("[test-alert] final state={} version={}",
                engine.statusOf("r").state(), engine.statusOf("r").version());
    }
}
