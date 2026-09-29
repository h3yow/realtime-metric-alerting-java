package com.github.h3yow.realtimemetricalertingjava.alert;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阈值反复抖动时不能反复触发/恢复；触发与恢复需要各自独立的连续确认；
 * 同一规则并发评估不能产生重复事件或状态跳变。
 */
class RuleAlertStateTest {

    private static final Logger log = LoggerFactory.getLogger(RuleAlertStateTest.class);

    private RuleAlertState newMachine(String id) throws Exception {
        Constructor<RuleAlertState> c = RuleAlertState.class.getDeclaredConstructor(String.class);
        c.setAccessible(true);
        return c.newInstance(id);
    }

    /** 1=越限, 0=正常。 */
    private String feed(RuleAlertState m, long[] windows, int[] breached,
                        int fireN, int resolveN) {
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < windows.length; i++) {
            String e = m.evaluate(breached[i] == 1, windows[i], breached[i],
                    fireN, resolveN, windows[i]);
            if (e != null) {
                events.append(e).append('@').append(windows[i]).append(';');
            }
        }
        return events.toString();
    }

    @Test
    void requiresConsecutiveConfirmationAndDoesNotFlap() throws Exception {
        RuleAlertState m = newMachine("rule-1");
        long W = 60_000L;
        long[] windows = {10 * W, 11 * W, 12 * W, 13 * W, 14 * W, 15 * W, 16 * W, 17 * W};
        // 触发需要连续3个越限窗口；恢复需要连续2个正常窗口。
        int[] seq = {
                1, 1,       // 越限但未连续3次 -> PENDING_FIRING
                0,          // 抖动回正常 -> 确认清零，回到 INACTIVE，不触发
                1, 1, 1,    // 连续3次 -> FIRED（恰好一次）
                0, 1        // 恢复确认中又越限 -> 回到 FIRING，不产生 RESOLVED、不重复 FIRED
        };
        String events = feed(m, windows, seq, 3, 2);
        log.info("flap sequence events='{}' finalState={} breachStreak={} okStreak={}",
                events, m.getState(), m.getConsecutiveBreaches(), m.getConsecutiveOk());
        assertEquals("FIRED@" + (15 * W) + ";", events,
                "抖动期间只能产生一次 FIRED，不能提前触发也不能重复触发");
    }

    @Test
    void resolveAlsoNeedsIndependentConfirmation() throws Exception {
        RuleAlertState m = newMachine("rule-2");
        long W = 60_000L;
        long[] windows = {20 * W, 21 * W, 22 * W, 23 * W, 24 * W};
        int[] seq = {1, 1, 0, 1, 1};
        String events = feed(m, windows, seq, 2, 2);
        log.info("resolve-flap events='{}' finalState={}", events, m.getState());
        assertEquals("FIRED@" + (21 * W) + ";", events,
                "恢复确认未完成（只连续1个正常窗口后又越限），不能 RESOLVED");
        assertEquals(com.github.h3yow.realtimemetricalertingjava.model.AlertState.FIRING,
                m.getState());

        // 真正连续2个正常窗口才恢复，且只恢复一次
        long[] windows2 = {25 * W, 26 * W, 27 * W};
        int[] seq2 = {0, 0, 0};
        String events2 = feed(m, windows2, seq2, 2, 2);
        log.info("real resolve events='{}' finalState={}", events2, m.getState());
        assertEquals("RESOLVED@" + (26 * W) + ";", events2);
    }

    @Test
    void duplicateAndOutOfOrderWindowsAreIgnored() throws Exception {
        RuleAlertState m = newMachine("rule-3");
        long W = 60_000L;
        assertEquals("FIRED@" + (31 * W) + ";",
                feed(m, new long[]{30 * W, 31 * W}, new int[]{1, 1}, 2, 2));
        // 重复评估同一窗口：幂等无事件
        assertNull(m.evaluate(true, 31 * W, 1, 2, 2, 31 * W));
        // 更早的乱序窗口：直接忽略，不改状态
        assertNull(m.evaluate(false, 29 * W, 0, 2, 2, 29 * W));
        log.info("duplicate/out-of-order ignored, state={} lastWindow={}",
                m.getState(), m.getLastEvaluatedWindowStart());
        assertEquals(com.github.h3yow.realtimemetricalertingjava.model.AlertState.FIRING,
                m.getState());
        assertEquals(31 * W, m.getLastEvaluatedWindowStart());
    }

    @Test
    void concurrentEvaluationFiresExactlyOnce() throws Exception {
        RuleAlertState m = newMachine("rule-concurrent");
        long W = 60_000L;
        // 先把状态推进到 FIRED 前一步（PENDING_FIRING，需要连续2个）
        m.evaluate(true, 40 * W, 1, 2, 2, 40 * W);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger fired = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 所有线程并发评估同一个窗口
                    String e = m.evaluate(true, 41 * W, 1, 2, 2, 41 * W);
                    if ("FIRED".equals(e)) {
                        fired.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("concurrent evaluation FIRED count={} state={}",
                fired.get(), m.getState());
        assertEquals(1, fired.get(), "并发评估同一窗口只能产生一次 FIRED");
    }
}
