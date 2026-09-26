# 事件、Outbox、审计与血缘

## Outbox

业务事务在同一个数据库事务中写入：

```text
对象当前状态
对象历史快照
关系当前状态
关系历史快照
审计记录
outbox_event
```

Outbox Worker 在事务提交后发布 CloudEvent。发布失败可以重试，重复发布由事件 ID 和消费者幂等键处理。

## 事件

事件至少包含：

```text
specversion
event_id
event_type
tenant_id
subject
time
transaction_id
action_id
actor_id
data
```

对象和关系变化必须分别有创建、更新和终止事件。事件数据不应泄露调用者无权查看的敏感字段。

## 审计

审计记录是追加式数据，至少包含：

- 操作主体和主体类型；
- 租户和组织上下文；
- 操作类型、Action 类型和目标；
- 操作时间、trace_id、transaction_id；
- 变化前后快照或摘要；
- 结果、错误原因和来源系统。

审计表不得提供普通更新和删除权限。

## 血缘

对同步、计算或人工录入的字段，应记录：

```text
source_system
source_record_id
source_version
transformation
produced_at
produced_by
value_hash
```

