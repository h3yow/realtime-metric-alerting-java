package com.github.h3yow.realtimemetricalertingjava.model;

/** 单个数据点写入结果。被拒绝时带原因；被接受时带落点窗口起点。 */
public class MetricWriteResult {
    private boolean accepted;
    private RejectReason reason;
    private String message;
    private Long windowStart;
    private String pointId;

    public MetricWriteResult() {
    }

    public static MetricWriteResult accepted(String pointId, long windowStart) {
        MetricWriteResult r = new MetricWriteResult();
        r.accepted = true;
        r.pointId = pointId;
        r.windowStart = windowStart;
        r.message = "accepted";
        return r;
    }

    /** 幂等命中：语义上算成功（不重复计数），但单独标明 DUPLICATE_POINT 便于调用方观察。 */
    public static MetricWriteResult duplicate(String pointId, long windowStart) {
        MetricWriteResult r = new MetricWriteResult();
        r.accepted = true;
        r.pointId = pointId;
        r.windowStart = windowStart;
        r.reason = RejectReason.DUPLICATE_POINT;
        r.message = "duplicate point ignored (idempotent)";
        return r;
    }

    public static MetricWriteResult rejected(RejectReason reason, String message) {
        MetricWriteResult r = new MetricWriteResult();
        r.accepted = false;
        r.reason = reason;
        r.message = message;
        return r;
    }

    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }
    public RejectReason getReason() { return reason; }
    public void setReason(RejectReason reason) { this.reason = reason; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getWindowStart() { return windowStart; }
    public void setWindowStart(Long windowStart) { this.windowStart = windowStart; }
    public String getPointId() { return pointId; }
    public void setPointId(String pointId) { this.pointId = pointId; }
}
