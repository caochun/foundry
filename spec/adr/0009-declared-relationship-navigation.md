# ADR-0009：声明关系导航与受控读取

状态：Accepted。日期：2026-09-28。参考上游v0.3.0，规范§2.1.3。

## 声明与存储

`@link(type, direction, history)`保存为独立LinkFieldDefinition，属于读取投影，不混入存储属性。可返回另一端对象或关系记录本身，保留列表、元素非空、外层required、sensitive及history标记；direction默认OUTBOUND。接口可继承关系字段，同名声明不得冲突或换成普通属性。Java直接构造Schema与ODL编译使用相同校验。

校验关系类型、端点方向、输出类型、列表基数和history列表要求。关系字段不能当作createObject/updateObject属性写入；关系事实仍经createLink/deleteLink创建或结束，并保持自己的ID、属性和历史。关系投影新增分类SAFE，改变或移除分类BREAKING，摘要包含投影声明。

## 读取边界

ApplicationService.readLinkField接收源EntityKey和注册字段名，不接受调用方自定义关系路径。GraphQL按声明生成字段；REST路径为`/api/v1/{type}/{id}/links/{field}`。集合支持first/offset，分页在权限过滤之后计算。

源对象、每条关系及目标对象均检查viewer权限；源导航字段受字段策略/sensitive保护，目标对象和关系属性分别按自己的字段策略裁剪。不可访问源或隐藏字段返回null；可访问集合没有可见结果时返回空列表。GraphQL投影字段外层可空，防止权限隐藏引发整条记录非空错误。

`history: true`表示包括已经结束的关系，每个关系ID最多一个最新投影，不是返回每个修订版本。返回关系类型时，即使历史端点已软删除，仍可在其viewer权限允许时读取该关系；返回对象类型时不返回已删除对象。多个关系指向同一对象时按关系行返回，不做对象去重。

## 当前限制及后续

本阶段是当前状态导航及已结束关系查询。QueryOptions中的asOf或调用方includeDeleted在此入口明确拒绝，避免将历史边与当前端点拼装后冒充时间一致视图；Storage SPI已有时间遍历，仍需接入完整通用API。

读取目前逐页使用Provider查询并逐条授权，未提供整个导航过程的并发一致快照或批量授权优化。关系字段仅能从已存在的活动对象发起，关系到关系端点仍受现有SPI对象端点限制。`@immutable`、`@constraint`等未实现的关系字段写入语义明确拒绝，不能作为上游覆盖完成。required是模型声明，当前未用于提交时强制对象必有关系。

计算字段、跨Pack组合、关系过滤/排序与计数、Action中的关系路径解析等仍待实现。本次不能等同完整ODL、完整关系API或上游Library借还流程已运行。
