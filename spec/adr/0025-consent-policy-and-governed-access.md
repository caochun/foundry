# ADR-0025：Consent策略、记录及受控访问

日期：2026-09-28。状态：记录/opt-out、用途策略、读与动作检查、管理API和审计已实现；事务recordConsent effect及实时撤销影响管理待完成。

## 上游证据与领域边界

固定上游v0.3.0 / 1d7e1aa的`packages/spi/src/consent.ts`将DataPurpose定义为开放字符串，医疗常量只是预设。`packages/security/src/consent`实现同意记录、关系豁免、列表排除和动作拒绝；`packages/storage-postgres/src/consent`提供持久存储；`packages/api/src/consent/router.ts`提供记录者角色门禁及写入/拒绝审计。

Java默认不启用Consent，不把医疗或政务特定政策写进底座。部署显式配置受控对象类型、默认用途、可登记用途、记录者角色及可选关系豁免。DataPurposes保留上游标准预设；例如GOV_SUPERVISION、KYC等非预设用途同样可用。

主体采用EntityKey(type,id)，并由RequestContext明确提供租户，不使用隐式default租户或根据冒号猜测类型。多种主体类型可并存，同租户同ID但不同类型不会共享同意；配置类型必须是ApplicationService已登记的具体对象类型。

## 存储和策略

新增ConsentRecord、ConsentSnapshot、ConsentDecision、ConsentAudit、ConsentStore。内存/JDBC记录均追加，按主体内递增sequence决定最新决策，不按墙上时钟排序。opt-out单独保存；修改同意或opt-out增加revision，拒绝尝试审计不改变同意状态。

JdbcConsentStore使用`of_consent_subjects`、`of_consent_records`、`of_consent_audit`。同一主体变更以行锁分配顺序；决策/opt-out及成功审计同事务提交，审计写入失败不能留下未经审计的GRANT。记录包含操作者、证据及时间，审计另外保留traceId。没有模仿上游内存实现自动丢弃旧记录的容量上限；保留/配额应由明确运维策略处理。

ConsentService按固定上游的实际顺序判断：

1. 若当前用途匹配显式启用的豁免用途，调用普通AuthorizationService核验配置的ReBAC关系；关系成立且未opt-out时，按LEGITIMATE_INTEREST允许。
2. 否则使用该主体/用途最后插入的GRANT或DENY。
3. 无记录默认拒绝。

因此**豁免优先于显式DENY**；opt-out仅关闭豁免，之后仍可能因显式GRANT而允许。没有把opt-out改成全用途绝对禁止，也不根据用途名称自动宣称法律依据。当前实现不推导其他对象所关联的个人主体；只有配置的对象类型受Consent控制，其余类型仍受原对象/字段授权。

## 读取与动作

Consent叠加原有身份、ReBAC、字段权限和模型绑定，不能扩大这些权限。

- 集合/列表、过滤排序、搜索、聚合、ObjectSet执行：先排除未同意对象，再算分页、排序、总数、分数和统计。
- 单对象：FGA不允许或对象不存在仍不可见；FGA允许但Consent拒绝时，公共REST/GraphQL仅返回主键及`_consentRestricted=true`，不保留原始记录或系统版本/时间。GraphQL其他属性为null。
- 新`readObject`返回ObjectReadResult，受限值只含EntityKey，不含隐藏ObjectRecord。兼容的Java `getObject`在受限时返回null；正常返回保持原ObjectRecord语义。
- 历史拒绝返回空；按历史时间读取也使用当前同意，不允许通过回溯时间绕过撤回。关系导航、关系历史及计算字段会检查相关受控端点，未同意主体不进入人数/边计数。
- 动作参数中的受控对象（包括集合）、额外已有更新目标、选中的关系端点、提交前效果及重放均检查同意。创建全新主体不要求预先存在的同意；只有服务器效果证据证明创建的身份可以使用此例外，且新主体读取仍默认受限。创建回执只返回身份，重放不伪造新的GRANT。

ConsentActionAuthorizer保留原授权策略的所有回调，只叠加Consent。CONSENT_DENIED回滚业务变更/回执/outbox，并在原事务关闭后写拒绝审计，避免借第二个连接造成单连接池死锁。

JDBC动作内读取Consent复用事务连接，要求JdbcTransactionAccess及与ConsentStore相同的DataSource实例；装饰器应保留这个可信适配接口，不能悄悄退回嵌套借连接。接口只供元数据读取适配器使用，不允许其提交或关闭借到的连接。ConsentService初始化存储发生在进入对象事务前。

## 装配和管理接口

```java
ConsentStore store = new JdbcConsentStore(dataSource, dialect);
ConsentConfiguration policy = new ConsentConfiguration(Set.of("Person"), "GOV_SUPERVISION");
ConsentService consent = new ConsentService(store, authorization, policy);
ApplicationService app = new ApplicationService(storage, authorization, actions,
        schema, manifests, fieldPolicies, AuthorizationMode.STRICT_RESOURCES, consent);
```

配置默认记录者角色为admin，豁免默认关闭。配置默认用途必须可登记；可以显式提供recordablePurposes、recorderRoles和Exemption。Consent配置是可信部署配置，不是每次读请求任意选择的绕过参数。

REST保留上游`POST /api/v1/consent`，增加revoke、opt-out及管理读取：

```json
{"subject":"person-1","subjectType":"Person","purpose":"GOV_SUPERVISION","decision":"GRANT","evidence":"reviewed record"}
```

- subjectType在只配置一种类型时可省略，多类型时必须提供；subject为原始ID，不解析FGA前缀。
- purpose缺省为配置用途，decision缺省GRANT；未知/伪造身份字段或错误类型拒绝。
- POST `/api/v1/consent/revoke`要求reason，追加DENY，不删除历史。
- POST `/api/v1/consent/opt-out`要求optedOut布尔值和reason。
- GET `/api/v1/consent`、`/api/v1/consent/audit`用subject/subjectType查询，均要求记录者角色。

GraphQL提供上游ConsentInput/ConsentResult与recordConsent，并增加revokeConsent、setConsentOptOut、consentRecords、consentAudit。对象及接口增加可空`_consentRestricted`元字段。核心名称冲突拒绝；未配置服务时明确CONSENT_NOT_CONFIGURED。

管理写入及合法请求的角色拒绝均留审计；身份/参数格式无效在进入写入前拒绝。REST受控动作返回403/CONSENT_DENIED，GraphQL返回同名扩展code。

## 明确的并发与未完成范围

Consent检查是各授权阶段的当前决策读取；JDBC单次snapshot用一个联表SELECT保持记录和opt-out状态一致。它**没有锁住同意直到对象事务物理提交**，也没有跨FGA/Consent/对象数据的统一快照。最后一次检查后才发生的撤回，不能声称已取消所有在途请求。

上游revoke返回的activeSessions/subscriptionsTerminated在固定版本中只是0占位。Java不伪造这些统计；扩展撤回响应明确liveInvalidationSupported=false。活动订阅关闭、会话追踪、跨实例实时撤销及完整一致读仍待实现。

`recordConsent` Action effect、其condition、与业务效果同事务/补偿的实现仍未完成，本阶段没有把管理API记录等同于动作效果。Consent字段级restriction扩展、跨对象主体血缘映射也未实现；现有FieldPolicy继续独立执行。

独立配置发布epoch、所有拒绝路径统一审计、资源预算、实际PostgreSQL/国产库和生产性能继续验收。本阶段未启用Mirror的Consent政策，未改动运行中的业务数据库或服务JAR。

## 验证

新增25项：11个场景分别在memory/H2验证，另3项覆盖单连接池、文件恢复/审计失败回滚、配置和默认关闭。包括时钟回退下的最新序号、自定义用途、租户/类型隔离、豁免/DENY/opt-out优先级、ID-only、130个未同意对象后的正确分页、聚合/搜索/ObjectSet、历史与关系计数、动作集合/间接端点/新主体/重放、事务内复核、角色/审计和REST/GraphQL。

常规Foundry508项、Mirror根reactor618项全部通过，无失败/错误/跳过。独立探针在两种Provider确认：无同意只有身份、非记录者拒绝、仅一个已同意对象进入总数且SUM=2、撤回后集合为空、配置豁免优先于DENY且opt-out可以关闭该豁免。
