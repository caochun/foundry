# 对象与关系模型

## 对象

每个 Object Type 必须声明且只能声明一个主标识字段。运行时使用平台内部 `object_id` 作为稳定标识；业务主键、身份证号、手机号或外部系统主键只能作为属性或来源映射，不能直接承担平台主键职责。

对象的系统字段至少包括：

```text
tenant_id, object_type, object_id, version,
created_at, updated_at, deleted_at,
last_transaction_id, last_action_id
```

业务字段由 Domain Pack 声明。字段可以附带必填、唯一、索引、敏感、只读、不可变和约束等元数据。

## 关系

每个 Link Type 必须声明：

- `link_id`：关系实例的稳定 ID；
- `from_type`、`from_id`：起点；
- `to_type`、`to_id`：终点；
- `cardinality`：一对一、一对多、多对一或多对多；
- 关系属性；
- 当前有效状态和历史生命周期。

关系是独立实体，可以拥有例如 `started_at`、`ended_at`、`role`、`source_system` 等属性。`@link` 字段只是对关系的查询引用，不是关系的存储替代品。

## 关系生命周期

- 创建关系产生新的 `link_id`。
- 修改关系属性保留 `link_id`，版本递增。
- 关系端点发生变化时，终止旧关系并创建新关系；不能原地改写端点。
- 终止后再次建立同类关系，默认生成新的 `link_id`。
- 误操作恢复必须作为有审计的 Action，不得删除历史记录。

## 当前投影与历史事实

当前表只保存最新状态，用于列表、权限和关系遍历。历史表保存每次变化前后可重建所需的完整快照。业务查询默认只返回当前有效对象和关系；历史查询必须显式提供版本或时间参数。

## 领域对象映射示例

底座可以承载以下对象，但不解释它们的业务规则：

```text
Person, Organization, Position, Assignment,
Project, RiskEvent, Tag, ReminderTask, DeliveryRecord
```

示例关系：

```text
Person --belongsTo--> Organization
Person --holds--> Position
Person --participatesIn--> Project
RiskEvent --involves--> Person
Person --hasTag--> Tag
Person --receives--> ReminderTask
```

