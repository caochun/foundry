# ADR-0026：事务 recordConsent Action effect

日期：2026-09-28。状态：效果、条件、事务写入、幂等重放和局部补偿已实现；完整核心覆盖仍在进行。

## 上游行为与 Java 取舍

固定上游 v0.3.0 / 1d7e1aa 的 `packages/actions/src/parser` 定义 recordConsent；executor 解析主体、执行可选 CEL condition，默认 decision=GRANT、purpose=DIRECT_CARE，evidence 是字面文本。上游注释明确其记录在业务事务外、不能回滚，且未配置 ConsentManager 时静默跳过。

Java 保留声明形状、主体引用、条件和追加证据，作以下明确调整：

- 默认用途来自部署的 ConsentConfiguration，不在通用底座硬编码医疗用途。
- 缺少可事务写入的 ConsentStore 时拒绝执行，即使 condition=false，也不静默降级。
- 同意记录、同意审计、对象/关系、动作审计、outbox、命令回执和 continuation 一起提交或回滚。
- 外部副作用失败时可追加补偿决定；不删除原始证据，不覆盖更晚的同意/撤回/opt-out。

这属于对上游一致性的加强，不宣称逐缺陷复刻，也不把基础 Consent 能力自动启用为 Mirror 的业务政策。

## 声明和条件

```yaml
action: RegisterPerson
version: 1
effects:
  - type: createObject
    objectType: Person
    target: params.id
    properties: {name: params.name}
  - type: recordConsent
    subject: person
    purpose: GOV_SUPERVISION
    decision: GRANT
    evidence: "accepted during registration"
    condition: "has(params.consent) && params.consent == true"
```

subject 支持已解析的对象参数、created-object alias、EntityKey，以及字符串 ID。Java 增加可选 subjectType：裸 ID 在只配置一种主体类型时可省略；多种类型时需显式指定。对象引用自带类型，显式类型不一致时拒绝；类型和用途都必须在可信配置允许范围内。记录不改变对象事实版本，也不额外产生 affectedObjects。

condition 在原效果位置求值，只有此前创建的对象可见。false 时不解析 subject、不写记录，但保留 skipped journal。真实 CEL 错误拒绝整个命令，不把异常当作同意；例如缺少 consent 参数时 `params.consent != false` 会报错。需要默认同意必须明确写 `!has(params.consent) || params.consent != false`；上游默认同意测试使用的是模拟 CEL，不作为真实 CEL 的缺字段语义依据。

created alias 是独立根变量，不加入 params。evidence 保留原文，允许空字符串，不作为 CEL/模板执行。缺省 decision 为 GRANT，仅接受 GRANT/DENY；未知字段、空主体/用途/条件或错误类型拒绝。自定义 ExpressionEvaluator 要支持 created bindings 必须覆写 evaluateBindings；默认适配器拒绝这种调用，避免偷偷污染 params。

完成所有普通业务效果及 allowedEffects 检查后，按原声明顺序写入排队的 Consent 效果。这样本动作的 DENY 不会使自己的最后业务检查递归拒绝。主体和条件仍按各自效果位置确定，后续业务写入失败会阻止所有同意记录。后续效果不能读取尚未写入的 Consent 元数据。

## 事务与授权

ApplicationService 从已配置的 ConsentService 自动装配 executor；独立可信调用可使用 withConsentStore。Action 的登记权限是执行其声明 Consent 效果的依据，不额外强制管理接口的 recorderRoles。普通对象参数/业务效果仍受既有身份、模型、ReBAC、Consent 和字段规则约束；元数据效果本身不递归要求“已同意才能同意”。allowedConsent 是可信的主体/用途授权扩展点。

效果在获取主体锁前、获取后、追加后复核授权，拒绝均回滚。重放和 continuation 也重新检查原 Action 权限及 journal 中的主体/用途。此检查不是跨外部 FGA 的原子撤权门禁；普通读取和不写 Consent 的动作仍遵循 ADR-0025 的当前决策检查边界。

JDBC 要求初始化后的 JdbcConsentStore、相同 DataSource 实例和相同 RequestContext。主体头的创建、行锁、追加记录、revision 和成功审计均使用原对象事务连接，不借第二连接、不提前 commit。prepareTransaction 先锁住主体，再读取 prior decision，避免补偿前像与实际记录之间被另一条决定插入。

内存 TransactionResource 让 Consent 的工作副本参与对象提交，持有 Consent 可见性锁直至对象状态发布完毕；对象 revision 冲突或参与者发布失败会撤回同意发布。内存实现只保证单进程，且事务须在同一线程使用。嵌套独立 Consent 写入明确拒绝，避免重入覆盖。事务快照及写入要求相同 RequestContext。

## 幂等与恢复

在计算 manifest 指纹前解析部署默认用途和唯一主体类型。省略配置值的清单在用途改变后不能用旧幂等键重放。没有 Consent 效果的旧清单/回执指纹保持不变。

每个 Consent 效果都有 journal 条目，包含效果位置和 applied 标志；实际应用还包含主体、用途、决定、序号和上一次显式决定。普通 format-1 回执保存 consent journal；有外部副作用时存入 format-2 的持久 ActionExecution。回放验证条目完整性、字段、序号、类型、用途及清单一致性，并重新鉴权。

同键重放只返回原结果，不重新记录 GRANT。若用户之后撤回，旧创建动作回执不会将其再次同意；读取仍按最新决定治理。此规则不豁免已有对象参数和其他业务效果的当前 Consent 检查。

ROLLBACK_ALL 按逆序补偿：

1. 每个主体第一次补偿时锁住其头，要求当前 revision 等于该动作最后一次写入的序号。更晚的其他用途、撤回或 opt-out 也视为冲突。
2. 上一决定存在时追加恢复 GRANT/DENY；原先无显式决定时追加 DENY，并在 evidence 中注明原状态为空。
3. 多次修改同主体/用途按逆序追加，不删除原记录，不回退序号；opt-out 不由本效果修改。
4. Consent 补偿与对象/关系恢复及 continuation 状态同事务；后续对象恢复因新关系依赖等失败时，已暂存的 Consent 补偿也回滚。

原先无记录与显式 DENY 在审计事实层面并不相同；选择 DENY 保持默认拒绝和证据完整，且保留上游关系豁免优先规则。版本冲突进入 COMPENSATION_FAILED，保留较新决定供人工处置，不强行撤销。

## 验证与剩余范围

专项测试覆盖 memory/H2 的条件、created roots、默认值、缺字段、用途/类型、同键重放与外部撤回、追加后失败/撤权、补偿逆序/冲突/对象恢复失败、损坏 journal、上下文隔离、单连接池、内存提交冲突和发布失败。

另有独立 JVM 在业务提交前后和补偿提交前后使用 Runtime.halt 退出，再从 H2 文件恢复，验证记录/审计/对象同进退、重放不重复追加。阶段总数及独立探针见 upstream-parity-plan。

没有新增实时会话/订阅撤销、字段级 Consent restriction 或跨对象主体血缘；没有跨 Consent、FGA、对象读取的一致快照，也没有独立配置发布 epoch。H2 事务/故障验证不能代替真实 PostgreSQL/国产数据库的并发、锁顺序、性能及运维验收。完整本体、查询下推、同步和其他上游范围继续推进。
