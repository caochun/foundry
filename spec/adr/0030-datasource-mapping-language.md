# ADR-0030：数据源映射声明与变换执行

日期：2026-09-28。状态：上游映射声明、内置变换、自定义函数版本绑定、关系引用映射及属性入库已实现；关系状态协调与连接器运行装配继续。

## 上游依据与分层

固定上游v0.3.0 / 1d7e1aa 的 sync/mapping 模块包含mapping-parser.ts、transforms.ts和record-mapper.ts。它把datasource/connector/connection/mapping/sync解析成配置，把源记录转换成对象属性和MappedLink目标引用，再交给changeApplier；映射器自身不执行关系转移或启动CDC/overlay。

Java新增DatasourceMapping、KeyMapping、PropertyMapping、LinkMapping、MappingConfigParser、TransformRegistry及MappingSchemaValidator。原三参数MappingConfig构造器仍可用，既有简单重命名的摘要保持完全一致，避免使已提交的来源回执和检查点失效。

## 声明保留与校验

```yaml
datasource: People
connector: jdbc
connection:
  url: "${PEOPLE_DB}"
  table: people
mapping:
  objectType: Person
  primaryKey: {source: person_id, target: id, transform: "prefix('person-')"}
  properties:
    name: {source: surname, transform: "concat(first, ' ', surname)"}
    born: {source: birth_text, transform: "parseDate('dd/MM/yyyy')"}
  links:
    - linkType: WorksIn
      toType: Unit
      toKey: {source: unit_id, target: id, transform: "prefix('unit-')"}
sync:
  mode: CDC
  conflictResolution: SOURCE_PRIORITY
  rateLimit: {maxRecordsPerSecond: 500}
```

保留连接URL/table及额外连接属性、主键source/target/transform、每个目标属性的source/transform、关系类型/端点/键及属性映射，以及OVERLAY/CDC/POLLING/BATCH、interval、conflictResolution、rateLimit、cacheStrategy/cacheTTL、writeback。环境占位符原样保存，解析不会读取环境、解析凭证、建立网络连接或启动调度。

解析器使用SafeConstructor，拒绝重复键、未知映射选项、错误类型、递归/过深数据与错误函数语法。连接器扩展属性仍可保留；函数custom名称可以先声明，再由可信宿主登记实现。日期格式/函数参数在编译时验证，不等到导入一半才发现未知函数。

MappingSchemaValidator绑定目标对象、属性、关系两端及主键目标；readonly映射拒绝。toKey.target必须是目标类型主键，不把声明为其他属性的外键悄悄当作ID查询。它不执行来源I/O。

一个源字段可以通过不同变换填充多个目标字段。新代码应使用properties()/primaryKey()/links()；旧sourceToTarget()仅在可表示为一对一重命名时提供视图，不能表示时明确报错。

## 变换语言

支持固定上游的全部内置函数：concat、prefix、suffix、parseDate、parseDateTime、toUpper、toLower、parseInt、parseFloat、trim、ifPresent、coalesce、map，以及custom扩展入口。

- concat区分未加引号的字段引用和字符串字面量，可访问完整记录；字段null/缺失拼为空字符串。
- prefix/suffix/大小写/trim/数值日期解析/map对null保持null；ifPresent依据非null而非真假判断，coalesce只替换null。
- parseInt/parseFloat沿用十进制数字前缀解析，例如“12 items”得到12；无数字或非有限浮点返回null。整数尽量保留精度，最终仍受目标ODL类型限制。
- 日期格式采用yyyy/MM/dd/HH/mm/ss等上游标记和字面分隔符；日期时间默认UTC，不受宿主时区影响。缺省年月日/时间部分沿用上游默认，非法日历日期、时间或重复标记拒绝。
- map精确解析字符串键值，未知源值为null；重复键、残余垃圾、错误参数数量、不完整引号和未知函数拒绝，不做宽松局部匹配。
- 支持转义引号、逗号/冒号字面量及双引号扩展。没有eval、JavaScript或反射执行；表达式只包含单个函数调用，嵌套/管道不是该上游语言的能力。

与宽松上游的差异有意保留：非法日期不拼出伪ISO日期，整数不先按JS浮点舍入，文本函数不把结构对象默默变成“[object Object]”。大小写使用Locale.ROOT，trim覆盖常见ECMAScript空白。

没有transform的缺少字段仍表示未报告，不清空已有属性。声明了transform时按其规则执行，缺失输入作为null传入；例如ifPresent仍可产生默认状态。这与未变换字段的部分更新语义不同，配置作者需明确选择。

## 自定义函数与配置身份

TransformRegistry是不可变快照。注册必须给出名称、显式版本和可信Java函数；RecordMapper一次性编译全部表达式。未注册函数在连接器读取之前失败，不在处理中查询可变全局注册表。

```java
var registry = new TransformRegistry().with("canonicalName", "2",
        (value, record) -> value == null ? null : value.toString().strip().toUpperCase(Locale.ROOT));
var mapper = new RecordMapper(mapping, registry);
var sync = new MaterializedSyncService(storage).withAuthorization(policy).withTransforms(registry);
```

函数接收完整的不可变SourceRecord数据，结果冻结为JSON值。函数实现须纯、确定，不应执行外部副作用；Java插件没有脚本沙箱。使用相同版本但替换函数行为无法由函数类型自动证明，宿主必须更新版本。

映射声明和实际引用的自定义函数版本参与mappingVersion、回执及检查点配置摘要。增加未引用函数不改变摘要；修改已引用函数版本，即使输出暂时相同，也不能复用旧管道检查点。相同来源事件重放可能重新计算变换以验证摘要，但不会重新写事实。

## 关系投影及运行边界

RecordMapper返回不可变MappedLink列表。缺少未变换的外键字段不产生条目；显式null外键产生clear引用；非null值经变换后无法生成身份时拒绝，避免意外清除关系或创建名为“null”的端点。DELETE只映射实体身份，不因过时的非关键属性变换阻塞删除。

这些条目是事务关系应用器的输入，不等同于已经完成关系状态协调。目前MaterializedSyncService对非空关系映射在来源I/O之前明确拒绝，保证不会只提交对象而丢弃关系。

下一步关系应用器必须明确源映射的关系范围、稳定身份、端点变化形成新关系、源管理关系与人工关系的冲突、空值清除、目标权限/基数约束、删除/恢复后的来源控制，以及与回执/检查点同事务恢复。现有对象同步能力保持可用，完整目标未因此缩小。

DatasourceMapping.sync运行选项只是完整保留的声明。本阶段不启用OVERLAY缓存、轮询调度、限速或writeback；宿主取mapping()进行一次属性物化是显式使用映射，并非运行完整数据源计划。

## Pack与升级

含datasource或mapping键的连接器资产按完整声明解析，其他旧原始连接器配置继续保留。可通过ConnectorDefinition.datasourceMapping()取得类型化配置。

映射引用的对象、关系和端点类型进入Pack依赖可见性检查，最终组合Schema验证字段、端点及主键；连接器文件不再仅是原始附件。装载只检查声明和语法，不要求此时已有custom实现，也不部署连接器。

老简单MappingConfig和无关系MappedRecord构造器保留。转成显式YAML主键目标、加入变换或关系会产生新的配置身份，不能当作旧检查点的同一计划。应用授权代码若读取重命名视图，应改用完整properties()以覆盖重复来源。

## 验证与剩余范围

新增27项：内置函数/日期/数值/空值/词法错误、不可变自定义注册与结果、历史摘要兼容、原上游三份连接器文件、关系引用的缺失/null、DELETE身份路径；memory/H2共享测试验证规范化入库/来源/重放、自定义版本漂移、错误停点和执行前拒绝；Pack验证依赖和字段绑定。

Foundry715项、根reactor825项全部通过，无失败/错误/跳过。独立探针直接解析固定上游PAS/ERP/TMS文件，确认主键前缀、姓名拼接、日期、整数/小数和模式保留；没有把原始OVERLAY声明当作已启动的overlay服务。测试夹具原样保留文件、版本/摘要和Apache-2.0许可证。

关系物化、完整数据源计划装配、连接器生命周期/增量/CDC/背压、overlay/writeback、调度与生产目标库验收仍需继续。运行中的Mirror库和服务JAR未改动，完整核心覆盖目标保持active。
