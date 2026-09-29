package com.github.h3yow.realtimemetricalertingjava.domain;

import java.util.List;

/**
 * 一次批次写入的结果。
 *
 * @param acceptedCount 实际落入窗口的点数（重复批次/重复点不计）
 * @param rejected      被拒绝的点（带原因）
 * @param duplicate     是否为重复批次（整体命中幂等缓存）
 * @param windowStarts  本次接受点落入的窗口起始时间集合
 */
public record IngestResult(int acceptedCount, List<RejectedPoint> rejected, boolean duplicate,
                           List<Long> windowStarts) {
}
