# 实时指标接入与告警判定（进程内实现）

服务端自行维护指标窗口与告警状态，**不依赖任何外部监控组件 / 时序数据库 / 消息队列**：
窗口聚合、点去重、批次幂等、告警状态机全部在单个 Spring Boot 进程的内存中完成，
重启后状态清空（本项目聚焦判定逻辑正确性）。

## 1. 窗口划分规则

- 固定**滚动窗口（tumbling window）**，默认窗口大小 **10 秒**（`AppConfig#windowSpec`）。
- 窗口边界**按 epoch 0 对齐**，左闭右开：

  ```
  windowStart(t) = floor(t / sizeMillis) * sizeMillis
  窗口 n 覆盖 [n * sizeMillis, (n+1) * sizeMillis)
  ```

  例：`9999ms -> [0,10000)`，`10000ms -> [10000,20000)`，边界两侧绝不混算。
- 数据只按**实际发生时间** `timestamp` 落窗口，与到达顺序无关：乱序、迟到的数据直接补写进历史窗口，
  与同窗口其它数据一起聚合（count / sum / min / max / avg）。
- 每个 `(service, metric)` 序列独立保留最近 10000 个窗口，超出淘汰最老窗口。
- 写入时的拒绝原因（`RejectReason`，逐点返回，HTTP 整体 200）：
  - `EMPTY_SERVICE` / `EMPTY_METRIC`：维度为空
  - `ILLEGAL_TIMESTAMP`：时间戳 <= 0（或请求中缺失）
  - `FUTURE_TIMESTAMP`：超出“当前时间 + 未来容忍（60s 时钟漂移）”
  - `EXPIRED_TIMESTAMP`：早于“当前时间 - 24h”，视为明显过期
  - `NAN_OR_INFINITE_VALUE`：值非法
  - `DUPLICATE_POINT`：同一 `pointId` 在目标窗口已计过数

## 2. 告警状态机

每条规则独立维护一个状态机（`AlertStateMachine`），判定值为窗口聚合平均值，
`breach = avg > threshold`（**严格大于**，等于阈值不算 breach）。

```
                 连续 breachForN 次 breach
       NORMAL  ───────────────────────────────▶  FIRING
        ▲                                            │
        └────────────── 连续 recoverForN 次 ◀────────┘
                        非 breach（恢复确认）
```

- **触发与恢复各自需要独立的确认条件**：默认规则 `qps-high` 为连续 **3** 个窗口超阈值才触发、
  连续 **2** 个窗口回落才恢复（`breachForN` / `recoverForN` 可按规则配置）。
- 阈值在边界附近来回抖动时，只要任一方的“连续确认”被打断，状态保持不变，
  不会反复触发/恢复。
- **并发安全**：每条规则一把锁，同一规则的并发评估串行执行；不同规则互不阻塞。
- **评估幂等**：以“窗口起始时间”为幂等键，同一窗口无论被评估多少次，状态机只推进一次，
  不会产生重复 TRIGGERED / RECOVERED 事件，也不会状态跳变。
- 每次评估都通过 SLF4J 打印窗口边界、breach 结果、两个连续计数与状态变化过程。

## 3. 数据正确性与幂等

- 写入：每个 `(service, metric)` 序列一把分段锁，计数在锁内更新，高并发读写并存时不丢数、不串桶。
- 两层幂等，保证“同一批数据重复提交的结果与只提交一次一致”：
  1. **批次级**：相同 `batchId` 只首次真正写入，后续提交回放首次结果并标记 `duplicateBatch=true`
     （同批次并发提交用批次锁串行化，只有一个线程真正写入）；
  2. **点级**：相同 `pointId` 在同一窗口只计一次（跨批次重复也拦截，返回 `DUPLICATE_POINT`）。

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/metrics/ingest` | 批量写入，body：`{"batchId":"...","points":[{service,metric,timestamp,value,pointId}]}` |
| GET | `/api/metrics/windows?service&metric&from&to` | 查询 `[from,to)` 内的窗口聚合（升序） |
| POST | `/api/alerts/evaluate?ruleId&at` | 评估 `at`（缺省=当前时间）所属窗口，返回事件与最新状态 |
| GET | `/api/alerts/status?ruleId` | 当前状态、连续计数、版本号 |
| GET | `/api/alerts/events?ruleId` | TRIGGERED / RECOVERED 事件历史 |

启动：`mvn spring-boot:run`（`application.properties` 使用随机端口）。

写入示例：

```bash
curl -s -XPOST localhost:8080/api/metrics/ingest -H 'Content-Type: application/json' -d '{
  "batchId":"b1",
  "points":[
    {"service":"order-service","metric":"qps","timestamp":1769702400000,"value":120,"pointId":"p1"}
  ]
}'
```

## 5. 本地验证方式

```bash
# 全量单测 + Spring 集成测试（共 16 个）
mvn test

# 只跑某一类场景
mvn test -Dtest=WindowStoreTest          # 乱序/迟到落窗、点去重、并发写入不丢数
mvn test -Dtest=AlertStateMachineTest    # 阈值反复抖动不反复触发/恢复
mvn test -Dtest=AlertEngineTest          # 同规则并发评估只有一次触发、同窗口评估幂等
mvn test -Dtest=IngestionServiceTest     # 拒绝原因区分、批次重复提交/并发重复提交幂等
mvn test -Dtest=EndToEndFlowTest         # 乱序写入 -> 窗口聚合 -> 触发/恢复全链路
mvn test -Dtest=HttpApiIntegrationTest   # HTTP 重复提交幂等、拒绝原因、窗口查询
```

日志中可直接观察：

- `[window] open ... window=[start,end)`：数据落点的窗口边界（含迟到补写历史窗口）；
- `[window] duplicate point ignored ...`：窗口内点去重；
- `[alert] ... STATE CHANGE -> FIRING/NORMAL`：状态流转；
- `[alert] ... breachStreak / recoverStreak`：抖动时两侧独立计数的变化。

## 6. 代码结构

```
domain/   指标点、窗口聚合、规则、状态、事件、拒绝原因等不可变模型
window/   WindowSpec（epoch 对齐）、WindowBucket（窗口内聚合/去重）、WindowStore（分段锁存储）
alert/    AlertStateMachine（确认计数状态机）、AlertEngine（规则锁 + 窗口幂等 + 事件历史）
service/  IngestionService（校验/批次幂等）、QueryService、AlertEvaluationService
web/      指标写入/查询、告警评估/状态/事件 REST 接口
config/   AppConfig 进程内 Bean 装配与默认规则
```
