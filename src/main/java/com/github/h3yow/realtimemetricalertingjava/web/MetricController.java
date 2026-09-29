package com.github.h3yow.realtimemetricalertingjava.web;

import com.github.h3yow.realtimemetricalertingjava.domain.IngestResult;
import com.github.h3yow.realtimemetricalertingjava.domain.MetricPoint;
import com.github.h3yow.realtimemetricalertingjava.domain.RejectedPoint;
import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import com.github.h3yow.realtimemetricalertingjava.service.IngestionService;
import com.github.h3yow.realtimemetricalertingjava.service.QueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 指标写入与窗口查询接口。 */
@RestController
public class MetricController {

    private final IngestionService ingestionService;
    private final QueryService queryService;

    public MetricController(IngestionService ingestionService, QueryService queryService) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
    }

    /**
     * 批量写入指标。同一 batchId 重复提交，返回结果与只提交一次一致。
     * 非法点按原因拒绝，HTTP 整体仍返回 200，逐点给出 rejected 原因。
     */
    @PostMapping("/api/metrics/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        List<MetricPoint> points = new ArrayList<>();
        List<RejectedPoint> malformed = new ArrayList<>();
        if (request.points() != null) {
            for (PointDto dto : request.points()) {
                if (dto.timestamp() == null || dto.value() == null) {
                    malformed.add(new RejectedPoint(
                            new MetricPoint(dto.service(), dto.metric(),
                                    dto.timestamp() == null ? -1L : dto.timestamp(),
                                    dto.value() == null ? Double.NaN : dto.value(),
                                    request.batchId(), dto.pointId()),
                            com.github.h3yow.realtimemetricalertingjava.domain.RejectReason.ILLEGAL_TIMESTAMP,
                            "timestamp and value are required"));
                    continue;
                }
                points.add(new MetricPoint(dto.service(), dto.metric(), dto.timestamp(), dto.value(),
                        request.batchId(), dto.pointId()));
            }
        }
        IngestResult result = ingestionService.ingest(points, System.currentTimeMillis());
        List<RejectedPoint> allRejected = new ArrayList<>(malformed);
        allRejected.addAll(result.rejected());
        return Map.of(
                "accepted", result.acceptedCount(),
                "rejected", allRejected,
                "duplicateBatch", result.duplicate(),
                "windowStarts", result.windowStarts());
    }

    /** 按 [from, to) 时间范围查询窗口聚合，乱序/迟到补写的数据也会体现在其所属窗口中。 */
    @GetMapping("/api/metrics/windows")
    public List<WindowAggregate> queryWindows(@RequestParam String service,
                                              @RequestParam String metric,
                                              @RequestParam long from,
                                              @RequestParam long to) {
        return queryService.query(service, metric, from, to);
    }
}
