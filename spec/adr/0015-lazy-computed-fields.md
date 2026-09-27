# ADR-0015：LAZY计算字段

状态：Accepted。日期：2026-09-28。基线：上游v0.3.0 `engine/src/computed/computed-field-evaluator.ts`。

## 覆盖范围

固定上游实际实现的内置函数是countLinks，实际运行策略为LAZY：每次读取重新计算，不持久化结果、不跨请求缓存。Java覆盖这一能力；EAGER、TTL及任意自定义函数在该上游版本仍未实现，本阶段不会把接受其声明当成可执行能力。

ODL现在保留计算字段的名称、结果类型、函数、args、cache、ttl、required和sensitive。接口继承包含计算字段，禁止更改继承字段种类或保护语义；普通属性、关系字段及计算字段不能重名。编译与Provider入口共同校验，不能通过Java直接构造Schema绕过。

countLinks要求Int结果、已知关系及正确端点方向，direction默认INBOUND，支持OUTBOUND。未知函数、参数、缓存策略或TTL配置明确拒绝；计算字段不能作为对象/关系的普通属性写入。函数语义进入Schema摘要和差异，改变/移除为BREAKING；未声明计算字段的旧模型不因空列表改变摘要编码。

## 求值与权限

ComputedFieldEvaluator在可信SPI层提供原始活动关系总数，与上游内置函数含义一致。它遍历完整结果，不把默认第一页长度误当总数；按关系条数计数，不对相同端点去重，并校验Int范围。

ApplicationService公开读取按可见关系计数：源对象必须可读；端点不可见或不活动的关系不进入结果；STRICT_RESOURCES还检查关系自身viewer，ONTOLOGY_TARGETS沿用端点授权。公开计数因此可能小于内部总数，这是明确的权限收敛策略，不把隐藏关系通过统计值泄露。

计算字段本身受sensitive及显式字段策略控制。上游文件加载的storedFieldsOnly策略不误隐藏虚拟计算字段；程序式策略仍可显式限制它们。每次读取重新检查权限，没有跨主体缓存。

## API与时间

当前对象读取及列表结果合并可见计算值；GraphQL按Int生成字段并保留接口声明。REST router支持`/api/v1/{type}/{id}/computed/{field}`，与Java单字段入口共用实现。外层保持可空以容纳字段隐藏。

带双时间的列表/单字段查询，对源、关系及目标采用同一validTime和recordedTime，使用当前访问权限。原始HistorySnapshot不补入计算值，也不修改对象版本或历史；已删除的源对象不计算当前值。JDK HTTP适配器仍未解析完整时间查询参数，完整时间API继续在总计划中。

## 验证及限制

原始NHS Ward ODL已作为未修改夹具，验证currentOccupancy保留和编译；支撑Patient/Bed/关系类型由测试提供，不冒充完整NHS Pack已加载。memory/H2覆盖变更后重算、超过100条、多关系同端点、字段与对象/关系撤权、跨租户、GraphQL/REST及删除/恢复期间的时间口径。

当前计数逐页读取并逐项授权，未实现SQL聚合下推、批量授权或整次多读的一致快照。计算结果没有注入Action原始参数快照；Action计算依赖及其余ODL/查询能力仍另行处理，不因此标记整体核心覆盖完成。
