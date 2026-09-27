# ADR-0011：事件领取、完成回执与中断恢复

状态：Accepted。日期：2026-09-28。

## 问题与边界

旧OutboxDispatcher直接读取pending，没有跨工作进程的领取互斥。旧JdbcIdempotentEventSink在回调前插入“已消费”，进程在其后退出会永久抑制未执行事件；内存sink也会把失败或仍在执行的事件误当成功。这些行为必须修正，才能作为后续Action副作用恢复的基础。

本阶段处理通用事件可靠投递，不等于Action.sideEffects、ROLLBACK_ALL补偿或完整Library借还已实现。

## Outbox协议

OutboxStore新增claim、renew、complete、fail。claim返回事件、随机领取token、attempt及leaseUntil；complete/fail/renew均要求同租户、同事件、同token且租约仍有效。到期可重新领取，新token会使旧工作线程失去更新资格。attempt是领取次数，不保证回调已经开始。

JDBC新增of_outbox_delivery保存租约和下次尝试时刻，不改变原事件payload表和事务生产者。候选查询后锁定原事件行，再复核published和租约状态；领取/确认均在短事务内完成，回调不占数据库连接。使用READ_COMMITTED，避免等待锁后仍读取旧状态。

Dispatcher默认租约一分钟，失败重试延迟从一秒指数增加，最多五分钟；均可通过构造参数/代码策略配置初值。逐条回调前续租，已过期的批次项不执行。旧markPublished入口仅用于尚未进入租约投递的记录，不能绕过领取凭证。

## 消费回执

去重作用域为consumer＋tenant＋CloudEvents source＋id，并绑定事件内容摘要。相同身份不同内容明确拒绝。回调成功后才写completed；同一身份正在处理时返回BUSY，绝不能向调用方假报成功。异常释放领取，进程消失后由租约到期接管；旧token无法写入新一轮完成状态。

JDBC回执存入of_event_delivery_receipts。内存sink等待同身份的在途回调完成，失败后可重试并保留内容绑定，但进程重建不保留回执。事件payload在SPI、outbox与CloudEvent边界深拷贝为不可变JSON结构，允许可选null字段。

这是至少一次投递：回调已经产生外部效果而进程在记录完成前退出，或回调超过租约时，可能重投。稳定事件ID必须传递到外部接收方做幂等；不能声称普通外部回调具有数据库意义上的exactly-once。回调需设置超时并匹配租约时长；当前不在回调期间自动后台续租。

## 升级

1. 停止旧版投递工作进程后再启用新协议，避免旧SQL客户端绕过租约；启动时调用JdbcEventStore.initialize创建附属表。原事务生产者和已有事件payload不用改写，原published状态不自动重置。
2. 旧of_consumed_events记录没有tenant/source/consumer，也不能区分“领取”与“完成”，不能自动导入新完成回执。遇到相同eventId的旧记录默认返回LEGACY_REVIEW_REQUIRED。
3. 在有外部成功证据时，可信迁移代码可调用confirmLegacyCompletion；明确接受可能重复执行时可调用allowLegacyReplay。两者均绑定具体新作用域和内容，不能覆盖在途租约或重置已完成回执。测试只操作隔离数据；本次未对运行库作决定或迁移。
4. 新scope和事件source配置应保持稳定。更换consumer表示另一个订阅者，应有独立回执；不是清除旧幂等记录的捷径。

## 验证及剩余工作

共享memory/H2测试覆盖租约排他、到期边界、重试时刻、续租、跨租户和旧token隔离、批次超时。独立JVM在领取后、回调前后Runtime.halt，证明重建后恢复而不凭空推定完成；两JVM经本地H2 TCP竞争只有一个有效领取者。真实事务生产者、消费回执与outbox确认故障组合测试验证单连接池和不同连接默认配置。

真实PostgreSQL/国产库、数据库与工作节点时钟误差、较大队列性能、死信/运维查询和自动回调续租仍需单独验收。Action副作用任务、webhook、策略状态机和失败补偿继续推进，不把本阶段视为完整F4。
