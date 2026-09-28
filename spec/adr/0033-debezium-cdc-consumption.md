# ADR-0033：Debezium CDC 解码与事务确认

日期：2026-09-28。状态：Debezium JSON 解码、Kafka 分区会话和事务后确认已实现；完整 CDC 运维/Schema Registry/生产联调继续。

## 上游对照

上游 v0.3.0 的 `CdcConsumer` 接收连接器产生的 `SourceRecord`，调用 `RecordMapper` 和 `changeApplier`，按间隔保存 checkpoint；当前上游实现记录单条失败后继续处理，因此可能越过失败事件。Java 保留其映射边界和 CDC 配置，同时将失败停点、事务回执及确认顺序收紧为数据底座必须具备的语义。

Java 新增 `DebeziumDecoder`、`CdcTransport`、`KafkaCdcTransport` 和 `CdcConsumer`：Kafka Connect JSON envelope 在进入 Foundry 映射前被解析，SourceRecord 仍由已有 `MaterializedSyncService` 处理。Consumer 只支持一次显式消费会话；自动调度、死信和集群租约属于宿主运行层。

## 消息位置与确认

Transport 固定逻辑 identity、topic 和 partition assignment。打开会话时读取每个分区的已提交 Foundry checkpoint；有本地位置则 seek 到 `offset + 1`，无本地位置显式从日志起点开始，禁止信任其他 consumer group 的游标。Kafka 自动提交关闭，隔离级别为 `read_committed`，`MAX_POLL_RECORDS` 和 fetch bytes 有上限。

一次只向解码器交付一个当前消息；在当前消息确认前不能拉取下一条。Consumer 把 source event position 写入 SourcePosition，MaterializedSyncService 将对象/关系事实、来源、receipt、checkpoint、audit/outbox 同事务提交；只有该调用成功返回后，`CdcTransport.Session.acknowledge` 才能 commit Kafka offset。确认失败时本地事实/回执仍在，重投由 receipt fingerprint 返回 REPLAYED，不能再次产生事实。

处理映射/授权/数据库失败会停止本次会话，未确认消息及其后的消息留在 broker。此前已提交的消息可安全重投；持久 checkpoint 只按已成功事件推进。tombstone 没有 row，不产生 Foundry DELETE，只在 broker 会话内确认；因此重启时可能重复看到 tombstone，这是安全的无副作用处理。源系统若需要把删除表达为 Debezium delete envelope，使用 `op=d`，它会生成 DELETE SourceRecord。

消费端支持 pause/resume 和关闭唤醒；关闭主动取消当前 session。限速按会话消费，跨进程总速率、rebalance、租约和背压传播由宿主 transport/调度器负责。Kafka commit 不是 Foundry 事务的一部分，设计采用本地事实先提交、broker offset 后提交的 at-least-once 语义。

## Envelope 与来源校验

解码器支持 schemaless 和 Kafka Connect `{schema,payload}`；拒绝重复 JSON key、尾随内容、过深/过长 JSON、错误 UTF-8、错误 topic/table/database/schema 及 key/row 主键不一致。支持 `c/u/d/r`，`d` 使用 before，`r` 作为 UPSERT；缺失 source timestamp 时使用 broker timestamp。Provenance 保存 Debezium event identity、映射来源和 envelope 摘要，避免同一 offset 换 payload 后复用旧 receipt。

Kafka Connect 精确 Decimal 从 bytes/scale 转 BigDecimal；Date、Timestamp、Micro/Nano Timestamp、Time 变成 ISO 值；struct、array、map 递归转换。时间单位转换有范围检查，未知命名 scalar 保留受约束的 JSON 值，由 ODL 目标属性继续校验。Java 不执行 schema 中的代码、表达式或外部引用；真实 Debezium logical type 组合仍需来源适配测试。

分区位置采用 `cdc_<topic,partition>` 稳定命名，事件 ID 由 topic/partition/offset 派生。offset 必须非负且同一 partition 单调；transport 不可把另一 topic/分区消息注入会话。消费者身份、binding、connector、URL、表和 properties 进入来源配置摘要；换 Kafka 集群、绑定或映射必须显式迁移 checkpoint。

## Kafka 适配器与宿主边界

`KafkaCdcTransport` 由宿主提供 bootstrap/security/group 等可信配置，强制 `enable.auto.commit=false`、`auto.offset.reset=none`、`read_committed`、ByteArrayDeserializer 和 UTF-8 JSON。它使用显式 `assign`，不自动发现分区，也不替宿主做 consumer group rebalance；一个会话关闭时清理 consumer。`commitSync` 只提交当前 partition 的 `offset+1`，并检查 acknowledge 对象仍是当前消息。

真实 Kafka 连接、SASL/TLS 凭证、Schema Registry wire format、压缩/大消息、rebalance 与跨实例 ownership 不是单元测试可证明的能力。宿主必须提供固定分区租约或使用另一个 transport 实现，并将错误送入可恢复的死信流程。

## 验证

新增 DebeziumDecoder/CdcConsumer 定向测试，覆盖 create/update/delete/read、tombstone、Kafka Connect Decimal/日期/时间/嵌套结构、topic/source/key 校验、重复/尾随 JSON、逐条确认、失败停点、重投恢复、授权拒绝、暂停/恢复和关闭。完整 Foundry 回归结果记录在阶段报告中。

CDC 仍依赖已有 SchemaBinding、MappingSchemaValidator、字段/关系冲突策略和 transactional receipt。生产 Schema Registry、真实 Kafka 连接、CDC 延迟/吞吐、断线重连、rebalance、死信/运维告警、自动调度及国产目标库验收继续；完整上游核心覆盖目标保持 active。Mirror 运行库和服务 JAR 未改动。
