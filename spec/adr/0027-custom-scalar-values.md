# ADR-0027：自定义 scalar 声明与值链路

日期：2026-09-28。状态：名称/说明、组合、存储、Action、API、精确值比较与注册表已贯通；完整AST和结构值模型仍继续。

## 上游证据

固定上游v0.3.0 / 1d7e1aa 的 parser/types.ts 保留 ScalarDefinition.name/description；parser/index.ts 收集 scalar 声明，默认把无 @objectType/@linkType/@actionType 的普通 type 作为 objectType。validator/index.ts 将声明的scalar纳入类型引用解析，schema-loader.ts 组合各Pack的scalar。

Java此前忽略所有scalar定义，仅靠一组硬编码平台类型读写，并丢弃未加类型指令的普通type。本阶段修复这两个声明缺口，不把普通type擅自解释为内嵌值类型；其实体主键/存储约束照常验证。

固定上游的scalar AST没有base type或序列化函数定义，engine/objects/validation.ts只对内置类型执行具体验证。Java不根据任意名称猜测字符串、数字、正则或外部对象身份，而为未赋予平台语义的声明提供明确的不透明JSON值契约。这不是任意宿主语言对象或可执行编解码插件。

## 声明与组合

```graphql
"An external structured value"
scalar Payload

type Record @objectType {
  id: ID! @primary
  value: Payload!
  values: [Payload!]
  secret: Payload @sensitive
}
```

OntologySchema新增scalars，ScalarDefinition保留名称和可空description；原5/6/7参数构造器继续使用。名称合法性、重复、跨种类重名和GraphQL生成名称冲突都会拒绝。未知scalar指令也明确拒绝，未把完整指令AST标为已覆盖。

OdlParser.compose允许完全相同的scalar重复出现；说明也必须相同，不能只比较会丢失说明的紧凑AST文本。DomainPackLoader仍要求自定义类型单一Pack所有者，引用方显式依赖；不会因名字是scalar而跨越依赖可见性。平台标量的重复声明沿用允许共享规则。

ID/String/Int/Float/Boolean/Date/DateTime/Duration/URI/GeoPoint/JSON等平台类型仍使用原有校验；声明scalar Date不会使非法日期变成合法的任意JSON。平台scalar说明会存入模型，但不替换GraphQL平台内置codec的说明。

## 值、动作与API

自定义scalar接受可递归冻结的JSON表示：字符串、布尔、有限数字、字符串键对象、列表及嵌套null。字段和列表的外层非空约束仍按ODL执行。未知宿主对象、ObjectRecord、非字符串Map键、非有限数值拒绝；返回值为不可变深拷贝。

同一契约用于对象/关系属性、默认值、唯一性、历史、Action参数及GraphQL变量/字面量。@constraint照常对值执行CEL；@immutable/@readonly/字段权限和实体授权不变。带有id或_type键的scalar只是数据，不进行对象引用解析，也不会取得所指对象的权限或内容。

GraphQL使用声明的scalar名称和说明，输入输出均传JSON值，不将结构值转换成字符串。声明未被字段使用时仍出现在运行Schema；静态SDL保留相同名称/说明。字段治理作用于整个scalar属性，未实现嵌套字段权限。

### 数值完整性

ODL数字字面量根据精度保留Integer/Long/BigInteger及Double/BigDecimal。普通可无损表达的小数仍保持既有Double形态；高精度值不先转Double。Int字段的32位边界由类型校验检查，不误限制嵌套JSON/custom scalar中的大整数。

JDBC对象/关系、历史、回执/continuation及激活校验接入已有JsonNumbers解码器：普通JSON数字兼容原形态，无法精确表示为Double的小数保留BigDecimal。因此相邻高精度唯一值在回读/激活时不会被舍入成相同值。JDK REST请求和旧GraphQL JSON输入也在解析阶段使用同一数值规则；声明为Float的参数仍按平台Double语义进入CEL，而opaque scalar保持精度。已经由旧版本舍入的数据不自动恢复；边缘HTTP客户端自身的JSON数字精度仍取决于客户端。曾被旧HTTP解析器舍入的高精度请求现在具有不同的参数指纹，旧幂等键会拒绝不同内容，不伪装为同一命令。

## 查询

自定义scalar提供完整值eq/ne/in/exists；按现有canonical JSON比较，忽略Map键顺序和数值容器类型，保留列表顺序。GraphQL生成具名scalar Filter，Java/REST/GraphQL共享同一规则，筛选前仍检查字段可见性。

支持完整值分组和COUNT；组的稳定顺序采用canonical表示，不代表领域上的大小关系。contains/startsWith、普通对象排序、SUM/AVG/MIN/MAX及文本搜索不会猜测opaque类型的字符串或数值语义。嵌套路径筛选和内嵌结构值Schema仍待完成。

## 指纹与升级

编译摘要、SchemaFingerprint及持久快照包含scalar名称/说明；声明顺序不改变身份。增加声明或修改说明为SAFE，移除声明为BREAKING；字段类型变更继续按原迁移规则判断。说明变化也会产生新模型身份，旧ApplicationService不能随Provider重新绑定而继续读取。

空scalars在注册表指纹中省略，旧快照缺失该字段时读为[]，保持已有registry-schema-v1摘要，不重写历史证据。新快照及新类型需要新版本运行时，不保证旧运行时能读取新增字段。

过去被忽略的scalar声明现在会改变运行模型，即使它是Date等平台标量。持久部署应先登记/审核并显式激活新的模型；启动applySchema不会自动替换旧激活。高精度默认值从旧的舍入值改成精确值时也属于真实模型变化，应检查diff。没有修改运行中的Mirror库或JAR。

## 验证和边界

测试覆盖memory/JDBC对象/关系/历史、深拷贝、非法值和列表/必填/唯一约束、嵌套CEL、Action和敏感字段、真实HTTP输入/输出与重放、旧GraphQL JSON输入/Float CEL、GraphQL变量/字面量/说明/静态SDL、精确数值重放与激活、完整值筛选/分组、Pack所有权/依赖、冲突声明、无注解实体、旧快照摘要及注册表文件重启。

本阶段不等于完整ODL覆盖：内嵌结构值、接口型动作参数、其他指令元数据/代码生成、关系写入声明约束等仍需实现；任意自定义codec插件不是当前scalar契约。查询仍由现有受控应用引擎执行，SQL下推、统一快照和真实目标数据库/性能验收继续。
