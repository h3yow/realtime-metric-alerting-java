# 实时指标接入与告警判定服务

纯内存实现的实时指标接入 + 窗口聚合 + 告警状态机服务。**不依赖任何外部监控组件**
（无数据库、无消息队列、无 Redis），服务端自行维护指标窗口与告警状态，进程重启后状态清空。

## 1. 运行与本地验证

环境要求：JDK 21+、Maven 3.6+。

```bash
# 编译
mvn compile

# 运行全部单测（乱序迟到 / 阈值抖动 / 重复提交 / 并发 / HTTP 端到端）
mvn test

# 查看带窗口边界与状态流转过程的日志
cat target/surefire-reports/com.github.h3yow.realtimemetricalertingjava.*.txt
# 或直接运行时观察控制台（日志由 slf4j 打印）

# 启动服务（application.properties 中 server.port=0 表示随机端口；
# 如需固定端口改成 8080）
mvn spring-boot:run
```

### curl 手工验证示例

```bash
# 写入（请求体支持单个 JSON 对象或数组）
curl -s -X POST localhost:8080/api/metrics -H 'Content-Type: application/json' -d '[
  {"id":"p1","service":"order","metricName":"cpu","value":0.91,"timestamp":<epochMillis>},
  {"id":"p2","service":"order","metricName":"cpu","value":0.95,"timestamp":<epochMillis>}
]'

# 按服务/指标/时间范围查询窗口聚合（from/to 为 epoch millis）
curl -s "localhost:8080/api/metrics?service=order&metricName=cpu&from=<ms>&to=<ms>"

# 注册告警规则（windowMillis 缺省取全局 alerting.window-millis）
curl -s -X POST localhost:8080/api/alerts/rules -H 'Content-Type: application/json' -d '{
  "id":"cpu-high","service":"order","metricName":"cpu",
  "operator":"GT","threshold":0.8,
  "fireConsecutiveWindows":2,"resolveConsecutiveWindows":2
}'

# 手动评估某窗口 / 查状态 / 查事件
curl -s -X POST "localhost:8080/api/alerts/rules/cpu-high/evaluate?windowStart=<对齐后的窗口起点>"
curl -s localhost:8080/api/alerts/rules/cpu-high/state
curl -s localhost:8080/api/alerts/rules/cpu-high/events
```

## 2. 时间窗口划分规则

- 窗口大小由 `alerting.window-millis`（默认 60000ms）配置，窗口为**左闭右开**：
  `windowStart = floorDiv(timestamp, windowMillis) * windowMillis`，区间 `[windowStart, windowStart + windowMillis)`。
- 数据点按**自身携带的 timestamp** 落到它实际发生时间所在的窗口。乱序、迟到数据
  不会进入"当前窗口"，而是回填到历史窗口；不同时间点的数据绝不会被算进同一个窗口。
- 查询 `[from, to)` 同样按窗口对齐：起点向下取整、终点（to-1）向下取整，逐窗口返回，
  保证调用方看到的窗口边界与写入侧完全一致。
- 时效性校验（拒绝原因见写入结果的 `reason` 字段）：
  - `INVALID_SERVICE` / `INVALID_METRIC_NAME`：服务名/指标名为空
  - `INVALID_VALUE`：value 不是有限数值（NaN / Infinity）
  - `INVALID_TIMESTAMP`：timestamp <= 0
  - `FUTURE_TIMESTAMP`：timestamp 超过当前时间 + `alerting.max-future-millis`
  - `EXPIRED_TIMESTAMP`：timestamp 早于当前时间 - `alerting.max-late-millis`（明显过期）
  - `DUPLICATE_POINT`：相同 `id` 的点重复提交（语义上仍算成功，不重复计数）
- 窗口聚合字段：`count / sum / min / max / average(=sum/count)`。
- 旧窗口按 `alerting.retention-millis` 由定时任务周期清理（`alerting.purge-fixed-delay-ms`）。

## 3. 告警状态机

四态状态机（`model.AlertState`），触发与恢复使用**各自独立的连续确认窗口数**：

```
                 连续 breach 达 fireConsecutiveWindows
   INACTIVE  ───────────────────────────────────────►  FIRING
       ▲                                                    │
       │ 连续 !breach 达 resolveConsecutiveWindows          │ 出现 !breach
       │                                                    ▼
       └────────────────────────────────────────────  PENDING_RESOLVE
       │ （恢复确认中再次 breach -> 计数清零，直接回 FIRING，不产生事件）
       
   INACTIVE ──breach──► PENDING_FIRING ──再连续 breach 达标──► FIRING
                            │
                            └──任一窗口 !breach──► INACTIVE（触发确认清零，不触发）
```

要点：

- **防抖**：阈值来回抖动时，PENDING_FIRING / PENDING_RESOLVE 中的连续计数会被对侧信号
  清零，因此不会提前触发、不会重复触发、不会误恢复。
- **触发与恢复条件相互独立**：`fireConsecutiveWindows=N`、`resolveConsecutiveWindows=M`，
  互不影响。
- **无数据不算恢复**：窗口没有任何数据点时评估结果为 `skipped`，状态机完全不推进，
  避免"停报"被误判为"恢复正常"。
- **乱序评估防御**：状态机只接受严格递增的 `windowStart`；重复评估同一窗口幂等无事件，
  更早的窗口直接忽略。因此迟到数据即使回填了历史窗口，也不会把当前告警状态拉回去。
- **并发安全**：`RuleAlertState.evaluate(...)` 为 synchronized，同一规则被并发评估时
  状态与连续计数作为原子整体更新，保证同一窗口只可能产生一次 FIRED/RESOLVED。
- 写入后默认自动评估匹配 `service + metricName` 的规则（`alerting.auto-evaluate=true`），
  也可通过 `/api/alerts/rules/{id}/evaluate` 手动驱动。

## 4. 并发与幂等

- `InMemoryWindowStore` 基于 `ConcurrentHashMap<WindowKey, WindowAggregate>`，
  并以每个窗口聚合对象为锁分段串行化：不同窗口并发写入互不阻塞，同一窗口的
  count/sum/min/max 更新原子进行，高并发写入下不丢点、不串窗口。
- 每个窗口维护已写入 `pointId` 集合（`LinkedHashSet`）。重复提交同一批次
  （相同 id）只在首次生效，后续返回 `DUPLICATE_POINT` 且计数不变——
  提交 N 次与提交 1 次的窗口结果完全一致。id 为空的点不做去重。
- 读写并存时查询对窗口对象加快照锁后拷贝出 `WindowData`，读到的聚合字段自洽。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/metrics` | 写入指标点（JSON 对象或数组），返回逐点结果（含拒绝原因/落点窗口） |
| GET  | `/api/metrics?service&metricName&from&to` | 查询窗口聚合列表（按窗口起点升序） |
| POST | `/api/alerts/rules` | 注册/替换告警规则 |
| GET  | `/api/alerts/rules` | 列出全部规则 |
| DELETE | `/api/alerts/rules/{id}` | 删除规则 |
| POST | `/api/alerts/rules/{id}/evaluate?windowStart=` | 评估某窗口，返回状态与可能的事件 |
| GET | `/api/alerts/rules/{id}/state` | 当前告警状态 |
| GET | `/api/alerts/rules/{id}/events` | 该规则产生过的 FIRED/RESOLVED 事件 |

## 6. 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `window.InMemoryWindowStoreTest` | 乱序/迟到落实际窗口、右开边界、pointId 去重、16 线程并发写入不丢点 |
| `alert.RuleAlertStateTest` | 触发连续确认、抖动不触发/不重复告警、恢复独立确认、重复/乱序窗口忽略、32 线程并发评估只产生一次 FIRED |
| `service.MetricServiceTest` | 七类拒绝原因逐一区分、迟到窗口落点、同一批次重复提交 3 次结果幂等、12 写线程 + 1 读线程并发一致性、自动评估驱动完整触发/恢复 |
| `web.ApiIntegrationTest` | HTTP 端到端：对象/数组写入、非法数据按原因拒绝、重复提交、窗口查询、规则注册/评估/状态保持 |

单测日志会打印：窗口边界 `[windowStart - windowEnd)`、每窗口 count/sum/avg、
拒绝原因明细，以及状态机每次评估的 `transition FROM -> TO`、连续计数和事件类型。
