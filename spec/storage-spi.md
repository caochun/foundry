# Storage SPI 与关系数据库实现

## SPI 责任

Storage Provider 负责：

- Schema 应用和迁移元数据；
- 对象当前状态和历史；
- 关系当前状态和历史；
- 过滤、分页、聚合和搜索；
- 时间查询和关系遍历；
- 事务和乐观并发；
- 租户隔离；
- 健康检查和能力声明。

引擎不得直接拼接数据库专有 SQL，也不得绕过 Provider 访问数据库。

## JDBC 实现

首期采用 JDBC Provider，按数据库方言封装：

```text
DatabaseDialect
Schema DDL Generator
Query Builder
Transaction Adapter
Temporal Query Adapter
Index Adapter
```

核心 SQL 只使用各目标数据库共同支持的能力：事务、主键、普通索引、时间字段、递归 CTE（若目标库支持）。JSON、全文检索和图遍历属于可选能力，必须通过 `StorageCapabilities` 声明。

## Provider 能力

Provider 必须明确声明：

```text
transactions
temporalQueries
fullTextSearch
recursiveTraversal
bulkMutations
jsonProperties
replication
```

上层 API 根据能力降级或隐藏相关接口，不能假设所有数据库都支持 PostgreSQL 特性。

## 历史和关系表

每个对象类型和关系类型至少有当前表和历史表。历史写入与当前写入必须在同一事务中完成。对象和关系的删除默认使用软删除；物理删除必须是受控的保留策略操作。

## 并发

更新必须支持 `expected_version`。版本不匹配返回明确的并发冲突错误，不能静默覆盖其他事务的变更。

