package com.github.h3yow.realtimemetricalertingjava.service;

import com.github.h3yow.realtimemetricalertingjava.domain.WindowAggregate;
import com.github.h3yow.realtimemetricalertingjava.window.WindowStore;

import java.util.List;

/**
 * 窗口查询服务：按服务维度、时间范围返回固定窗口聚合，
 * 与写入共用同一把序列锁，高并发读写并存时读不到半成品计数。
 */
public final class QueryService {

    private final WindowStore store;

    public QueryService(WindowStore store) {
        this.store = store;
    }

    public List<WindowAggregate> query(String service, String metric, long fromMillis, long toMillis) {
        if (service == null || service.isBlank() || metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("service and metric are required");
        }
        return store.query(service, metric, fromMillis, toMillis);
    }
}
