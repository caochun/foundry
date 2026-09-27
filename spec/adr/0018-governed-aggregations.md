# ADR-0018：授权范围内的对象聚合

日期：2026-09-28。状态：聚合应用/API契约已实现；原生存储下推及完整查询体系继续推进。

## 上游依据

以固定v0.3.0 / 1d7e1aa的以下实现为依据：

- `packages/spi/src/ontology.ts`：AggregateFunction、AggregateQuery、AggregateGroup、AggregateResult。
- `packages/storage-memory/src/memory-storage-provider.ts`：五种函数、非空COUNT、分组、排序和分页。
- `packages/storage-postgres/src/objects/aggregate.ts`：SQL聚合、空集合以及分页前totalGroups。
- `packages/api/src/graphql/resolver-generator.ts`、`rest/route-generator.ts`：对象授权、聚合/分组/筛选字段权限。
- `packages/odl/src/codegen/index.ts`：AggregateFieldInput、AggregateGroup、AggregateResult及`<对象小写名>Aggregate`。

聚合是平台通用能力，不包含Mirror业务指标的定义或计算规则。

## 执行顺序与权限

`ApplicationService.aggregateObjects`接收已注册对象类型和`AggregateQuery`，依次执行：

1. 校验请求租户/主体、对象类型、全部聚合/分组/排序声明、别名和筛选条件。
2. 按指定时间口径读取同租户/类型的候选对象；逐对象检查viewer并执行已校验的过滤条件。
3. 仅在剩余可见对象上建立分组、累积指标、统计总组数，再排序和分页。

默认敏感字段及角色策略隐藏的字段不能作为聚合、分组或筛选字段；主键沿用已有公开ID口径。不能因数据为空或没有可见对象就跳过字段验证。排序只引用已声明的分组字段或聚合结果别名，不能引用任意原始字段。

结果别名是标签，不是原始属性引用。例如`COUNT(*) AS secret`不读取名为secret的属性；而`SUM(secret)`必须具有secret的可见权限。别名与分组字段重名、多个聚合使用同一结果名均拒绝，避免排序歧义和覆盖结果。

## 数值及分组语义

- COUNT(*)计所有可见匹配对象；COUNT(field)仅计该字段非null的对象，支持任意已声明存储属性，包括JSON及数组。主键值取对象ID。
- SUM/AVG/MIN/MAX仅接受Int/Float属性，与固定上游内存实现及数字结果类型对齐；禁止对字符串、日期、JSON等进行隐式数值转换。
- null和缺失不参与数值运算；无非null数值时SUM/AVG/MIN/MAX返回null，不以0代替。
- COUNT为long。数值累计用BigDecimal，AVG按DECIMAL128除法，输出有限double以匹配公开数字契约；如SUM结果超出有限double范围，整个请求明确失败，不序列化Infinity或返回部分结果。输入仍沿用存储层的Int/Float类型和精度，不承诺任意精度金融小数。
- 分组支持已声明的存储属性；null/缺失合为一组。复合分组使用类型化的规范键，不使用分隔符拼接；JSON键顺序和相等数值表示不影响分组，数组顺序保留。计算字段/虚拟关系不作为存储属性聚合。
- 有groupBy且无可见匹配对象时groups为空、totalGroups=0。无groupBy时始终有一个总体组：COUNT为0，其余函数为null。这沿用上游存储/SQL语义；上游公开API在无授权ID时提前返回0组的差异不沿用，Java对所有空可见集合保持相同语义，避免区分空表与全隐藏表。
- 显式排序支持多个分组字段或结果别名，按列表顺序执行ASC/DESC。null始终最后；相等时按groupBy声明顺序升序消歧。数字/日期/时间/时长按值比较，JSON/数组按规范表示稳定排序。
- totalGroups为所有可见匹配组的数量，limit/offset只裁剪结果组，不裁剪输入对象。limit=0有效，返回空groups及正确totalGroups；负值拒绝。默认不限制组数。
- 缺省别名为小写函数名加下划线及字段，例如sum_amount、count_*；与上游公开API的函数名规范化一致。显式alias须以字母开头，后接字母/数字/下划线，总长最多128。

## 公共接口

GraphQL遵循上游命名及结果形状，并额外支持组排序和分页：

```graphql
{
  itemAggregate(
    filter: {category: {eq: A}}
    groupBy: ["category"]
    fields: [
      {field: "*", fn: COUNT, alias: "objects"}
      {field: "amount", fn: SUM, alias: "total"}
      {field: "amount", fn: AVG}
    ]
    orderBy: [{field: "total", direction: DESC}]
    limit: 10
  ) {
    groups { keys values }
    totalGroups
  }
}
```

生成的AggregateFunction/FieldInput/OrderInput/Group/Result名称和查询字段冲突明确拒绝。keys/values为JSON，空指标保留为null。

REST增加`POST /api/v1/<对象类型>/aggregate`，沿用Java现有按类型名的路由约定及直接结果信封；上游REST的复数资源名与data信封不是本阶段的路由迁移。示例：

```json
{
  "fields": [{"field":"amount","fn":"sum","alias":"total"}],
  "groupBy": ["category"],
  "filter": {"amount":{"gte":0}},
  "orderBy": [{"field":"total","direction":"desc"}],
  "limit": 10,
  "offset": 0
}
```

REST函数/方向接受大小写并规范化，filter使用ADR-0017定义的公共过滤形状。Java/REST另支持成对asOfValidTime/asOfRecordedTime以及includeDeleted，依据指定历史状态分组和求值，权限仍按当前主体检查。GraphQL本阶段为当前状态聚合。

未知请求选项、字段、函数、非法类型、别名冲突及溢出返回400；字段或身份权限拒绝返回403。不会默默忽略不支持的声明或输出部分聚合。

## 存储及一致性边界

本阶段在应用层基于一次对象读取完成聚合和总组数，内存/JDBC共享相同算法，不新增表或改变持久数据格式。原生Storage SPI聚合入口、SQL GROUP BY下推、授权范围下推、批量鉴权和生产规模验收仍待实现；当前并非上游原生存储聚合接口的完整等价替代。

各指标及totalGroups来自同一输入集合，但没有宣称与外部权限服务或跨请求的一致快照。Consent、搜索、反向分页、ObjectSet以及其余查询能力仍按总计划推进，不将本阶段标为完整核心覆盖。

## 验证

新增23项：11个场景分别在memory/H2验证，另1项生成名冲突测试。覆盖五种函数、COUNT差异、空集合/null组、复合/JSON组、130个隐藏对象、隐藏字段/角色策略、别名与原始字段区别、排序和limit=0、历史/删除/租户/撤权、0.1+0.2、有限数溢出以及实际HTTP和GraphQL入口。

常规Foundry362项、Mirror根reactor472项全部通过，无失败/错误/跳过。独立探针在memory/H2确认：隐藏对象及其独有组不出现；可见COUNT=3、SUM=4、AVG=2；limit=1仍返回totalGroups=2；隐藏指标拒绝。
