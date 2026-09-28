# ADR-0029：事务血缘、来源冲突与可恢复同步

日期：2026-09-28。状态：对象/关系字段血缘、Action与补偿来源、受控读取、对象同步冲突及事务事件回执/检查点已实现；完整连接器与映射运行装配继续。

## 上游证据

固定上游v0.3.0 / 1d7e1aa提供：

- spi/provenance.ts：ACTION/SYNC/FUNCTION来源及字段valueHash。
- engine/lineage：可选LineageRecorder、内存查询存储，ObjectManager创建/更新时记录字段变化。
- sync/conflict：LAST_WRITE_WINS、SOURCE_PRIORITY、ACTION_PRIORITY及字段覆盖规则；同优先级回退时间比较。
- sync/cdc：CdcConsumer通过changeApplier应用记录，再周期性另存Checkpoint；处理失败仍继续后续记录。

Java此前仅有Provenance DTO、未接入的ConflictResolver和逐条覆盖对象的MaterializedSyncService。此阶段把来源与真实事实/动作接通，并加强一致性：事实、血缘、处理回执、检查点和审计/outbox在同一个存储事务内提交；失败停止该输入流，不越过坏记录推进游标。没有照搬上游事务外追加血缘、缺租户参数的内存血缘查询或失败后继续推进检查点的行为。

## 事务来源与字段证据

MutationSource显式标识DIRECT/ACTION/SYNC/FUNCTION，记录来源名称、操作ID、生产时刻和不可变扩展资料。事务第一次事实或来源观测写入之后不能切换来源。没有根据actor名称或外部系统名称中的action前缀猜测来源种类。

FieldProvenance包含租户、实体、字段、实体内sequence、对应事实version、存在标记、规范值SHA-256、记录时刻、事务/操作者和MutationSource。记录不另存字段明文副本。查询按追加sequence倒序，而非可能回退的来源时间；beforeSequence用于稳定翻页。null与不存在分别哈希，Map键顺序及同值数字容器不改变规范值。

内存状态把血缘一并复制/发布，JDBC增加of_field_lineage并复用事实事务连接与租户写锁。字段属性的真实变化、默认/受管理字段的生成、删除和恢复会留下记录；来源表示引发本次写入的事务原因，不声称默认值由外部原始记录直接提供。旧数据不会被回溯编造成某个来源。

内部字段_entity表示对象/关系的存在断言。创建、删除、恢复记录其变化；普通属性更新不替换其来源。来源观测也可以明确确认当前存在状态。尚无事实行的删除消息可以形成entityVersion=0的缺席断言，防止迟到消息复活该身份，且不伪造缺少必填字段的对象。该内部断言不出现在普通公开字段血缘中。

Transaction.recordProvenance可以确认当前实际值/缺席，不增加事实版本，要求匹配当前版本并校验字段。它供可信引擎记录来源观测，不能借此写入另一个字段值。FUNCTION来源及输入引用可由可信调用者提供；尚未自动执行任意函数或推导完整依赖图。

## Action和恢复

支持transactionalLineage的Provider接收ActionExecutor给出的ACTION来源；当前记录lastActionId、历史actionId及字段记录互相对应。显式把字段赋成同一个值也记录Action重新断言，避免同步误把人工确认当作可自由接管的数据。

外部副作用失败时，补偿追加ACTION/COMPENSATION记录，保留原EXECUTION证据；补偿失败回滚本次恢复的事实和血缘。恢复值的本次写入者是补偿Action，不伪造为原外部系统重新写入；ACTION_PRIORITY据此处理其后来源。直接SPI写入默认DIRECT，不能根据调用者昵称假定是Action。

新增restoreObject保留ID、createdAt和连续版本，清除删除状态并追加RESTORED。恢复遵循当前必填/类型/约束/唯一/不可变/受管理字段规则；未提供的值沿用保存的对象状态。关系的活动状态仍由其自身历史决定。restoreObjectProperties继续用于活动对象的属性补偿，不代替对象重新激活。

## 同步授权、映射与冲突

MaterializedSyncService现在默认拒绝，必须配置SyncAuthorizer，并使用具有操作者的RequestContext。策略在连接器I/O之前检查整个管道，在事务内及提交前检查目标。身份认证与具体授权后端由可信宿主接入，此服务不接受HTTP提交的授权函数。

```java
var resolver = new ConflictResolver(ConflictResolver.Strategy.ACTION_PRIORITY,
        Map.of("name", ConflictResolver.Strategy.SOURCE_PRIORITY),
        Map.of("hr", 0, "legacy", 1));
var sync = new MaterializedSyncService(storage, resolver).withAuthorization(syncPolicy);
var checkpoint = sync.checkpoint(connector.name(), mapping, "partition-0", context);
// 增量适配器负责把 checkpoint.token() 用于自己的提取请求。
var result = sync.sync(connector, query, mapping, context);
```

一次任务固定SchemaBinding。映射类型、目标字段、重复目标、受管理字段以及冲突规则中的未知/主键/受管理字段在读来源之前验证。每条记录在事务内读取当前事实和最新字段来源，重试内存并发冲突；模型切换使旧任务失败，不自动改用新模型。

当前映射支持字段重命名和已声明标量规范化。缺少来源字段表示未报告，不转换为null；显式null才清空允许为空的属性。数字主键规范化为稳定字符串，映射主键必须与实体身份一致。标准JDBC日期/时间、UUID转为JSON表示；REST保留高精度数字。未知操作和不可冻结值明确拒绝。

ConflictResolver的规则：

- LAST_WRITE_WINS比较生产时刻；相同时接受新输入。
- SOURCE_PRIORITY支持默认及每字段优先级，数值越小越优先，已列出的来源优先于未列出来源；同级回退生产时刻。
- ACTION_PRIORITY依据已记录的来源种类保护Action写入；同类来源仍比较生产时刻，不根据字符串伪装Action。
- 相等值可以表示一致，但低优先级输入不能取得原值的来源控制权。合法新观测可更新血缘而不制造新事实版本。
- 存在状态与属性分开裁决；修改一个字段不会夺走受保护的存在断言。删除/恢复作为整体通过规则，避免只删除部分属性。受管理属性由存储引擎生成，不作为外部冲突输入。

已有值没有血缘或哈希与当前值不符时，默认REJECT_CHANGES阻止首次改变。TRUST_UPDATED_AT是显式采用旧数据的选项，用当前事实时间参与裁决，并不把旧操作者猜成Action。也可先通过受控迁移建立有据的新观测。已有已删除对象的未知删除来源同样不能被悄悄反转。

冲突决策与审计/outbox同事务。事件区分policyAccepted和实际applied，整体生命周期被拒绝时不会把单字段的许可误报为已执行。输出不携带冲突字段明文值。普通事实变更、来源观测、被策略保留分别有统计；重放不重复计算新增变更。

## 回执与检查点

SourceRecord原构造器仍可使用；新增可选SourcePosition(partition,eventId,sequence,checkpoint)。checkpoint保留字符串、数字或JSON对象，不按字符串比较位置。sequence由连接器提供，必须在分区内单调递增，允许原生偏移存在间隔；它不是从墙上时钟或行号猜测的顺序。

回执身份按租户、管道名称、目标类型及分区事件ID划分。没有SourcePosition但有sourceVersion时，使用来源系统/记录ID/版本去重。回执共享给有权限的工作进程，不绑定首次交付者。请求摘要绑定标准化值、操作、来源生产时刻/声明元数据、位置及映射/冲突策略配置；同ID不同内容拒绝。工作进程和本次runId不改变事件身份。

of_ingestion_receipts、of_ingestion_checkpoints与事实/血缘同事务。重放先验证回执、当前事实版本/摘要及已保存的检查点，再重新验权；不重新套用旧来源值。未识别的旧或相同sequence拒绝，不假定是重复消息。任何处理失败停止当前流并关闭资源，后续消息不处理；下一次从已提交的opaque token恢复。

检查点用expectedVersion更新，绑定来源系统、映射和冲突策略。改变这些配置需要明确的新管道或迁移，不能静默沿用旧位置。这是分区内配置门禁，不等于全系统部署epoch，也没有覆盖所有连接器连接/提取配置。

无位置、无版本的记录仍可作为快照输入：相同值合并、不增加事实版本，新的观测可记录来源新鲜度。这种输入没有可靠事件去重/排序承诺。现有JDBC/REST read仍是快照读取；持久检查点不会自动把它们变成CDC。增量适配器须提供稳定事件位置并使用查询到的游标。

## 血缘读取与隐私

可信SPI可查询完整证据，事务内latestLineage不截断字段集合。ApplicationService.lineage和GET `/api/v1/{type}/{id}/lineage?field=name&limit=100&before=123`提供受控读取：

- 对象/关系查看权限、当前Consent和独立can_view_lineage权限同时生效。
- ONTOLOGY_TARGETS下关系血缘检查两端的can_view_lineage，STRICT_RESOURCES检查关系资源自身。
- 字段可见性在分页之前执行，敏感字段的哈希也不泄露；隐藏记录不会挤掉可见结果。
- 公开来源只返回概要。sourcePointer、sourceVersion、任意扩展资料和函数inputRefs保留在可信SPI，不经此公共接口输出；源记录键可能包含敏感身份信息。
- 查询开始/结束复核权限并绑定模型。默认100、最大1000，支持0和实体内sequence游标。

使用OpenFGA时需显式配置can_view_lineage，例如仅授予审计角色或owner；此阶段不会修改/发布现有FGA模型，也不默认让viewer获得源系统指针访问权。GraphQL血缘制品及递归函数依赖查询继续开发。

## 升级与验证

新增存储能力transactionalLineage/transactionalIngestion，旧构造器默认false。扩展Provider只有实现对应事务方法才应声明支持；同步要求两项能力。JDBC启动新增三张元数据表，既有历史action_id/source_system列开始写入真实标识，不重写已提交的旧历史。运行中的Mirror库和JAR未改动。

常规根reactor797项通过；随后增加已列来源最大优先级边界测试，并定向复核全部同步及跨进程恢复。当前测试集798项，其中Foundry688项，全通过，无失败/错误/跳过；本阶段新增73项。

覆盖memory/H2的字段来源、同值Action、补偿、null/缺席、分页/租户/字段及独立元数据权限/Consent、旧来源/模型绑定、默认拒绝、源回执/失败停点/乱序/多来源/人工保护、生命周期与未知删除/唯一性恢复、游标配置、连接器真实JDBC/HTTP转换及单连接池。独立JVM验证物理提交前/后退出恢复以及两个不同操作者的工作进程竞争一个来源事件。SQL故障验证血缘/检查点失败不能留下部分事实或回执。

独立探针确认来源链为ACTION/SYNC、人工值保留、重复事件只重放、坏记录后检查点停在2且下一条未入库，元数据权限和指针脱敏生效。

完整YAML映射/变换及关系同步、连接器生命周期/发现/真正增量提取/CDC消息解码/背压、overlay/writeback、调度/死信治理、函数自动血缘和生产PostgreSQL/国产库验收仍待完成。此阶段完成数据提交链路，不把H2及模拟事件当作所有外部系统集成完成。完整核心覆盖目标保持active。
