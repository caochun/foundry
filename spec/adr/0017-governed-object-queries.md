# ADR-0017：对象过滤、排序与授权后分页

日期：2026-09-28。状态：已实现本阶段契约；完整查询体系继续推进。

## 上游依据

固定上游 v0.3.0 / 1d7e1aa 的 `packages/odl/src/codegen/index.ts`、`packages/api/src/graphql/resolver-generator.ts` 和 `pagination.ts` 定义字段过滤、AND/OR/NOT、多字段排序、Connection及游标。公开查询须先限制可见对象，禁止用隐藏字段过滤或排序；totalCount只统计可见且匹配的对象。上游聚合、搜索、反向分页等属于后续阶段。

Java旧列表在存储分页后过滤权限，可能短页、漏掉后续可见对象，且没有可信的可见总数。本阶段修复这个顺序。

## 契约

`ApplicationService.queryObjects(context, principal, type, ObjectQuery)` 只接受已注册对象类型，验证租户/主体和全部过滤分支及排序字段，然后从一次存储对象读取所得集合中执行：逐对象viewer检查 → 过滤 → 排序 → 统计 → 分页 → 字段投影及可见计算字段。旧`listObjects`也改为授权后分页；旧无Schema构造器仅保留元数据列表，不能执行新查询。

- 过滤形状：`{amount: {gte: 10}, OR: [{state: {eq: "OPEN"}}, ...]}`，与生成的GraphQL输入相同。
- ID/枚举支持eq/ne/in；字符串和URI另支持contains/startsWith；数字、Date/DateTime/Duration另支持gt/gte/lt/lte；Boolean支持eq/ne。上述类型均支持exists。
- 枚举值、标量及操作符按Schema严格验证。数组、JSON、GeoPoint、虚拟关系和计算字段尚不参与筛选/排序；不会默默忽略未知字段或操作符。
- eq:null匹配缺失或null，ne:null匹配非null；exists表示非null。其他比较不接受null操作数，in不接受null元素。空AND为true，空OR/in为false；这比上游忽略空组合的转换行为更明确。
- 所有逻辑分支先校验，即使没有数据、没有授权对象或另一个分支已经为true，也不会跳过隐藏字段检查。默认敏感字段及角色策略隐藏的字段均不能影响筛选和排序；主键始终可用，沿用公开读取已暴露ID的规则。
- 排序方向ASC/DESC，同值最终以对象ID升序消歧，null始终最后。数字按数值、日期/时刻/时长按对应值比较，不按表示字符串排序。Java和REST保留orderBy映射顺序；GraphQL输入对象无调用方顺序契约，多字段优先级为生成输入的Schema声明顺序。
- 过滤嵌套最多32层、编译节点最多1000，in最多1000项。QueryOptions继续要求first/limit>=1、offset>=0；非合法形状拒绝。
- `ObjectQueryResult`含items/totalCount/offset；`connection()`输出edges/node/cursor、pageInfo和totalCount。游标沿用上游base64(`cursor:<位置>`)，位置是可见匹配集合的索引；无效、非规范或溢出的游标拒绝。after与非零offset不能组合。

## API与兼容

旧GraphQL复数字段继续返回列表，新增filter和orderBy参数；新增`<复数名>Connection`返回Connection。这是显式兼容选择，尚未将默认复数字段改成上游的Connection签名。示例：

```graphql
{
  itemsConnection(filter: {state: {eq: OPEN}}, orderBy: {amount: DESC}, first: 20) {
    totalCount
    edges { node { id amount } cursor }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
  }
}
```

生成Filter、OrderBy、Edge、Connection、PageInfo和SortDirection；生成类型、查询字段或AND/OR/NOT与业务属性的名称冲突会明确拒绝。SDL和运行时采用相同规则。

REST增加`POST /api/v1/<对象类型>/query`，请求JSON：

```json
{"filter":{"amount":{"gte":10}},"orderBy":{"amount":"DESC"},"first":20,"offset":0}
```

还可传after、includeDeleted、成对asOfValidTime/asOfRecordedTime；时间为ISO instant。输出Connection，未知请求选项/非法类型返回400，隐藏字段或身份不符返回403。旧GET列表结构保留。GraphQL本阶段只暴露当前对象查询；Java和REST支持双时间过滤/排序，旧历史仍沿用既有迁移门禁。

## 一致性与性能边界

总数和分页来自同一次对象读取的同一匹配集合，避免单独count与page查询之间的数据变化。没有宣称跨请求快照：新增、删除、撤权或改变筛选条件后复用位置游标，可能改变页边界；客户端应重置游标。字段权限依当前主体角色，viewer依当前逐项检查；外部授权服务和计算字段/关系的后续读取尚未纳入统一快照。

当前读取同租户/类型全部候选对象，再在应用层执行。内存Provider锁内取列表，JDBC为单次对象查询；数据库谓词/排序/授权范围下推、批量鉴权、资源预算、大数据量性能验收仍待完成。本阶段没有修改Storage SPI或持久数据格式，没有迁移运行库。

聚合、全文搜索、last/before、first=0、数组/结构值筛选、计算字段过滤、完整时间关系API以及订阅仍待后续实现，不将该阶段当作上游完整查询覆盖。

## 验证

新增19项测试（9个场景分别在memory/H2验证，另1项游标/生成名冲突）。覆盖125个隐藏对象后的正确页面与总数、角色字段权限、全分支拒绝、空值和组合条件、数字/时刻/时长、稳定排序、撤权、租户隔离、双时间历史、HTTP及GraphQL连接。

常规Foundry339项、Mirror根reactor449项全部通过，无失败/错误/跳过。独立探针在memory/H2确认首条可见对象按排序返回、可见总数2、旧列表也越过隐藏行、隐藏字段谓词拒绝。真实目标关系库、生产规模和跨读取快照不以H2结果替代。
