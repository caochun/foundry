# Schema 编译与 Domain Pack

## 输入

Domain Pack 至少可以包含：

```text
pack.yaml
schema/*.odl
actions/*.yaml
permissions/*.fga
connectors/*.yaml
seed/*.yaml
```

## 编译阶段

1. 发现并读取 Pack manifest。
2. 校验名称、版本、命名空间和依赖。
3. 解析 ODL 为平台 AST。
4. 校验对象、关系、字段、指令、约束和引用。
5. 合并 Pack，处理命名空间和冲突。
6. 解析并校验 Action、权限、连接器和种子清单。
7. 生成 `CompiledOntology` 中间模型。
8. 由中间模型生成存储模型、API 模型、权限模型和 Action 注册表。
9. 对比已登记 Schema，生成 diff 和迁移分类。
10. 应用安全的迁移；破坏性变化必须有已批准的迁移计划。

## 编译产物

```text
CompiledOntology
RuntimeSchema
StorageModel
GraphQL Schema
REST/OpenAPI Contract
Authorization Model
Action Registry
Connector Registry
Seed Registry
```

编译不是生成一个与领域绑定的可执行二进制文件，而是生成可被运行时装配和校验的元数据及契约。运行时仍通过通用引擎执行对象、关系、Action 和查询。

## 稳定性要求

- 同一输入 Pack、同一编译器版本和同一能力配置必须产生稳定的 Schema 摘要。
- Pack 加载顺序必须确定，不能依赖文件系统返回顺序。
- 已发布 Schema 版本不可原地修改。
- API、数据库迁移和权限模型必须引用同一个编译版本。
- 编译失败时不得启动使用部分模型的生产服务。

