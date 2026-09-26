# 对象与关系的历史模型

## 双时态

Foundation 采用双时态模型：

- **Valid Time**：事实在现实业务中生效的时间区间 `[valid_from, valid_to)`；
- **Recorded Time**：平台记录该事实的时间，来自数据库提交或事件接收时间。

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

