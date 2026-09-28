# ADR-0024：ObjectSet保存查询与受控执行

日期：2026-09-28。状态：保存定义、内存/JDBC存储、受控执行/聚合及REST/GraphQL入口已实现。

## 上游依据

固定上游v0.3.0 / 1d7e1aa的`packages/spi/src/object-set.ts`定义ObjectSetDefinition/ObjectSetStore；`packages/engine/src/object-sets`实现内存存储和ObjectSetManager，`packages/storage-postgres/src/object-sets/postgres-object-set-store.ts`提供持久存储，API的REST/GraphQL生成器提供管理和执行入口。

ObjectSet是保存的命名查询定义，包括对象类型、筛选、排序、默认页大小及可选聚合，不是固定对象ID清单或结果快照。业务事实变化时，执行结果相应变化。

## 数据与存储契约

新增SPI：ObjectSetSpec、ObjectSetDefinition、ObjectSetStore。

ObjectSetSpec仅包含用户可编辑内容：name、description、objectType、filter、orderBy、limit、aggregation、isPublic。ID、tenantId、createdBy、createdAt/updatedAt及revision由存储生成，创建接口不接受伪造身份；更新不能修改身份、所有者、对象类型或时间。

内存与JdbcObjectSetStore均提供create/get/getByName/list/update/delete。创建必须有actorId；可见性为同租户内公开或创建者本人。只有创建者可以更新/删除，管理员没有隐式绕过。底层SPI允许无actor上下文读取本租户公开定义；受控API仍要求有效SecurityPrincipal与RequestContext匹配。

- 字段/嵌套JSON防御性复制，返回值不可原地修改。
- name和objectType非空且最多255字符。名字允许重复，按createdAt及ID排序选择最早的可见同名定义，不用隐藏定义遮蔽公开定义。
- update是字段补丁：未提供的字段保留；description/filter/orderBy/limit/aggregation可用null清除；name及isPublic不可清除，create中的null isPublic按false处理。
- 增加revision：更新总是递增，空补丁也递增；可选expectedVersion检查过期修改/删除。JDBC在同一行锁事务中做所有者检查、读取、合并和写入，避免先检查后更新的竞态。
- 无expectedVersion的SPI并发补丁在锁内合并未修改字段；API额外绑定验证时观察到的revision，防止验证后定义又被修改。
- JDBC独立初始化`of_object_sets`，不依赖业务对象表初始化；保留格式版本与索引列/JSON内容一致性检查，读取未知格式或不一致内容会失败。

元数据数字编解码复用注册表的无损数字规则，避免保存筛选值时无意截断。它不是业务数据查询的任意精度承诺。revision用于当前定义的乐观并发控制，本阶段不提供历史定义版本查询。

## 验证、共享与执行

ObjectSetService组合一个固定模型的ApplicationService与ObjectSetStore。创建、修改查询或发布时，先验证当前对象类型、操作符、值类型、筛选/排序/聚合字段权限，不执行查询来“猜测”有效性。

保存的筛选接受上游SPI形状（field/operator/value、and/or/not），也接受现有Java公共查询形状。neq转换为ne，`_id`解析到真实声明的主键，不能借系统别名访问其他隐藏属性。实际支持的字段/操作符沿用通用查询引擎；结构值/数组/关系等尚未支持的筛选不会被静默忽略。

执行始终使用**执行者**的租户、对象viewer与字段权限，集合创建者的权限不会传递。isPublic仅表示同租户共享定义，不授予其所选对象的访问权限。对象查询/总数、字段投影、排序/分页和聚合均复用ApplicationService已有门禁。

集合filter与aggregation.filter做AND交集，不允许聚合忽略集合范围。默认limit只影响普通执行的默认页大小，可以被调用参数覆盖；仍受Connection默认20/最大100等规则约束。聚合自己的组分页独立于集合默认对象页大小。

执行开始取得定义快照，完成后再检查可见性与revision/内容：中途取消共享或删除返回不可用，其他修改返回冲突，不交付基于旧定义的结果。

公开定义自身也可能含敏感筛选值。向非创建者显示定义前，检查其所有筛选、排序和聚合字段权限；不可访问的公开定义不出现在列表中。创建者保留自己编写的定义用于修复，但执行仍须通过当前权限。即使查询失效或创建者失去相关字段权限，也允许其重命名、注释、取消共享或删除；不能借这条规则发布或修改未获权限的查询。

保存定义与本体事实不是同一事务，模型可以在保存后变化；新执行会按当前模型和权限重新验证。对象/元数据的多次读取不是统一数据快照；沿用ADR-0023的模型绑定而不声称更强的一致性。

## 装配及API

存储显式注入，不默认创建易丢失的内存目录：

```java
ObjectSetStore sets = new JdbcObjectSetStore(dataSource, dialect);
ObjectSetService service = new ObjectSetService(application, sets);
RestApiRouter rest = new RestApiRouter(application, sets);
GraphQL graph = GraphqlApiRuntime.create(schema, application, manifests,
        GraphqlApiRuntime.ActionMode.TYPED, GraphqlApiRuntime.QueryMode.CONNECTION, sets);
```

REST沿用直接结果信封：

- GET/POST `/api/v1/object-sets`：列出/创建；GET可按objectType过滤，或用name做可见同名查询。
- GET/PUT/DELETE `/api/v1/object-sets/{id}`：读取、字段补丁、删除。PUT正文可传expectedVersion；DELETE用同名查询参数，成功204无正文。
- GET `/api/v1/object-sets/{id}/execute?limit=&offset=`：受控Connection结果。
- GET `/api/v1/object-sets/{id}/aggregate`：保存的聚合结果。

GraphQL包含上游ObjectSet/CreateObjectSetInput/UpdateObjectSetInput以及objectSet/objectSets、create/update/deleteObjectSet。增加objectSetByName、executeObjectSet、aggregateObjectSet和字符串version/expectedVersion。动态执行节点为JSON，包含id/type/version及已经遮蔽的properties，不返回未经授权的原始属性。

通用Schema始终保留核心ObjectSet类型和操作名称；未注入存储时明确返回OBJECT_SETS_NOT_CONFIGURED（REST 501），不把未配置伪装成空目录。与上游部分未配置查询返回空列表的行为不同。核心类型/查询/变更名称与领域模型冲突会拒绝，不静默覆盖领域Action。

不可用定义为404/OBJECT_SET_NOT_FOUND，非创建者修改或无字段权限拒绝，版本/执行期间变化为409/OBJECT_SET_CONFLICT。私有定义的API更新可能返回404以隐藏定义；SPI变更仍按上游区分同租户非创建者的FORBIDDEN。非法查询、未知或只读输入字段返回400，不沿用上游对未知更新键的静默忽略。

## 剩余范围

ObjectSet复用已实现的通用筛选与聚合能力，不能据此把原生存储下推、数组/结构值/关系筛选、数据快照或Consent标为完成。独立配置发布、订阅、CLI/SDK、元数据变更审计、资源配额及实际目标数据库/性能验收继续推进。

本阶段新增元数据表，不变更运行中的Mirror数据库、服务JAR或业务模型。没有把ObjectSet变成标签规则或政务流程。

## 验证

新增24项：11个场景分别在内存/H2验证，另2项覆盖文件恢复/单连接池和核心名称/未配置门禁。覆盖创建者来源、私有/共享/匿名SPI可见性、跨租户、只读字段、清除和部分更新、CAS与并发合并、动态成员及130条隐藏记录、调用者权限、交集聚合、敏感定义保护、取消共享/修改期间执行拒绝、Schema变化后的修复、REST及GraphQL。

常规Foundry483项、Mirror根reactor593项全部通过，无失败/错误/跳过。独立探针在memory/H2均确认读者仅得到对象b、可见总数1，聚合交集SUM=2，非创建者不能更新，取消共享后定义不可见。
