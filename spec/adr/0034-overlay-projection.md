# ADR-0034：Overlay 源投影与 TTL 缓存

日期：2026-09-28。状态：只读 read-through overlay、TTL 缓存和托管连接器生命周期已实现；关系投影、writeback 和一致快照继续。

## 上游依据与边界

上游 `overlay-engine.ts` 将源记录映射为 ontology-shaped object，按 TTL 缓存，缓存未命中时实时查询连接器，并拒绝 mutation；overlay 对象不产生 ontology history 或 lineage storage。Java 保留这一层语义，并明确把缓存值当作响应投影，不把它混入 `StorageProvider`。

`OverlayEngine` 要求 datasource mapping 的 mode 为 OVERLAY，默认 TTL 为 PT5M，允许配置最长30天。当前只读引擎拒绝 writeback=true；关系映射需要单独的受控关系读取器，不能把源记录里的 link 当成已授权本地关系。`ManagedOverlay` 从可信 ConnectorRegistry 创建连接器、初始化、检查 fullExtract 能力，并在关闭时先清缓存再关闭连接器。

## 读取与缓存

`get(key)` 先规范化 key（数值1与字符串"1"具有相同查询身份）并计算表/键缓存键。命中且当前时间早于 expiresAt 时直接返回同一不可变对象，不访问连接器；过期或未命中时消费源连接器流，按 mapping 的 primary key 字段匹配记录，执行既有 RecordMapper，再缓存映射结果。DELETE 或未找到记录会返回 null，并清除同键旧缓存。

缓存值包含 objectType、映射后的 ID、不可变属性、connector/sourceSystem/sourceRecordId/provenance、cachedAt 和 TTL。它反映读取时刻的源视图，不声称是事务快照；源数据在缓存期间变化时仍返回旧值。`clearCache` 强制下一次读取源端；关闭后任何读取都失败，缓存不会泄露到下一个生命周期。

源连接器流使用 try-with-resources，短路/异常也释放资源。普通 Connector 使用 `read(SourceQuery)`；ManagedConnector 使用其受控 `fullExtract`，因而按批读取并遵守连接器的生命周期/限速边界。OverlayEngine 不打开数据库、不启动调度、不写 checkpoint，也不自动连接外部系统。

## 只读与来源安全

`mutate` 永远抛出 `OverlayReadOnlyException`。投影对象的属性和 lineage 均不可变；任何本地 Action/对象事务必须在进入 overlay 层前拒绝，不能通过缓存 API 绕过权限。缓存键只依赖声明表和调用方传入 key，宿主仍需在调用前执行租户/字段/源端授权；Java 不把缓存命中当作授权证据。

缓存不持久化，进程重启后重新读取源端。当前实现没有跨节点共享缓存、失效广播、stale-while-revalidate、ETag/版本条件读或源端一致快照；部署多个实例时每个实例拥有独立 TTL。

## 验证

新增 OverlayEngine/ManagedOverlay 测试，覆盖映射读取、数值/字符串键规范化、命中/过期/清除、缺失与删除、只读异常、不可变 lineage、错误模式/writeback 及托管 JDBC 来源；Foundry 现有回归语义不变。

关系投影、写回审批/冲突、缓存容量/内存回收、真实 REST/国产数据库、分布式失效和生产规模性能仍待完成。完整上游核心覆盖目标保持 active。Mirror 运行库和服务 JAR 未改动。
