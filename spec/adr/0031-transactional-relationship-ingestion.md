# ADR-0031：事务关系物化与成员来源

日期：2026-09-28。状态：memory/JDBC关系协调、来源冲突、授权及恢复已实现；完整数据源运行装配继续。

## 背景与边界

ADR-0030完成上游mapping声明、变换和MappedLink投影。本阶段将其接入MaterializedSyncService，使一个来源事件的对象、关系、来源证据、回执、检查点、审计及outbox在同一事务提交。上游映射产生关系引用并交给changeApplier；Java以关系身份、历史和来源控制明确实际落库契约，不把连接器声明解析等同于运行完整CDC或overlay服务。

## 关系身份与协调范围

- 管理关系ID由connector、sourceSystem、sourceRecordId、源对象身份、映射槽名、关系类型及目标身份确定。目标变化结束旧边并创建另一条边；返回相同来源/槽/端点组合时恢复原管理ID，保留历史。
- MANY_TO_ONE与ONE_TO_ONE只允许一个同类型映射槽，协调该对象的单值出向关系。相同目标可复用现有边；替换或清除其他来源的边仍须通过冲突及权限判断。
- MANY_TO_MANY与ONE_TO_MANY只协调相应槽的管理ID，保留无关人工/其他来源边。多个具名槽独立保留未报告的槽，不把一次外键输入解释为完整集合快照。
- 未报告、未变换的外键不改变关系；显式null表示清除。变换仍遵循ADR-0030的缺失输入规则。端点不是可就地替换的普通属性。
- connector、来源记录身份或槽名重命名会改变管理ID命名空间。配置升级必须显式规划迁移/来源交接，不能假定新配置会自动收养旧边。

## 成员来源与属性来源分离

新增RelationshipScope（端点、关系类型、方向）及RelationshipAssertion。每次CREATE、DELETE、RESTORE自动为两端追加成员变更记录；普通关系属性更新不接管成员来源。observeRelationships允许同值及空集合的显式来源确认，使用expectedRevision防止覆盖。

记录包含revision、操作/边ID、记录时间、事务、参与者及MutationSource。操作/边ID为空表示观测。每条记录尺寸不随成员数量增长；它是成员变更/观测历史，当前成员仍从事务内的一等关系记录读取，并非每次复制完整集合快照。

出向scope使“人工清空后来源改用一个新目标/新ID”仍受人工优先策略保护。ONE_TO_ONE/ONE_TO_MANY同时检查目标入向scope；被另一源对象占用的唯一目标不会被自动抢走，而是原子失败。多值scope的最近人工成员变更也会影响后续来源协调，槽身份并不绕开scope策略。

成员与属性分别判断来源。即使成员最初由同步建立，人工修改的关系属性也可阻止同步删除该边。策略键按具体属性、关系类型、默认关系域依次选择：links.<LinkType>.<property>、links.<LinkType>、links。对象真实属性也名为links时，拒绝含糊的显式links覆盖配置。

旧关系没有scope来源时沿用UnknownOrigins规则；默认不允许改变未知成员归属，同值不会自动接管，宿主可显式选择采用。不能从当前“没有边”推断没有人工清除历史。scope记录不是防篡改签名，也不单独提供完整双时间集合查询；事实历史仍走原关系历史契约。

## 事务、授权与失败

RelationshipSync先在来源对象写入前规划，再于同一事务应用。SyncAuthorizer.allowedRelationship接收具体关系身份及两端，默认复用既有allowed判断；宿主可据此实现本体/FGA策略，不必从尚未建立的关系ID猜测端点。

规划、暂存后及提交前检查授权；拒绝权限、缺少目标、属性/基数约束或存储故障均回滚该事件。UPSERT可接受对象属性而拒绝冲突关系，回执记录关系决策；DELETE若关系协调被策略拒绝，对象也保持活动，避免绕过关系保护删除父对象。

一次协调的出向scope最多允许1000个历史关系身份，超限明确失败，不截断结果。此界限及事务内候选读取尚不是大规模同步性能验收。

## 元数据与回执兼容

StorageCapabilities新增relationshipAssertions；原十参数构造器保留且默认false。memory/JDBC声明支持，SchemaBoundStorage转发并保留模型绑定。JDBC新增of_relationship_assertions，scope记录与业务事实共用连接/事务；写入SQL或运行异常将事务标记为只能回滚。内存实现纳入事务状态副本。

无关系映射保留format-1回执与原摘要。有关系映射使用format 2，增加relationships证据和relationshipDigest，请求指纹包括规范化后的关系输入。证据保存选中/影响的边身份、版本、两端、值摘要、原目标引用及scope revision。重放检查证据并重新授权原目标，不拿今天的关系重新执行过去事件；同一事件不能只替换外键或关系属性后复用成功回执。

relationshipChanges返回created/updated/deleted/restored计数，原SyncResult构造器保留。来源观测比较规范化值，避免JDBC数字解码为Integer/Long的差异产生重复观测。回执摘要只检查一致性，不代替数据库访问控制或数字签名。

## 验证与剩余工作

新增47项测试：memory/JDBC共享场景覆盖建立、改派/恢复、缺失/null、多槽、人工清除/属性、显式接管、反向来源、基数、端点/重放权限、晚拒绝回滚、变更请求、历史目标重放、租户/历史、对象生命周期及同值观测；另含JDBC元数据故障、回执损坏、未知旧来源、单连接池及三个独立JVM提交前后中断/竞争场景。

常规根reactor872项，其中Foundry762项，全通过，无失败/错误/跳过；该计数不包括以前运行的OpenFGA集成报告。独立探针在memory/H2确认建立、结束旧边并创建新边、人工清除保留、旧事件仅重放及检查点3，旧探针观测语义未变。

完整数据源计划装配、连接器生命周期/发现/增量提取、CDC解码/背压、overlay/writeback、调度/死信、真实目标数据库和性能验收仍未完成；完整上游核心覆盖目标保持active。Mirror运行库及服务JAR未改动。
