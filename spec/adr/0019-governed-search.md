# ADR-0019：受控对象文本搜索

日期：2026-09-28。状态：应用层搜索和REST/GraphQL入口已实现；原生存储搜索及生产下推继续推进。

## 上游证据及模式

固定上游v0.3.0 / 1d7e1aa实际实现存在差异：

- `packages/storage-memory/src/memory-storage-provider.ts`：不区分大小写，按空白拆词，任一词匹配即可命中，跨字段累计各词的不重叠出现次数。
- `packages/storage-postgres/src/objects/search.ts`：以完整查询字符串执行ILIKE，转义%/_/反斜线，每个命中字段计1分，并非PostgreSQL全文索引或ts_rank。
- `packages/spi/src/ontology.ts`：SearchQuery、SearchHit（可选highlights）和SearchResult。
- `packages/api/src/graphql/resolver-generator.ts`、`rest/route-generator.ts`：授权对象范围、字段可见性、默认可见搜索字段、过滤和分页。
- `packages/odl/src/codegen/index.ts`：search<Type>s、SearchHit_<Type>、SearchResult_<Type>。

Java显式提供TERMS和PHRASE模式，均由memory/JDBC共享求值，默认TERMS。TERMS对齐上游内存的匹配和计分；PHRASE对齐上游PostgreSQL的完整字符串匹配和命中字段数。模式不随StorageProvider切换，不能笼统宣称上游已有统一搜索语义。

## 权限及执行

`ApplicationService.searchObjects`只接受已注册对象类型，要求租户/主体一致，先绑定并验证搜索字段和所有筛选条件，再从一次候选对象读取中过滤viewer授权和条件，最后匹配、评分、排序、统计和分页。输出对象沿用普通读取的字段投影及计算字段规则。

默认仅搜索可见的、非主键的已声明文本属性：String/ID/Date/DateTime/Duration/URI或枚举。未知属性、运行时恰好为字符串的JSON、数组、数字、布尔和计算/关系字段不会被自动转成文本。显式指定这些非文本属性会拒绝；这是按Schema绑定的契约，与上游内存按运行时typeof string自动挑字段有意不同。

显式搜索主键使用对象ID，沿用现有公开ID规则。显式隐藏字段返回权限拒绝，不静默删掉字段；默认字段只含可见属性。显式fields=[]表示不搜索任何字段，得到空结果，不回退到所有字段。即使fields为空或没有可见对象，也先验证全部筛选条件，避免借空结果绕过隐藏字段检查。

隐藏对象不参与命中数、评分或分页，隐藏字段不参与匹配、评分或highlights。因此不能仅因隐藏字段含有关键词而看到一个公开对象，也不能从分数变化推断隐藏内容。

## 匹配、输出及边界

- TERMS按Unicode空白及BOM分词；词之间为OR，重复词重复计分。每个词在每个字段中计不重叠出现次数。
- PHRASE保留原查询字符串的空格和顺序，完整字符串在某字段出现就计1分，不按出现次数累计。两种模式均使用Locale.ROOT小写比较。
- 中文按字面子串匹配，不声称实现中文分词、词干、同义词或相关性学习。%、_、反斜线和正则符号均为普通字符，不解释为SQL通配符或正则表达式。
- 按score降序，相同分数按对象ID升序消歧。对象变更、撤权或查询条件改变后，应重置游标；游标是当前可见匹配序列的位置，不是固定快照。
- SearchResult含hits、totalCount、hasNextPage。Hit含已投影node、score、highlights和cursor。totalCount来自完整可见匹配集合，超出末页仍保留正确总数。
- highlights只含参与匹配的可见字段原文，是普通文本，不生成HTML。一个字段多个词命中仍只返回一份原文，区别于上游内存重复返回相同原文；PHRASE也提供此可选信息。调用方按文本展示，不能把原始数据当成可信HTML。
- 默认limit/first=20，offset=0；允许limit=0，返回空hits及正确totalCount/hasNextPage；负值拒绝。after复用ADR-0017的规范位置游标，与非零offset互斥。
- 查询必须非空白，最多4096个UTF-16字符、256个词，显式字段不能重复。未知请求参数、无效模式/字段/类型/时间/游标拒绝，避免静默改变查询含义。

## 接口

GraphQL沿用上游命名与基础结果，增加mode、offset、highlights和返回cursor：

```graphql
{
  searchArticles(query: "政务 关系", fields: ["title", "body"], mode: TERMS, first: 20) {
    hits { node { id title } score highlights cursor }
    totalCount
    hasNextPage
  }
}
```

filter使用生成的<Type>Filter。新增SearchMode/SearchHit_<Type>/SearchResult_<Type>和search<Type>s名称冲突会明确拒绝。

REST支持：

```text
GET /api/v1/Article/search?q=alpha&fields=title,body&mode=TERMS&limit=20&offset=0
POST /api/v1/Article/search
```

POST示例：

```json
{"query":"alpha","fields":["title","body"],"filter":{"category":{"eq":"NEWS"}},"mode":"PHRASE","limit":20}
```

继续沿用Java的类型名路由和直接结果信封，不在本阶段迁移到上游复数名/data信封。GET重复参数及未知选项拒绝；fields=明确表示空字段列表。**JDK HTTP适配器的GET /<Type>/search成为保留的搜索路径**，已有ID恰为search的对象仍可通过Java或GraphQL单查，但该GET路径不再表示对象ID。POST /search支持复杂filter，GET仅支持列出的简单搜索选项。

Java/REST支持成对asOfValidTime/asOfRecordedTime及includeDeleted，用指定历史状态匹配和返回对应文本；权限按当前主体检查。GraphQL本阶段为当前状态搜索。非法输入返回400，隐藏字段或身份不符返回403；旧历史仍受现有迁移门禁约束。

## 剩余工作

本阶段为应用层的跨Provider一致算法，并未接通原生Storage SPI searchObjects或SQL/全文索引下推，没有修改持久数据格式或运行库。统计和评分来自同一对象读取集合，但外部授权、随后计算字段/关联读取及跨请求没有统一快照。

数据规模/资源预算、实际国产数据库搜索、原生查询与聚合下推、Consent、ObjectSet、反向分页及其余上游能力仍在完整对齐计划。不能把文本子串搜索说成已完成全文检索基础设施。

## 验证

新增21项：10个场景分别在memory/H2验证，另1项输入/生成名冲突测试。覆盖TERMS/PHRASE差异、评分/同分排序、中文/Unicode/土耳其Locale、字面特殊字符、不重叠匹配、130条隐藏对象、隐藏字段及角色策略、空字段、主键/枚举/文本标量、过滤拒绝、时间/删除/租户/撤权、分页/越界/limit=0以及实际GET/POST/GraphQL。

常规Foundry383项、Mirror根reactor493项全部通过，无失败/错误/跳过。独立探针在memory/H2确认可见命中数2、TERMS首条得分3、PHRASE两条各1分、隐藏字段拒绝且highlights不含隐藏文本。
