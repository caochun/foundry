# ADR-0021：持久Schema Registry与登记门禁

日期：2026-09-28。状态：注册表及登记/启动检查契约已实现；存储激活、迁移执行及在途写入门禁待接入。

## 上游依据和当前缺口

固定上游v0.3.0 / 1d7e1aa的`packages/storage-postgres/src/schema-registry/postgres-schema-registry.ts`持久保存ParsedSchema版本、diff和分类，用事务及PostgreSQL advisory lock串行分配版本，破坏性登记需要migrationPlan.approved。

`packages/api/src/schema-registry-boot.ts`在启动时检测变化，但会自动批准迁移说明以记录破坏性变更。其注释明确表示这是记录，不是启动门禁。Java不能据此声称底层数据迁移已经执行，或自动批准生产变更。

Java原SchemaRegistry只有内存版本，JdbcStorageProvider.applySchema仅维护进程内模型。本阶段补齐持久注册表与可调用的登记/启动校验，不把它们与存储迁移混为一谈。

## 注册表契约

保留current、atVersion、history、apply；新增：

- `currentVersion()`：空注册表为0；current/不存在的atVersion仍明确失败。
- `apply(schema, plan, expectedVersion)`：原子比较版本并登记；expectedVersion=0要求空注册表。不匹配抛SchemaVersionConflictException，不能用基于旧模型的计划覆盖新版本。
- `applyIfChanged(schema, plan)`：在同一临界区比较规范指纹，相同模型不生成新版本；并发启动不会重复登记。显式apply仍沿用上游每次生成新版本的行为。
- 四参数apply供需要同时指定预期版本和变化去重的可信宿主使用，预期版本检查先于去重；过期计划不会因为配置恰好相同而被接受。
- `requireCurrent(configured)`：验证配置模型并与当前登记模型比较，不一致或尚未登记时抛SchemaDriftException。返回所验证的SchemaVersion，供宿主绑定启动产物。

自定义SchemaRegistry实现需要实现新增四参数原子入口；已有调用签名保留，不以客户端先读版本再写入代替原子检查。

所有新登记先执行SchemaCompiler校验，approved=true不能绕过无效模型。BREAKING仍需显式批准的MigrationPlan。SchemaVersion新增migrationPlan以保留当时的说明和批准标志；五参数构造器继续兼容。审批标志来自可信部署/治理调用方，本阶段未提供对外的模型管理HTTP接口或审批工作流。

示例：

```java
SchemaRegistry registry = new JdbcSchemaRegistry(dataSource, dialect);
SchemaVersion current = registry.applyIfChanged(configuredSchema, null);
registry.requireCurrent(configuredSchema);

// 由可信部署流程提供已经审查的计划，并绑定审查时的版本。
registry.apply(nextSchema, reviewedPlan, current.version());
```

上述登记不会调用StorageProvider.applySchema，也不会自动回填或更改业务数据。

## 独立模型指纹

新增SchemaFingerprint v1，用不歧义的类型化规范表示计算SHA-256：顶层对象/关系/动作/接口按名称排序，映射键规范化，嵌套声明列表保持顺序。

必须保留Action参数顺序：ONTOLOGY_TARGETS依首个对象参数选权限主目标。已有SchemaCompiler摘要为兼容旧编译产物/回执而对参数排序，不能用它判断登记模型是否完全等价。新注册表指纹不修改旧编译摘要或回执格式；参数顺序变化可被漂移检查识别，SchemaDiffer也按动作声明变化分类为BREAKING。

属性/枚举等嵌套顺序保守保留，避免漏掉API声明或排序优先级差异。顶层类型排列变化不产生虚假启动版本。namespace变化新增BREAKING分类，声明version变化新增SAFE元数据diff；diff输出按路径/说明稳定排序。

## JDBC存储与恢复

`JdbcSchemaRegistry`位于foundry-storage-jdbc，该模块新增对foundry-schema的依赖。注册表独立于对象表初始化：

- `of_schema_heads`保存registry_key、current_version及当前指纹。
- `of_schema_versions`追加完整Schema快照、快照指纹、登记时间、diff、classification、MigrationPlan、格式版本及完整行校验和。

registry_key为可信部署范围，默认default，可在一个数据库隔离多个模型注册表；不是客户端请求的租户ID。实际存储激活时如何映射部署/租户仍需单独绑定，不允许把此范围隔离当成业务授权。

同一registry_key的读取/追加使用数据库行级FOR UPDATE锁，READ_COMMITTED确保等待后看到最新提交。快照追加和head更新同事务，失败回滚；初始化单独使用并关闭连接，支持单连接池及默认非自动提交的DataSource。无进程内全局锁替代数据库串行化。

读取校验格式、连续版本号、行校验和、Schema指纹、已保存diff/classification内部一致性、破坏性登记的审批证据及head/history一致性。缺行、损坏、未知格式或头指针不一致会拒绝，不自动覆盖或“修复”历史。历史diff保留登记时的判断，不用后续版本的SchemaDiffer重新裁定既有记录；新登记仍按当前编译器和分类器检查。

登记时间按注入Clock保存完整Instant字符串，表示登记过程取时，不宣称物理提交时刻。JSON仅存record字段，默认值、接口、枚举、关系投影、计算声明和Action参数均完整保留；未知快照字段/格式不静默丢弃。

## 边界与后续门禁

**登记成功不是迁移执行成功。** MigrationPlan目前仍是审查说明和标志，不是SQL迁移脚本或数据校验回执。requireCurrent是调用时的启动检查，不锁住随后StorageProvider操作。

JdbcStorageProvider.applySchema尚未自动接入注册表；跨部署旧模型写入仍需后续的持久版本绑定、锁顺序、存储激活与在途事务栅栏。存量数据回填/约束校验、物理模式漂移检测、生产启动装配、模型审批权限API和回滚策略均未完成。它们继续列在总计划，不因注册表测试通过而勾选完成。

本阶段只在隔离H2及内存中验证，SQL采用现有DatabaseDialect的文本类型和关系库行锁。实际PostgreSQL/国产库、DDL竞争、运维升级及生产规模仍需独立验收。未修改运行中的业务库或服务JAR。

## 验证

新增17项：5个契约场景分别在内存/JDBC验证，4项JDBC持久化/范围隔离/单连接池/故障/损坏覆盖，3项独立进程测试。包括8路并发启动去重、8路基于同一版本的竞争只接受一项、两个独立JVM竞争、物理提交前/后强制退出、头更新失败导致快照回滚、完整声明回读和审批说明保留。

常规Foundry419项、Mirror根reactor529项全部通过，无失败/错误/跳过。独立探针确认重建注册表后相同启动配置仍为版本1，Action顺序漂移和未批准变更拒绝，批准登记为版本2，旧预期版本拒绝且原始快照完整保留。
