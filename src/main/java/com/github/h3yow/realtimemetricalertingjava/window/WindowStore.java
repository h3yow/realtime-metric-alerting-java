package com.github.h3yow.realtimemetricalertingjava.window;

import com.github.h3yow.realtimemetricalertingjava.model.WindowData;
import com.github.h3yow.realtimemetricalertingjava.model.WindowKey;
import java.util.List;

/**
 * 窗口存储：按 窗口起点对齐 的 WindowKey 存放聚合。
 * 所有方法线程安全；同一窗口的写入串行化，保证高并发下不丢点、不重复计数。
 */
public interface WindowStore {

    /** 幂等写入结果。 */
    enum WriteStatus { ACCEPTED, DUPLICATE }

    /**
     * 向 timestamp 实际所在的窗口写入一个点。
     * 窗口起点 = floor(timestamp / windowMillis) * windowMillis。
     */
    WriteStatus add(String service, String metricName, long timestamp,
                    double value, String pointId, long windowMillis);

    /** 查询 [from, to) 范围内（按窗口起点）全部窗口聚合，按窗口起点升序。 */
    List<WindowData> query(String service, String metricName,
                           long from, long to, long windowMillis);

    /** 单个窗口快照，不存在返回 null。 */
    WindowData getWindow(WindowKey key, long windowMillis);

    /** 删除 windowStart <= cutoffMillis 的过期窗口。 */
    void purgeBefore(long cutoffMillis, long windowMillis);
}
