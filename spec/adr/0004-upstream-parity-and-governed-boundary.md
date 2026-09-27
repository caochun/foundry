# ADR-0004：上游对齐与受控执行边界修复

状态：Accepted。日期：2026-09-27。

对照上游syzygyhack/open-foundry v0.3.0（1d7e1aa）与Java初始提交ff944f7的源码和隔离探针，确认已有模块不等于完整能力。原tasks.md的勾选仅代表v0.1基线，不能作为上游等价或生产安全验收。

## 决策

1. Foundry保持领域中立。上层业务Handler无需转写为平台内置政务流程。
2. 平台修复优先于继续扩展Mirror。执行次序和未完成项记录在upstream-parity-plan.md。
3. 通用Action执行必须显式注入授权策略，默认拒绝。ApplicationService只接受启动时可信注册的Schema/Manifest；调用方不得提交任意效果或伪造对象快照。ODL显式permission保留到运行元数据；无权限声明不开放执行，不通过名称猜测扩权。
4. 每次执行包括幂等回放都校验租户、主体和权限。类型化对象参数按当前租户重新读取；Action参数约束在效果执行前验证。
5. 幂等键按tenant/actor/action隔离，并绑定配置与请求摘要；不同请求复用键拒绝。内存实现提供进程内原子重放，但不能宣称持久、跨进程或宕机恢复。事务内持久回执需后续独立完成。
6. 所有通用对象读取与历史读取统一应用字段白名单。未注册schema默认不返回业务属性；无显式策略时仅返回schema声明的非敏感属性；显式策略按角色决定可见字段。GraphQL非主键允许null以容纳字段隐藏，并正确解析properties。
7. 不支持的协议行为应明确拒绝；不能通过静默忽略权限/语义字段宣称兼容。完整ODL、原生关系读取、查询能力和时间语义按后续阶段推进。

## 兼容与迁移

ActionTypeDefinition保留两参数构造器，但其permission为空，默认不可执行。旧ApplicationService构造方式保留受限只读能力；需要完整字段/Action的调用方须提供可信schema、manifest及字段策略。低层Storage SPI仍为可信内部接口，不是公开CRUD；Provider校验/时间语义是另一阶段的工作。Mirror现有自定义BusinessCommands不因平台新增API约束而获得或失去业务权限。

## 本阶段执行细节

- Action权限声明由ODL保留，Schema摘要也包含permission。ApplicationService对`ActionType/<动作名>`检查声明的权限，再对所有对象参数检查同一权限；删除关系还检查关系及其两端对象。授权模型需提供对应ActionType关系，不能从角色名称推断全局执行权限。
- 低层ActionExecutor默认拒绝；可信程序需显式注入ActionAuthorizer并提供带权限的ActionTypeDefinition。旧无定义执行/批量调用不再作为绕过入口。
- API Action参数中的对象只接受ID；在当前租户解析成真实记录后交给CEL及效果执行。`actor/params/now`保留，不能被Action参数覆盖。
- 不支持的sideEffects、rollback、reversible及未知效果键明确拒绝；这不表示相应能力已实现。
- OpenFGA Check使用`/stores/{store}/check`和body的authorization_model_id；用户及资源的ID使用`base64url(tenant).base64url(localId)`，元组配置方须共用OpenFgaResourceIds。已有未隔离元组须重新供应，不提供旧编码回退。HTTP请求超时为5秒，真实模型/授权生命周期仍待集成。

数据库数据布局未改变。此次验证未重建运行中的Mirror JAR、未迁移业务数据或部署生产权限模型。
