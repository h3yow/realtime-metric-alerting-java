package com.github.h3yow.realtimemetricalertingjava.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 端到端：HTTP 写入（对象/数组）、拒绝原因、窗口查询、规则与告警状态。 */
@SpringBootTest(properties = {
        "alerting.window-millis=60000",
        "alerting.max-late-millis=86400000",
        "alerting.max-future-millis=86400000",
        "alerting.auto-evaluate=false"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        long ts = System.currentTimeMillis();
        long w0 = Math.floorDiv(ts, 60_000L) * 60_000L;
        long next = w0 + 60_000L;

        // 单点（JSON 对象）写入
        mockMvc.perform(post("/api/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"h1\",\"service\":\"svc\",\"metricName\":\"cpu\",\"value\":0.9,\"timestamp\":" + ts + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accepted").value(true))
                .andExpect(jsonPath("$[0].windowStart").value(w0));

        // 数组写入，其中含一条非法（空 service）-> 按原因拒绝，不影响其他点
        mockMvc.perform(post("/api/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"h2\",\"service\":\"svc\",\"metricName\":\"cpu\",\"value\":0.8,\"timestamp\":"
                                + (ts + 1000) + "},"
                                + "{\"id\":\"h3\",\"service\":\"\",\"metricName\":\"cpu\",\"value\":1,\"timestamp\":" + ts + "}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accepted").value(true))
                .andExpect(jsonPath("$[1].accepted").value(false))
                .andExpect(jsonPath("$[1].reason").value("INVALID_SERVICE"));

        // 重复提交 h1 -> 幂等
        mockMvc.perform(post("/api/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"h1\",\"service\":\"svc\",\"metricName\":\"cpu\",\"value\":0.9,\"timestamp\":" + ts + "}]"))
                .andExpect(jsonPath("$[0].accepted").value(true))
                .andExpect(jsonPath("$[0].reason").value("DUPLICATE_POINT"));

        // 查询窗口：两个不同点落在同一窗口，重复点不计数
        mockMvc.perform(get("/api/metrics")
                        .param("service", "svc").param("metricName", "cpu")
                        .param("from", "" + w0 + "")
                        .param("to", "" + next + ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].windowStart").value(w0));

        // 注册规则（windowMillis 缺省取全局配置），手动评估窗口 -> 越限但仅 1 个窗口，PENDING_FIRING
        mockMvc.perform(post("/api/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"r1\",\"service\":\"svc\",\"metricName\":\"cpu\",\"operator\":\"GT\","
                                + "\"threshold\":0.7,\"fireConsecutiveWindows\":2,\"resolveConsecutiveWindows\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowMillis").value(60000));

        mockMvc.perform(post("/api/alerts/rules/r1/evaluate")
                        .param("windowStart", "" + w0 + ""))
                .andExpect(jsonPath("$.state").value("PENDING_FIRING"))
                .andExpect(jsonPath("$.event").isEmpty());

        // 再次评估同一窗口 -> 幂等跳过（skipped=true，因为同一窗口第二次评估被状态机忽略，
        // 但窗口数据存在 -> 此处验证状态不发生跳变）
        mockMvc.perform(post("/api/alerts/rules/r1/evaluate")
                        .param("windowStart", "" + w0 + ""))
                .andExpect(jsonPath("$.state").value("PENDING_FIRING"));

        // 不存在的规则 -> 400
        mockMvc.perform(post("/api/alerts/rules/nope/evaluate")
                        .param("windowStart", "" + w0 + ""))
                .andExpect(status().isBadRequest());
    }
}
