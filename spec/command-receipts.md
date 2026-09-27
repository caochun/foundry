# 事务命令回执与幂等契约

设计依据：[ADR-0007](adr/0007-transactional-command-receipts.md)。

## 使用

内存和JDBC Provider的StorageCapabilities.transactionalCommandReceipts为true。ActionExecutor在提供幂等键时优先采用此能力，不要求额外注入IdempotencyStore；即使传入旧内存Store，也以Provider事务回执为准。没有该能力的第三方Provider须明确提供兼容Store，不能默默退化为无幂等写入。

键按tenant/actor/action分隔并散列，绑定manifest、Action类型与参数摘要。对象参数绑定稳定身份，不把服务器当前属性快照当作请求内容。用户的原始键和完整请求正文不保存在回执中。

Transaction新增：

- acquireWrite：进入Provider的写入一致性边界。
- getObject/getLink：读取同一事务内状态，包括自己的写入。
- getCommandReceipt/putCommandReceipt：当前租户和主体的不可覆盖回执。

自定义Provider只有在正确实现这些方法后才能声明能力。JDBC使用of_command_receipts，并复用租户行锁；写事务明确选择READ_COMMITTED，使等待行锁后的读取能看到前一提交者的回执。历史遍历仍采用独立的只读一致快照。

## 执行与回放

1. 校验类型参数、主体、租户和权限。
2. 开始事务、取得写入边界，重新读取对象参数并再次授权。
3. 读取回执：摘要一致则返回原Action ID和受影响实体，不重复效果、审计或outbox；摘要不一致拒绝。
4. 无回执时校验对象版本及当前前置条件，执行事务效果。
5. 成功结果、业务变更、历史、审计、outbox同事务提交。

前置条件失败及回滚不消耗成功键，后续可重试。已成功请求即使当前业务前置条件改变仍返回原成功，但当前权限检查必须通过。权限撤销后不能用回执绕过授权。内存事务冲突最多重试8次，且每次重新验权；只有明确的TransactionConflictException可重试，不能重试任意业务错误或猜测未知提交结果。

多次效果修改同一实体采用事务内最新版本写入；前置条件和引用表达式仍基于效果执行前捕获的参数。自定义授权策略如需读取底座状态，应覆盖带Transaction的allowed重载，避免持有业务连接时另取连接。

不完整或未来未知格式的回执报错，不通过再执行动作“修复”回执。只保证事务性数据库效果，不把外部HTTP调用或消息消费者纳入一次性执行保证。

## 持久化与升级

of_command_receipts以tenant_id＋receipt_key为主键，保存actor_id、action_name、request_hash与最小result_json。结果格式目前为1；改变结果或指纹编码必须评审兼容及迁移，不能覆盖已有回执或静默换算法。

JDBC回执随数据库恢复；内存Provider重建后不保留状态，不能称为跨进程持久。回执不自动过期，以免旧请求重新执行；未来归档须保留去重依据。

旧进程内IdempotencyStore中的历史结果不可恢复，不能自动变成持久回执。升级时应排空旧请求并明确历史重试边界；没有可信结果证据时不伪造回执。本版本不迁移Mirror自己的BusinessCommandReceipt业务对象，也没有自动补造过去命令的回执。

## 验证证据

- H2文件库重建Provider/Executor，返回同一生成对象ID及Action结果。
- 两Provider多线程、两个独立JVM经本地H2 TCP服务并发提交同键，仅一份对象、历史、回执、审计和outbox。
- 子进程在物理提交前Runtime.halt，无部分记录；提交后Runtime.halt，重试返回原保存结果。
- 回执插入故障回滚全部业务事实；条件失败可再试；请求变化、跨主体/租户、撤销权限及损坏结果均正确处理。
- 更高DataSource默认隔离级别、等待锁后权限改变、同对象多效果、单连接池关系删除回放均有测试。

以上不代替真实PostgreSQL/国产数据库、外部身份系统和副作用投递验收。
