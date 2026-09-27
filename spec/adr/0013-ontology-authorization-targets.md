# ADR-0013：本体动作权限目标与OpenFGA模型映射

状态：Accepted。日期：2026-09-28。

## 对照与模式

固定上游v0.3.0的`packages/api/src/server.ts`从Action定义选择首个ObjectType参数，`config.ts`只在该对象上检查动作关系。Library的book声明can_borrow/can_return，member声明viewer/editor；LinkType和ActionType没有相应实例关系。旧Java统一检查ActionType、所有参数及影响实体的同名动作关系，无法直接使用该模型。

新增显式`AuthorizationMode.ONTOLOGY_TARGETS`，只由可信启动配置选择。既有ApplicationService构造器保持STRICT_RESOURCES，保留现有调用方的ActionType及独立关系ACL约束，不自动放宽旧部署。新模式与OpenFgaHttpAuthorizer.forOntology配套使用。

## 新模式的检查

- 按定义顺序选首个ObjectType参数，跳过标量；检查其声明permission。集合逐个检查全部主目标；缺失/空主目标拒绝，不转用参与对象。
- 其他对象参数检查viewer；主目标已有动作权限时不额外强制viewer。
- 注册效果修改其他已存在对象，在值校验及写入前检查该对象的editor。此项比上游仅检查主目标更严格，用于避免把参与对象的只读权限当成修改权限。
- 关系读取由两端viewer决定；动作内建立/删除关系检查两端属于已授权主目标、可见参与对象或本动作创建的对象，不查询模型不存在的LinkType.can_*。
- 本动作创建的对象无需预先拥有不存在的资源元组。该授权只覆盖注册动作中的创建、后续效果及原任务恢复，不授予通用读取权限。
- 没有对象参数的动作仍需ActionType上的显式动作授权；不复制上游对未映射动作直接允许、只依靠后续CEL的做法。该模式下ActionType为保留类型名。

## 写入与恢复证据

ActionExecutor在提交前根据服务端真实变更构造ActionEffectAccess，并交给allowedEffects；拒绝会回滚全部业务、历史、回执和事件。关系筛选在披露匹配数量前也检查端点；此前同事务创建的对象由已发生的效果记录证明。

新普通回执在既有格式1中追加最小access列表（kind/type/id），不包含原属性内容。含副作用的格式2使用原持久journal。重放、续执行和补偿均依据这些服务端记录重新检查当前主目标、参与者和额外编辑权限。客户端参数不能提供或覆盖这些记录。

旧格式1若没有access，不从创建人、版本或调用方输入猜测创建授权；非主目标对象采取editor等保守检查，可能需要原权限或人工迁移。损坏access明确拒绝。请求指纹不因新增授权记录而改变，STRICT_RESOURCES仍采用原重放检查。

## OpenFGA模型与ID

新增显式类型映射，按上游规则把PascalCase转换成snake_case，并拒绝保留名/碰撞。用户与对象本地ID继续使用已有base64url(tenant).base64url(id)，不丢失租户隔离。原无映射构造器保留原类型拼写；没有自动双编码回退，切换模式需按新类型名供应元组。

forOntology读取指定的真实服务模型ID，启动前检查对象viewer和每个动作主目标关系。额外编辑目标使用editor，模型和元组须提供该权限。Check使用HIGHER_CONSISTENCY，只有JSON布尔true才允许；错误、异常或缺失关系均拒绝。

## 验证与边界

原始Library DSL由官方@openfga/syntax-transformer 0.2.2转换，无关系补丁。隔离OpenFGA 1.21.0的真实HTTP集成验证memory/H2借还、关系历史、撤权重放、参与者撤权后的整笔回滚、相同本地ID跨租户隔离和启动模型门禁；H2使用单连接池。

这不等于OIDC或服务认证已完成，也未覆盖权限文件自动加载、字段关系权限解析、自动元组生命周期、真实国产库、生产运维。宿主必须选择并配置权限模式、可信身份和元组供应；原严格模式仍受支持。相关缺口继续保留在总计划。

后续：[ADR-0014](0014-pack-composition-and-assets.md)接通Pack字段策略与声明资产读取，并验证显式种子初始化；FGA模型生成、部署和元组生命周期仍是独立未完成项。
