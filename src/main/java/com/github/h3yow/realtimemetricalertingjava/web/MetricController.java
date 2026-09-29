package com.github.h3yow.realtimemetricalertingjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.realtimemetricalertingjava.model.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.model.MetricWriteResult;
import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.service.MetricService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricService metricService;
    private final ObjectMapper objectMapper;

    public MetricController(MetricService metricService, ObjectMapper objectMapper) {
        this.metricService = metricService;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入：请求体支持单个 JSON 对象或 JSON 数组。
     * 返回值统一为数组（单点时长度为 1）。
     */
    @PostMapping
    public List<MetricWriteResult> write(@RequestBody JsonNode body) {
        List<MetricPoint> points = new ArrayList<>();
        JsonNode nodes = body.isArray() ? body : new com.fasterxml.jackson.databind.node.ArrayNode(
                objectMapper.getNodeFactory()).add(body);
        for (JsonNode node : nodes) {
            try {
                points.add(objectMapper.treeToValue(node, MetricPoint.class));
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid metric point payload: " + e.getMessage());
            }
        }
        return metricService.writeBatch(points);
    }

    /** 按服务 + 指标 + 时间范围查询窗口聚合。 */
    @GetMapping
    public List<WindowData> query(@RequestParam String service,
                                  @RequestParam String metricName,
                                  @RequestParam long from,
                                  @RequestParam long to) {
        return metricService.query(service, metricName, from, to);
    }
}
