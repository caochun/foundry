# ADR-0032：连接器生命周期、JDBC提取与数据源单次运行

日期：2026-09-28。状态：JDBC受控全量/轮询执行与持久游标恢复已实现；CDC、overlay及完整调度继续。

## 上游依据

固定上游v0.3.0的sync/connectors定义initialize/shutdown/healthCheck、discoverSchema、fullExtract/incrementalExtract、pause/resume及可选write。ConnectorRegistry登记插件元数据与工厂。JDBC实现使用PostgreSQL：全量按ctid/LIMIT/OFFSET分页，增量按updated_at > since读取；REST实现的提取和模式发现仍是TODO。server.ts只提供健康端点，不能视为完整数据源调度器。

Java增加ManagedConnector、ConnectorRegistry、JdbcSourceConnector和DatasourceRunner。已有Connector.read、JdbcConnector可信SQL读取、RestConnector实际HTTP读取继续兼容；新运行装配选择JdbcSourceConnector，不把旧查询入口自动解释为增量协议，也不以空REST提取器冒充完成。

## 生命周期与所有权

ManagedConnector显式提供初始化、健康检查、Schema发现、提取能力、全量/增量流、分区、暂停/恢复和close。状态为NEW→READY→CLOSED，不能重复初始化或关闭后重用；初始化失败可重试。close幂等，唤醒暂停/限速等待者并取消正在执行的JDBC语句，资源由提取线程的finally释放。中断恢复线程标记并停止提取。

流必须在try-with-resources中使用，包括findFirst/limit等短路消费；耗尽和失败也自动释放提取状态。按需获取下一批；每批最多batchSize条，读完即关闭ResultSet、Statement和Connection，再交给目标事务消费。因此来源和目标即使共用单连接池，也不会因持续持有源游标而互相等待。实际数据库的查询计划/驱动缓冲和吞吐仍需验收。

宿主提供并拥有DataSource池、凭证及连接端点解析，连接器关闭不关闭宿主池。默认查询超时30秒，batchSize默认1000、范围1..10000；连接获取/网络超时由宿主池及驱动设置。pause为协作式控制，不能撤回已经发出的行。限速按提取流控制，跨进程总速率治理尚未实现。

ConnectorRegistry支持登记/查询/移除、重复拒绝、插件版本和分区，以及工厂创建新实例。jdbc工厂不自行读取环境变量或打开网络；DatasourceRunner在通用权限、映射及检查点预检之后才调用工厂。运行时固定本次插件快照，校验实例名/版本/分区一致，失败也关闭实例。自定义工厂属于可信宿主代码。

## JDBC模式发现与提取

通过DatabaseMetaData发现配置Schema中的表、列、JDBC类型、可空性和有序主键。配置接受table或schema.table，标识符校验、按驱动规则折叠大小写并引用；数据参数使用PreparedStatement。映射字段名应匹配发现的实际列名，不能假定所有驱动返回小写。

当前keyset提取要求一个文本或精确数字主键，不再从“第一列”猜身份。可用keyColumn显式核对主键；复合/近似浮点/其他主键类型尚待扩展。全量按主键排序，从上一条主键继续，不使用OFFSET；单次全量不是跨批一致快照，也没有可持久续读的事件位置，重新运行会重新扫描。

轮询要求非空TIMESTAMP或TIMESTAMP_WITH_TIMEZONE列，默认updated_at，可通过watermarkColumn配置。无该列时仍能全量，不能增量。使用(timestamp, primaryKey)联合游标，查询严格晚于上一对值的记录；同一时间的其他行不会因只保存时间而跳过。无时区timestamp按UTC读取/绑定，有时区值归一化为Instant；精确数字不先变成double。

来源必须保证主键稳定、每次变化更新水位，且不会在已发布游标之前迟到发布记录。仅靠表轮询无法发现物理删除、同一行在两次轮询之间的全部变化、同水位原地改写或迟提交的旧水位记录。时间回拨/不满足该契约时需要对账或CDC；不得把这些条件下的轮询称为无损变更捕获。

## 游标、事件与配置身份

增量SourcePosition包含固定分区、由来源计划/主键/更新时间派生的事件ID、从持久检查点继续的sequence及不透明token。token保存格式、来源签名、主键、更新时间和sequence。来源产生时间使用水位时间，读取时间独立，避免重启时把同一事件改成新指纹。

来源签名绑定连接声明、已解析的JDBC端点/用户、实际Schema/表、列类型与主键；仅更换同名环境变量指向的数据库也不能沿用旧游标。宿主仍负责逻辑来源身份及恢复/重建策略；数据库在同一端点原地重建不是JDBC元数据能自动证明的同一来源，需要显式配置版本/迁移。

DatasourceRunner配置摘要绑定datasource、插件/版本/分区、URL/table/properties、模式、映射/变换版本与冲突策略。分区不因表名改变而自动换名，已建立的轮询管道改变表/映射会明确拒绝，不能悄悄开始一套空检查点。来源元数据漂移在初始化后的游标签名校验拒绝。连接声明和凭证不保存到回执中，存储的是摘要；摘要不是防篡改签名。

MaterializedSyncService新增withSourceConfiguration，默认空配置保留已有对象/关系回执与检查点摘要。来源配置一旦绑定，复制服务的权限/时钟/变换选项仍保留该绑定。

## 单次运行装配

```java
var definition = new MappingConfigParser().parse(yaml);
var registry = ConnectorRegistry.jdbc(connection -> hostDataSource(connection));
var runner = new DatasourceRunner(storage, registry, sourceAuthorization);
var outcome = runner.runOnce(definition, context);
var committed = runner.checkpoint(definition, context);
```

runOnce显式执行一次BATCH或POLLING；POLLING从存储中读取已提交检查点，BATCH执行全量。自动采用声明的冲突策略，来源优先级由宿主构造器提供；未知历史仍默认拒绝改写。声明限速与调用选项取更严格的值。interval保留为未来/宿主调度配置，runOnce本身不创建周期任务。

通用同步层在来源工厂执行前检查权限、Schema字段、自定义函数、映射及配置身份。逐条对象/关系、来源、审计/outbox、回执和检查点仍共用既有事务。失败记录不推进游标，也不处理后续记录；已完成的此前事务保留。来源查询失败作为异常传播，宿主仍可从最后持久检查点恢复。CDC/OVERLAY、writeback=true或缓存选项在本入口明确拒绝，不能静默当作物化模式运行。

并发运行同一稳定源序列可重放已提交事件；不提供跨来源和目标的分布式快照。可变源表被并发改写导致不同读取顺序/内容时，冲突回执或乱序检查会拒绝，需重新从持久游标读取，不能用轮询序号冒充数据库日志LSN。

## 验证与后续

测试覆盖元数据发现、分批顺序/精确数值/时区、同时间游标、更新、外来/错误游标、关闭/暂停/恢复/中断、限速、源查询错误、单连接池、memory/JDBC事务失败续读、权限与配置门禁、租户隔离、冲突策略及持久Provider重建。独立JVM验证物理提交前后退出恢复，以及两个进程从同一检查点竞争读取时只写一次。

新增35项；常规根reactor907项，其中Foundry797项，全通过，无失败/错误/跳过，不含以前运行的OpenFGA集成报告。

独立审计探针验证：首条写入后第二条失败停在检查点1，后续记录未写；修复同时间记录后新增剩余两条并停在检查点3，首条对象版本仍1，空轮询无变更，改变来源表拒绝。

REST托管适配/分页、复合主键、自动调度/任务状态/死信、CDC解码/背压、overlay/writeback、Pack自动启动、生产源系统/真实目标数据库及性能验收继续。完整上游核心覆盖目标保持active。Mirror运行库及服务JAR未改动。
