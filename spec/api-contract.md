# API 与内部服务契约

## 读取

平台至少提供：

```text
按类型和ID读取对象
按过滤条件分页查询对象
查询对象关系
按路径遍历关系
按版本或时间查询对象和关系
查询对象和关系历史
查询审计记录
```

GraphQL 和 REST 可以同时提供，但必须共享同一个 Application Service 和授权管线，不能各自实现一套数据访问逻辑。

## 写入

生产 API 不暴露通用对象 CRUD 写入。写入入口是：

```text
POST /api/v1/actions/{actionType}
```

同步服务也必须使用受控的同步命令或内部 Action，而不是直接写表。

## 时间查询

时间查询必须明确参数语义，例如：

```text
asOfValidTime
asOfRecordedTime
asOfVersion
```

不能把数据库当前时间、业务生效时间和历史记录时间混为一个 `timestamp`。

## 分页和并发

- 大列表使用稳定游标分页；
- 写入使用幂等键或期望版本；
- 批量操作返回逐项结果；
- 错误必须包含机器可读 code 和可定位路径。

