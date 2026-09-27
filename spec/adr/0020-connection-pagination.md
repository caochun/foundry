# ADR-0020：默认Connection契约与双向分页

日期：2026-09-28。状态：已实现；替代ADR-0017的默认数组/附加Connection过渡选择。

## 上游依据与目的

固定上游v0.3.0 / 1d7e1aa的`packages/odl/src/codegen/index.ts`将复数对象查询生成为<Type>Connection，参数包括filter/orderBy/first/after/last/before。`packages/api/src/graphql/types.ts`规定默认页大小20、最大100。

上阶段Java仅附加`itemsConnection`，默认`items`仍返回数组，且缺少last/before及first=0。这不等于上游接口覆盖。本阶段让默认契约对齐，并提供显式旧列表模式以支持迁移。

上游`pagination.ts`在last无before时仍取默认前页；before靠近序列开头时不收缩limit，可能越过before。Java实现完整可见集合上的边界切片，不复制这些缺陷。

## 统一页模型

`ConnectionPage(first, after, last, before, offset)`与存储QueryOptions分离。`ObjectConnectionQuery`含过滤、排序、页模型及可选双时间/includeDeleted。`ApplicationService.queryConnection`复用普通对象查询的身份、Schema、字段权限、对象viewer、过滤和排序规则；得到一个可见匹配集合后才确定页范围并投影结果。

- 默认向前20条，first/last上限100，超出上限收敛为100。负值拒绝，0合法。
- first与last不能同时指定，避免一请求中两个相反的截断意图。
- after/before均为排他边界，可同时指定；after之后且before之前为候选范围。交叉或相等边界返回空页，不越界读取。
- 指定first时取范围的前N条；指定last时取范围的后N条。单独last取末N条。只有before而未指定页大小时，取before前最多20条。
- before超出结果长度时按结果末尾裁剪；after超出结果长度时为空。向后取页仍按原排序输出，不反转页面内的顺序。
- offset是保留扩展，非零offset不能与after/before/last混用。整数加法及越界游标均明确处理，避免溢出。
- totalCount始终为完整可见匹配集合大小，不受页范围影响。first=0、last=0或越界时edges为空，startCursor/endCursor为null。
- pageInfo的hasPreviousPage/hasNextPage相对于完整可见匹配序列计算，而不是仅相对于after/before夹出的范围。例如before序列首条的空页仍可hasNextPage=true；last=0在非空序列末尾可hasPreviousPage=true。
- 游标沿用规范base64(`cursor:<位置>`)，表示完整可见匹配序列的索引。所有游标先验证，再读取数据。分页方向不会改变对象授权或字段遮蔽；即使first=0也不能使用隐藏字段筛选/排序。

QueryOptions原有limit>=1规则及旧Java listObjects/queryObjects契约保留，存储SPI不会因公开空页查询而收到非法零limit。Connection页大小只控制结果切片，不裁剪统计所依据的候选对象读取。

## GraphQL默认及兼容

默认入口生成与上游相同的Connection返回：

```graphql
{
  items(first: 20) {
    edges { node { id } cursor }
    totalCount
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
  }
}
```

下一页传first/after；上一页传last/before；末页只传last。已发布的`itemsConnection`继续作为同一Connection接口的别名。

旧数组查询`items { id }`需显式使用：

```java
GraphqlApiRuntime.create(schema, application, manifests,
        GraphqlApiRuntime.ActionMode.TYPED,
        GraphqlApiRuntime.QueryMode.LEGACY_LIST);
```

QueryMode和ActionMode独立。`create(schema, app, manifests, ActionMode.LEGACY_JSON)`仅选择旧Action输入，查询仍默认Connection；`createLegacy(...)`同时选择LEGACY_JSON和LEGACY_LIST，保持原旧动作/列表组合。旧数组模式仍保留first=100及offset，正向参数规则沿用原实现；双向/零条查询通过该模式中的`itemsConnection`提供。

SDL生成器增加同样的三参数配置：

```java
new GraphqlContractGenerator().generate(schema,
        GraphqlApiRuntime.ActionMode.TYPED,
        GraphqlApiRuntime.QueryMode.LEGACY_LIST);
```

不传QueryMode即CONNECTION。查询SDL不为first硬编码默认值，以免客户端只传last时产生同时指定first/last的歧义，默认页大小由页模型应用。

## REST与时间

`POST /api/v1/<Type>/query`切换到同一Connection页模型，支持first/after/last/before/offset。默认20、最多100，first=0合法；原先默认100的调用方需要显式first=100或处理后续页。JSON还支持filter/orderBy和成对asOfValidTime/asOfRecordedTime/includeDeleted。

```json
{"last":20,"before":"Y3Vyc29yOjQw","orderBy":{"name":"ASC"}}
```

Java/REST反向查询按所选历史时点的值过滤和排序，权限依当前主体。GraphQL时间查询仍属于后续范围。旧GET列表和接收ObjectQuery的RestApiRouter Java重载保留原契约；新增ObjectConnectionQuery重载供HTTP使用。

## 不变式与剩余范围

正向或反向走完整个稳定结果集，每个可见对象恰好出现一次；隐藏对象不占游标位置。总数、排序和切片来自同一次对象读取形成的集合。对象变更、撤权或更换过滤/排序后，位置游标可能移位，应重置；不提供跨请求固定快照或与外部授权服务的原子快照。

本阶段不改持久结构、运行库或业务模型。原生存储查询/搜索/聚合下推、批量授权、资源预算、时间关系API、Consent/ObjectSet及其余上游范围继续推进。

## 验证

新增19项：9个场景分别在memory/H2验证，另1项输入门禁。覆盖正反向完整遍历、130个隐藏对象、last单独使用、before首部截断、双边界、零条/空集合/越界、默认20/最大100、过滤排序/字段权限/租户/撤权、历史状态、REST、默认GraphQL及两种Action模式下的旧列表兼容。

常规Foundry402项、Mirror根reactor512项全部通过，无失败/错误/跳过。独立探针在memory/H2确认默认GraphQL末页为a5/a6、可见总数7，before索引1且last=3只返回a0，零条查询总数仍为7，显式旧列表返回原数组。
