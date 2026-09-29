package com.github.h3yow.realtimemetricalertingjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层集成测试：
 * 同一批次 JSON 重复 POST 两次，窗口计数必须与提交一次一致；拒绝原因逐点可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void duplicateBatchOverHttpIsIdempotentAndReasonsAreExposed() throws Exception {
        long now = System.currentTimeMillis();
        String body = """
                {
                  "batchId": "http-batch-1",
                  "points": [
                    {"service": "billing", "metric": "latency", "timestamp": %d, "value": 10, "pointId": "h1"},
                    {"service": "billing", "metric": "latency", "timestamp": %d, "value": 20, "pointId": "h2"},
                    {"service": "", "metric": "latency", "timestamp": %d, "value": 1},
                    {"service": "billing", "metric": "latency", "timestamp": 0, "value": 1},
                    {"service": "billing", "metric": "latency", "timestamp": %d, "value": 1}
                  ]
                }
                """.formatted(now, now + 1_000L, now, now - 99_999_999L);

        mockMvc.perform(post("/api/metrics/ingest")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicateBatch").value(false))
                .andExpect(jsonPath("$.rejected[0].reason").value("EMPTY_SERVICE"))
                .andExpect(jsonPath("$.rejected[1].reason").value("ILLEGAL_TIMESTAMP"))
                .andExpect(jsonPath("$.rejected[2].reason").value("EXPIRED_TIMESTAMP"));

        // 完全相同的批次再提交一次：计数不增加，标记为重复批次
        mockMvc.perform(post("/api/metrics/ingest")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicateBatch").value(true));

        // 窗口查询：两个点落在同一窗口（10s），计数仍为 2
        long windowStart = now / 10_000L * 10_000L;
        mockMvc.perform(get("/api/metrics/windows")
                        .param("service", "billing")
                        .param("metric", "latency")
                        .param("from", String.valueOf(windowStart))
                        .param("to", String.valueOf(windowStart + 10_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].sum").value(30.0));

        // 告警接口对默认规则可用
        mockMvc.perform(get("/api/alerts/status").param("ruleId", "qps-high"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NORMAL"));
    }
}
