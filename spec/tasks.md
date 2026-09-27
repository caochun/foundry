# Foundation v0.1 任务清单

本清单保留初始实现记录，不代表上游能力完整或生产验收通过。最新缺口与修复状态以[上游对齐修复计划](upstream-parity-plan.md)和ADR-0004为准。

## 规约和工程基线

- [x] 评审并冻结 Foundation Constitution（ADR-0003）。
- [x] 确定 Java 版本、构建工具、代码风格和包命名。
- [x] 确定第一种国产数据库 Provider 和最小支持能力（openGauss；现场连接验证待部署环境）。
- [x] 建立 ADR 模板和变更审查流程。

## Schema 和 Pack

- [x] 定义 Java 版 `ParsedSchema` 和 `CompiledOntology`。
- [x] 实现 ODL 解析、指令解释和错误定位（Foundation v0.1 基础指令）。
- [x] 实现 ObjectType、LinkType、ActionType 校验。
- [x] 实现 Pack manifest、命名空间、确定性加载和依赖版本校验。
- [x] 实现 Schema diff、迁移分类和版本注册（内存 Registry 基线）。

## 对象、关系和历史

- [x] 定义 Object、Link、HistorySnapshot 和 Provenance 类型。
- [x] 定义当前表、历史表和关系基数约束。
- [x] 实现对象版本和关系版本。
- [x] 实现 `getObjectAtTime`、`getLinkAtTime`（内存和 JDBC Provider）。
- [x] 实现 `traverseAsOf`。
- [x] 实现 `valid_time + recorded_time` 双时态查询。
- [x] 实现乐观并发和事务回滚（内存和 JDBC Provider）。

## Action、事件和审计

- [x] 实现 Action manifest 解析。
- [x] 实现参数校验、CEL 前置条件和受限效果执行。
- [x] 实现幂等键、批量结果和基础失败结果。
- [x] 实现审计表和追加式写入。
- [x] 实现 Transactional Outbox（事务内写入和回滚语义）。
- [x] 实现 CloudEvents 发布、失败重试和 JDBC持久化消费者幂等。

## 安全和 API

- [x] 实现 OIDC/JWT 主体和租户提取。
- [x] 实现 OpenFGA 授权适配。
- [x] 实现字段级脱敏。
- [x] 实现通用 ApplicationService 和关系/历史读取入口。
- [x] 实现框架无关的 REST 路由和 Action 路由契约。
- [x] 生成基础 GraphQL 查询/Mutation 契约并提供 GraphQL/JDK REST 运行时适配。

## 同步和一致性测试

- [x] 实现 JDBC/REST 连接器接口和基础读取实现。
- [x] 实现来源映射和字段血缘基础类型。
- [x] 实现来源优先级、时间优先级和 Action 优先级冲突策略基础实现。
- [x] 将 [conformance/README.md](conformance/README.md) 转为 JUnit 测试套件（内存/JDBC基线）。
- [x] 为 PostgreSQL 提供可选的相同测试入口（`PG_TEST_URL`）；国产数据库待现场验证。
- [x] 完成 Person/Organization/Assignment 调动场景端到端测试（内存/JDBC Provider）。
