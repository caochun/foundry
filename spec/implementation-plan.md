# Foundation v0.1 实现计划

## 阶段一：契约和内核类型

产出：

- Java 多模块工程；
- `RequestContext`、Object、Link、History、Transaction、Event 和 Audit 类型；
- Storage SPI；
- Domain Pack manifest 和 ODL AST；
- 错误码和能力声明。

## 阶段二：Schema 编译器

产出：

- ODL 解析和指令解释；
- Schema 校验；
- Pack 依赖和合并；
- Action manifest 解析；
- Schema diff 和版本注册；
- `CompiledOntology`。

## 阶段三：关系数据库 Provider

产出：

- JDBC 事务适配；
- 对象和关系当前表；
- 对象和关系历史表；
- 当前/历史查询；
- 乐观并发；
- 递归关系遍历；
- PostgreSQL 或第一种批准的国产数据库 Provider。

## 阶段四：Action 和事件内核

产出：

- 参数校验；
- CEL 前置条件；
- 对象和关系效果；
- 幂等和批量；
- 审计写入；
- Transactional Outbox；
- CloudEvents 发布和重试。

## 阶段五：安全和 API

产出：

- OIDC 认证适配；
- 租户上下文；
- OpenFGA 授权适配；
- 字段脱敏；
- GraphQL 查询；
- REST 查询和 Action 接口；
- OpenAPI/GraphQL 契约输出。

## 阶段六：同步和验证 Domain Pack

产出：

- JDBC/REST 连接器；
- 来源和血缘；
- 冲突解决；
- Person/Organization/Position/Assignment 示例包；
- Provider 一致性测试和端到端测试。

## 技术策略

首期采用模块化单体。API、Action、同步和事件 Worker 可以独立部署，但共享明确的 SPI 和数据库事务边界。只有性能、隔离或运维需求得到验证后，才拆分为独立服务。

