# ADR-0028：Action关系路径、读取证据与续执行

日期：2026-09-28。状态：声明关系的事务内路径解析、权限/Consent、重放及持久续执行已接通；完整表达式AST和跨服务一致快照仍待完成。

## 上游证据与本阶段范围

固定上游v0.3.0 / 1d7e1aa 的 action-executor.ts 在前置条件之前调用 preResolveLinkPaths，扫描CEL、效果和事件data中的点路径，通过 resolveTarget读取声明的 @link 并缓存。例如patient.currentBed、bed.ward.id随后可作为效果目标和插值来源。

上游当前使用事务外Storage查询，并在多条关系中取第一条。Java沿用声明路径与缓存上下文，改为在对象事务内读取；单值歧义拒绝，声明集合返回集合，history字段可读取已结束关系。应用权限、Consent和持久读取证据也进入这条链路。

另已核对：该固定版本resolveParamObjects仅解析schema.objectTypes中的具体对象参数；接口参数直接透传，GraphQL生成器也没有把接口变成合法多态输入。这不是上游已经完成的多态对象解析。本计划中的接口参数、内嵌结构值仍保留为待明确实现的模型能力，未冒称本次已覆盖。

## 解析与上下文

```yaml
preconditions:
  - expr: "person.unit.region.name == 'North'"
    error: "region is not ready"
effects:
  - type: updateObject
    target: person.unit
    set: {note: person.unit.region.name}
sideEffects:
  - name: notice
    type: event
    config:
      type: unit.updated
      data: {region: person.unit.region.name}
```

ApplicationService自动提供已登记的Schema。可信独立ActionExecutor调用须用withParameterSchema提供声明；没有模型的旧底层调用不能推导关系。字段只根据LinkFieldDefinition解析，不把普通JSON内的id/_type或任意Map提升成对象身份。

路径支持：

- 参数根变量和params前缀、简单多跳点路径、正反向声明关系。
- CEL前置条件、updateObject目标/set、createObject属性/参数路径ID、createLink端点/属性、deleteLink筛选端点或直接ID表达式。
- 事件data和webhook body的嵌套Map/List插值；本动作新建对象的别名在创建后可供后续效果/通知解析。
- recordConsent条件和主体路径；condition=false不会解析其主体或写入读取证据。

原输入对象的声明路径在业务效果前捕获；后续效果结束该关系时，当前动作仍沿用已经捕获的对象和值。新建别名的路径在首次实际使用时从当前事务解析，可见前面的关系写入。只有被路径实际触及的关系字段进入上下文；不递归展开整个对象图。

单值关系不存在时为null，列表为空时为[]；单值命中多条时拒绝。列表中可在CEL索引/遍历普通存储属性，关系返回类型声明为LinkType时可访问该关系的属性。效果属性引用整个实体返回ID，实体列表返回ID列表；updateObject和关系端点仍要求单个对象引用。

路径发现仍是明确的静态点路径扫描，字符串字面量不触发读取。CEL动态下标、遍历局部变量后的进一步关系跳转、任意函数产生的对象身份等未实现；不能把它说成完整CEL依赖分析。每次执行最多256个关系路径、1000条被选关系，路径最多16段，超限失败而非静默截断。

## 事务与历史关系

新增Transaction.findLinks(type,from,to,includeDeleted)，原三参数版本仍只查活动关系。内存/JDBC使用同一事务工作副本/连接，包含前序写入。history=true返回当前保存的活动及已结束关系身份；它不是任意双时间关系快照，也不返回每次关系属性变更的所有历史版本。

普通输入记录先在命令事务中刷新并检查原版本。关系取值、目标变更、读取证据、审计/outbox/回执和continuation同事务。默认JDBC租户写入门禁、内存revision冲突及既有Schema激活门禁继续生效；没有另外借连接查询关系。

## 权限与同意

ActionNavigationRead携带真实source、声明字段、选中关系及端点，交给ActionAuthorizer.allowedNavigation。默认低层策略仍由可信装配提供；ApplicationService施加完整的读取策略：

- 原Action和参数权限先通过，再读关系；即使无关系也检查源对象和关系字段。
- 关系字段遵循敏感标记及字段策略，源对象和相关端点须可查看。
- STRICT_RESOURCES要求查看实际关系身份；ONTOLOGY_TARGETS按端点检查，不要求上游模型不存在的LinkType FGA类型。
- 本动作创建证据可证明新对象/关系，不要求在创建之前存在其权限元组；仅靠客户端身份字段不能取得此例外。
- 任何不可读目标导致整个命令拒绝，不在Action中偷偷过滤成员而改变条件含义。
- 能查看不代表能修改。沿路径更新额外已有目标时，STRICT_RESOURCES检查该目标Action权限，ONTOLOGY_TARGETS检查editor；提交前复核更新权限。STRICT_RESOURCES创建关系时也要求已有端点的Action权限；ONTOLOGY_TARGETS继续使用上游的端点viewer规则。

ConsentActionAuthorizer对实际读取的受控源对象/端点检查同意，已创建身份例外与现有规则一致。提交前拒绝会回滚命令，Consent拒绝审计按原有事务关闭后记录。提交后续执行遭拒不会撤销已提交的业务事实，而是保留任务等待有权限的后续处理；不能把此时的权限错误说成业务回滚。所有路径和值捕获及最后读取检查在本Action写入Consent决定之前完成，避免本次DENY使自己的最终业务读检查递归失败；以后的重放/通知仍受当前Consent限制。

与原Action参数一样，已批准的服务端清单在其执行上下文读取存储属性；这不是向调用者开放任意路径查询，也没有新增任意表达式HTTP入口。公开普通读接口继续执行其属性投影策略。

## 读取证据和幂等恢复

格式1命令回执新增navigation；有外部副作用时证据保存在ActionExecution。每条记录保存路径、源实体身份、声明字段摘要及实际关系ID，不用之后新关系重新解释旧动作。navigationDigest绑定整个读取列表，检查意外丢失/截断/修改；它不是对抗数据库管理员重写全部证据的密码学签名。

首次提交前、回执重放和每次continuation授权均校验字段声明/端点/路径一致性，并重新检查读取权限和Consent。Manifest可判定的已访问路径必须有证据，包括实际应用的条件Consent主体；损坏或缺失证据拒绝，不把[]当成任意动作的合法补全。

关系后来结束或替换时，重放仍检查原关系及原端点；不会重新执行前置条件、业务写入或重建通知值。首次提交时的通知数据保持不变，撤回原端点权限则禁止后续投递。补偿使用原变更日志并继续检查读取证据，不能靠补偿跳过撤权。

不含关系路径的旧回执/continuation仍兼容；需要路径却没有证据的旧记录拒绝重放/续执行。不能从现在的关系补造过去的读取依据，升级时应明确处理这些待执行记录。清单/请求指纹没有因新增证据字段统一重写。

## 验证与剩余边界

memory/H2共享用例覆盖多跳CEL/效果/通知、空关系、集合/已结束关系、关系字段/端点权限、查看与更新权限分离、关系替换后的重放、撤权中止/事务回滚、续执行原值、补偿、损坏证据、两种授权模式、Consent及条件跳过、创建别名、筛选删除和派生ID。另有单连接池和独立JVM在提交后/通知确认前退出恢复测试。

独立审计探针验证两种Provider都写入North、只投递一次、结束原关系后同键返回原结果，且撤回原关联Region的权限后重放拒绝。详细测试总数记录在upstream-parity-plan。

外部FGA/Consent检查仍是阶段性决策，不宣称与数据库提交共同锁定的撤权屏障。完整表达式依赖图、历史时间参数、查询下推、同步/血缘/Checkpoint、完整API/代码生成及生产目标库验收继续。未改动Mirror运行库和服务JAR，完整核心覆盖目标保持active。
