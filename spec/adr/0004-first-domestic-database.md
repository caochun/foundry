# ADR-0004：第一适配目标选择 openGauss

## 状态

Accepted for Foundation v0.1，生产支持待现场验证。

## 背景

Foundation 需要国产关系数据库 Provider。当前 JDBC 引擎使用标准事务、普通索引、时间字段、CLOB/TEXT 属性存储和可选递归查询，目标数据库还必须支持 Java JDBC 驱动和 PostgreSQL 风格的分页/时间语法。

## 决策

第一适配目标选择 **openGauss**。Provider 复用 JDBC 引擎和 PostgreSQL 兼容方言，使用独立驱动与连接配置，不把 PostgreSQL 扩展或 Apache AGE 作为运行前提。

## 最小支持能力

- 事务和回滚；
- 组合主键和普通索引；
- `TIMESTAMPTZ`；
- `TEXT` 属性 JSON；
- `LIMIT/OFFSET` 分页；
- 当前表、历史表和 Outbox 表；
- 对象、关系和双时态查询。

## 验证要求

拿到目标 openGauss 版本、JDBC 驱动和连接参数后，必须运行 `foundry-conformance` 全部测试。测试通过前只能称为“方言适配”，不能称为生产支持。

