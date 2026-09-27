# Open Foundry Java Foundation 规约

这是 Open Foundry Java 版数据底座的 Foundation v0.1 规约。

本目录记录平台必须保持稳定的概念、数据语义、接口契约和架构决策。具体领域的标签、风险判断、提醒匹配和业务流程属于上层 Domain Pack 与应用，不属于本规约的底座范围。

## 最新修复基线

- [上游对齐修复计划](upstream-parity-plan.md)：取代旧任务勾选作为当前完成度依据。
- [ADR-0004](adr/0004-upstream-parity-and-governed-boundary.md)：通用执行治理、字段策略和兼容迁移。

## 阅读顺序

1. [constitution.md](constitution.md)：不可违背的设计原则和范围边界。
2. [vision.md](vision.md)：Foundation v0.1 的目标、使用场景和验收结果。
3. [glossary.md](glossary.md)：统一术语。
4. [ontology-model.md](ontology-model.md)：对象、关系和标识模型。
5. [temporal-model.md](temporal-model.md)：对象和关系的历史与双时态语义。
6. [action-model.md](action-model.md)：受控写入、事务和副作用。
7. [schema-compiler.md](schema-compiler.md)：Domain Pack 到运行时模型的编译过程。
8. [storage-spi.md](storage-spi.md)：关系数据库存储抽象和方言适配。
9. [authorization.md](authorization.md)：租户、关系权限和字段脱敏。
10. [event-and-audit.md](event-and-audit.md)：Outbox、事件、审计和血缘。
11. [sync-model.md](sync-model.md)：外部系统同步和来源管理。
12. [api-contract.md](api-contract.md)：API 和内部服务边界。
13. [implementation-plan.md](implementation-plan.md)：分阶段实现计划。
14. [tasks.md](tasks.md)：可执行任务清单。

关键决策记录在 [adr/](adr/)；可执行的一致性要求记录在 [conformance/](conformance/)。

## 规约状态

- Foundation v0.1：讨论稿，作为实现前的共同基线。
- `MUST` 表示实现必须满足的约束。
- `SHOULD` 表示默认方案；偏离时需要补充 ADR。
- `MAY` 表示可选能力。
