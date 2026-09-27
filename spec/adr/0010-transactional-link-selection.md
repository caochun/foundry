# ADR-0010：Action关系筛选删除与执行上下文

状态：Accepted。日期：2026-09-28。参考上游v0.3.0 Action Executor及规范§5.3。

## 关系筛选

DeleteLink保留旧linkId模式，新增filter.from/to/active及expect.ONE/ALL；二者互斥。ONE是默认值，零条返回LINK_NOT_FOUND，多条返回LINK_RESOLUTION_AMBIGUOUS；ALL允许零条。无端点的空filter匹配零条，不隐式扫描或删除整个租户的关系。

Transaction.findLinks在同一事务连接/工作快照内查询活动关系，支持两个端点的交集并按ID稳定排序，无默认100条分页截断。Action先取得写入一致性边界，再选边、逐项授权、检查匹配数量并删除；能看到此前effect刚创建或删除的关系。SQL失败使JDBC事务rollbackOnly，任何后续失败回滚该动作的此前修改、历史、审计和回执。

与固定上游实际实现相同，候选集合始终是当前活动关系；active省略、true或false均不会重新删除已结束关系。active:false不是硬删除/清理历史入口。这是对现有行为的兼容，不扩张为通用筛选语言。

## 授权与回放

新增ActionAuthorizer.allowedChanges供可信策略检查具体影响实体。ApplicationService按动作permission逐项检查选中关系及两个端点；授权先于ONE数量错误，避免通过错误暴露无权限匹配。直接ID删除也走此检查。

幂等重放根据回执中原affected逐项重新验权，关系软删除记录仍用于检查原端点。不能用“当前筛选为空”跳过原目标授权，也不能把后来新增的关系当成旧命令目标再次删除。对旧回执同样生效，权限收紧或模型移除可能使重放拒绝。

保留旧直接ID DeleteLink及默认ActionManifest的摘要编码，避免仅因Java record新增字段使既有回执误判配置冲突。非默认失败策略或不同筛选配置仍绑定不同摘要。新增筛选仅接受具备事务命令能力的Provider；其他Provider明确拒绝。

## 表达式与时间

ActionValues从写入前参数快照解析普通参数、params路径、对象ID/属性、嵌套Map、actor.id、显式单引号字面量和now。多effect更新同一对象时，写入版本取事务最新值，表达式仍读取原快照。每次执行尝试的前置条件、属性、审计和完成事件共享一次捕获的now。

这是简单路径解析，不是完整CEL effect表达式或关系路径导航；后者仍需实现。新增created-object上下文引用、条件effect等也尚未接入。

## 上游验证及副作用边界

未修改的Library schema及ReturnBook YAML已纳入测试，memory/H2单连接池验证权限前置条件、状态更新、筛选终止借阅关系、历史和幂等重放。ReturnBook只有本地effect，所以允许保留其rollback.onSideEffectFailure声明而不执行不存在的副作用。

本ADR阶段ActionManifest保留三种失败策略枚举，但sideEffects当时仍明确拒绝。上游BorrowBook仍不能执行：事件副作用在业务提交后执行，ROLLBACK_ALL涉及补偿；不能用“事务outbox写入成功”冒充事件已执行、补偿已实现。下一阶段需接通持久副作用任务、分发/恢复、失败策略及补偿验证，完整Library借还与完整Action覆盖仍未完成。

后续实现：副作用任务、处理器与补偿现已在[ADR-0012](0012-durable-action-side-effects.md)接通，上述阶段限制不代表当前全部状态。
