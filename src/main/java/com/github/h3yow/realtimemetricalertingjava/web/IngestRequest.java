package com.github.h3yow.realtimemetricalertingjava.web;

import java.util.List;

/** 批量写入请求体。 */
public record IngestRequest(String batchId, List<PointDto> points) {
}
