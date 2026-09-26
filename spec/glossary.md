# 术语

| 术语 | 定义 |
|---|---|
| Object | 具有稳定类型和 ID 的领域实体或事件实例。 |
| Link | 两个 Object 之间有方向、类型、基数和属性的关系实体。 |
| Object Type | 对象类型定义，包含属性、约束、索引和关系引用。 |
| Link Type | 关系类型定义，声明起点类型、终点类型、基数和关系属性。 |
| State | 对象或关系在某一时点的可查询状态。 |
| Version | 同一对象或关系的单调递增版本号。 |
| Valid Time | 状态在现实业务中有效的时间区间。 |
| Recorded Time | 平台接收并记录该事实的系统时间。 |
| Transaction | 一组必须原子提交的对象、关系、历史、审计和 Outbox 变化。 |
| Action | 经过校验、授权、前置条件和审计的业务写入命令。 |
| Domain Pack | 领域模型、Action、权限、同步和种子数据的可组合描述包。 |
| Compiled Ontology | Domain Pack 编译后的运行时模型。 |
| Storage Provider | 实现对象、关系、历史、事务和查询能力的存储适配器。 |
| Source of Truth | 某一字段或关系的权威来源系统。 |
| Overlay | 不在本地持久化、从外部系统读取并按 TTL 缓存的只读投影。 |
| Outbox | 与业务事务同库写入、之后可靠发布为事件的待发布记录。 |
| Provenance | 数据来源、转换、计算和变更依据的记录。 |
| Current Projection | 从最新有效状态投影出的高频查询视图。 |
| History Snapshot | 某对象或关系某一版本的不可变完整快照。 |

