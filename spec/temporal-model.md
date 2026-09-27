# 对象与关系的历史模型

## 双时态

Foundation 采用双时态模型：

- **Valid Time**：事实在现实业务中生效的时间区间 `[valid_from, valid_to)`；
- **Recorded Time**：平台记录该事实的时间，由平台服务端记录事实时分配；事务提交后才可见，不等同于数据库物理提交时点。

二者允许表达“9月10日才收到，但事实从9月1日起生效”的补录场景。

## 历史表

对象和关系各自拥有历史表。历史记录至少包含：

```text
history_id
tenant_id
entity_type
entity_id
version
operation
valid_from
valid_to
recorded_at
transaction_id
action_id
actor_id
source_system
snapshot
```

`snapshot` 可以由生成的类型字段组成，也可以在 Provider 中以结构化 JSON 保存；业务查询不能依赖某一种数据库的 JSON 类型。

## 一致性规则

1. 创建、更新和终止都必须写入历史快照。
2. 同一事务中的对象和关系变化共享 `transaction_id`。
3. `version` 在同一实体内单调递增，不能复用。
4. 历史记录只能追加，不能更新或物理删除。
5. 软删除表示当前不可用，不表示历史消失。
6. 当前有效关系的基数约束只作用于未终止关系。
7. 时间查询必须明确使用业务有效时间还是记录时间。

## 查询接口

Storage SPI 至少提供：

```text
getObjectAtVersion
getObjectAtTime
getLinkAtVersion
getLinkAtTime
getLinksAsOf
traverseAsOf
getEntityHistory
```

`traverseAsOf` 的每一步都使用同一时间语义，不能只把终点对象回溯而让中间关系仍使用当前状态。

## 关系遍历

关系数据库 Provider 应优先使用关系表、历史表和递归 CTE 实现遍历。图数据库或图扩展可以作为当前关系投影，但不能成为历史事实的唯一来源。


## 当前实现：格式2转换语义

详见[ADR-0005](adr/0005-temporal-transitions-and-legacy-history.md)和[升级边界](temporal-migration.md)。历史行保存完整状态断言而不是原地关闭前行：在指定validTime内，选择recordedAt不晚于查询获知时点的最新断言，同recordedAt按version消歧。删除也参与选择，选择结果为DELETED时不能回退到较早有效行。

时间单查返回HistorySnapshot，包括DELETED断言；这是审计状态，调用方判断operation。列表默认排除删除，includeDeleted返回对应已终止投影。历史快照的validTo保持原断言的区间，不代表已经把所有后继断言归并成互不重叠的区间表。

对象和关系的Transaction.create/update/delete均增加effectiveAt重载。省略时采用当前记录时刻；显式值支持不晚于记录时刻、且不早于该实体上次生效时点的迟到事实。业务有效时间精度为微秒，超出精度明确拒绝而不静默截断；服务端记录时间统一截为微秒。未提供任意历史区间更正、未来生效或物理提交时间回溯保证。

关系同一时点创建又解除允许形成空生命周期区间，isValidAt永不命中；历史通过version保留先后。关系显式补录校验过去端点存在及历史基数重叠。多进程竞争基数另需加强，不能由串行验证推断已满足。

queryObjects/getLinks通过QueryOptions同时指定asOfValidTime和asOfRecordedTime使用时间投影；单独设置一个参数会拒绝。历史遍历的每步和起点/终点均使用同一时间状态，终止端点不进入有效路径。JDBC遍历使用同一只读REPEATABLE_READ连接，当前实现仍有历史扫描，规模查询优化未完成。
