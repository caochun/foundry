# ADR-0003：Foundation v0.1 评审结论

## 状态

Accepted as the implementation baseline for v0.1。

## 评审结论

1. 对象、关系、当前状态和历史快照是底座核心；标签、风险和提醒属于上层 Domain Pack。
2. 关系数据库保存权威状态，图能力只能作为可重建投影。
3. 所有对象、关系、审计和 Outbox 变化在同一 Storage Transaction 中提交。
4. Action 是生产写入入口，幂等键和期望版本用于防止重复或并发覆盖。
5. OIDC/JWT 负责身份和租户提取，OpenFGA 或等价服务负责关系授权。
6. JDBC Provider 通过 DatabaseDialect 支持 PostgreSQL、openGauss、Kingbase 和达梦；每种数据库必须在目标环境运行共享一致性测试后才可宣布生产支持。

## 未决事项

- 国产数据库的具体版本、驱动、递归查询和 JSON/分页语法仍需在部署环境验证。
- GraphQL/REST 需要接入项目选定的 HTTP 容器和生产序列化策略。
- `of_consumed_events` 的崩溃恢复需要增加租约或重试超时策略。

