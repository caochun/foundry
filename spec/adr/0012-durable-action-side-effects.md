# ADR-0012：Action副作用执行与补偿

状态：Accepted。日期：2026-09-28。

目标是执行固定上游的事件/webhook副作用及三种失败策略，不以outbox入队代替副作用成功。

## 执行协议

- 原始业务effect、补偿依据、解析后的副作用任务、执行记录及幂等回执同事务写入。
- 事务提交后才调用副作用处理器，调用期间不占数据库连接。每次调用使用稳定的actionId/taskName幂等标识。
- 执行记录具有版本、状态和下次可处理时间；租约token放在不可变JSON状态中。短事务领取和完成确认均检查token，进程退出后到期可接管。
- 有重试等待时返回明确PENDING状态，不假报已完成；重新执行同一幂等键或显式resume只继续副作用，不重复业务effect。
- LOG_AND_CONTINUE耗尽尝试后继续下一任务并记录警告；RETRY_INDEFINITELY保持可恢复重试；ROLLBACK_ALL耗尽尝试后进入补偿，不再执行后续任务。

## 补偿

按相反顺序恢复本地业务效果，作为新的历史变更记录，不能抹除原事实。写入前检查动作最后写入版本，若期间有其他写入则拒绝覆盖并报告COMPENSATION_FAILED。恢复旧属性必须保留字段缺失/null差异；恢复已结束关系须保留原ID并追加恢复事实，不能伪装为原删除未发生。Schema/唯一/基数/端点约束继续生效。

本地补偿不声称能撤销已经成功送达的外部通知。回调成功但完成回执未落库时仍可能重试，接收方须使用稳定幂等标识。

## 本阶段验收

1. 原始Library BorrowBook/ReturnBook schema与YAML成功运行，事件数据包含解析后的ID。
2. 三种失败策略、重试等待、进程恢复、同键重放和并发领取均有memory/H2验证。
3. 更新/创建对象、创建/删除关系的补偿，以及第三方写入冲突、失败恢复和回滚历史验证。
4. 默认没有副作用处理器时拒绝执行含副作用动作，不能静默跳过；HTTP处理器只访问可信配置允许的端点，测试仅使用本地服务。
5. 既有无副作用动作、回执摘要和Mirror兼容性保持验证；完整上游覆盖的其余缺口仍单独列出。

## API、调度与升级契约

ActionResult新增status和errors：PENDING/RUNNING/COMPENSATING不是最终失败；COMPLETED与COMPLETED_WITH_WARNINGS为成功，ROLLED_BACK和COMPENSATION_FAILED为失败。批量结果单独计pending，不把等待中的动作计作failed。

Java入口提供resume与按当前tenant/actor扫描的resumePending。REST提供`POST /api/v1/actions/{action}/executions/{actionId}/resume`，只使用已注册定义和原执行记录，重新检查主体、动作及原影响实体权限，不接受客户端提供替代任务配置。宿主负责定时调用恢复入口；本阶段没有自动部署后台调度服务。缺少或改变原manifest/Action定义时拒绝恢复，不能靠重新执行业务effect修复；部署升级须保留原定义。

JDBC新增of_action_executions，业务effect、执行JSON及格式2命令回执原子提交。格式2回执只指向执行ID，以执行记录为当前结果来源；旧格式1仍可读取，旧无副作用manifest（含显式失败策略）的摘要编码保持不变。执行状态JSON格式1不接受未知/损坏版本，不自动猜测修复。

重试默认最多三次尝试，默认间隔200ms，声明上限1000次、基础间隔最多一天。指数延迟上限为五分钟与声明基础间隔的较大值，不缩短调用方声明的长间隔。等待通过availableAt持久化，不占连接休眠；RETRY_INDEFINITELY不会因达到声明次数而结束。进程中断带来的未知结果允许再次领取，因此不能把次数上限当成外部严格不重复的保证。

默认租约一分钟，可配置，回调期间没有自动续租。过期旧回调不能更新或补偿已被新工作线程完成的动作。已经送达但未保存结果时可再投递，保持稳定幂等键和原始事件时间、业务事务ID。

StandardSideEffectHandler调用配置的EventSink或被可信策略允许的HTTP端点。HTTP禁用自动重定向，只接受2xx，携带不可被manifest覆盖的Idempotency-Key；默认超时10秒、可配置到120秒，应与执行租约匹配。event.data和webhook.body递归解析简单上下文表达式；webhook省略body时使用原动作上下文快照，与上游默认行为对应。没有配置相应处理器时，在业务写入前拒绝含该类副作用的动作。

createObject支持上游省略target的生成ID模式，并把新对象按类型首字母小写加入执行上下文；不覆盖既有参数。创建对象的补偿还会检查本事务中剩余的活动关联，第三方后来建立的关系会阻止删除。恢复关系以RESTORED追加新生命周期，时间投影区分每轮激活与结束。

## 验证与仍未完成的范围

除成功与三种策略外，独立JVM验证业务提交后、回调后、补偿提交前和提交后强制退出；两JVM共享同一业务执行与有效任务领取。故障注入验证执行记录保存失败会回滚业务/历史/回执，补偿版本冲突会回滚整组逆操作。HTTP恢复入口重新检查权限与主体隔离。

原始core/library manifest、ODL、BorrowBook/ReturnBook及目录数据用于兼容测试；测试显式提供受控授权，并自行初始化目录数据。因此不将权限文件自动装配、Pack种子自动执行、跨Pack模型组合或真实事件总线/国产库联调算作完成。声明式条件effect、Consent/Undo、计算字段、类型化API、持久Schema Registry及运维修复入口仍按总计划推进。

本阶段曾确认的权限集成缺口：当时通用API对所有对象引用及影响实体使用同一动作关系，上游Library的book声明can_borrow/can_return而member没有这些关系。本阶段受控授权验证业务执行链，不能据此宣称原FGA文件可直接投入使用；权限目标映射及真实模型联调列为后续F1任务。

后续：[ADR-0013](0013-ontology-authorization-targets.md)增加显式本体目标模式并完成原始Library模型的真实OpenFGA验证；旧严格模式继续保留，自动权限资产装配与身份系统仍待完成。
