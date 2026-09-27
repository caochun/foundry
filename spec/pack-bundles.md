# 使用组合Pack

```java
var bundle = new DomainPackLoader().loadBundle(List.of(coreDirectory, businessDirectory));
storage.applySchema(bootstrapContext, bundle.ontology().schema());
var seeds = new PackSeeder().apply(bootstrapContext, bundle, storage);
var application = ApplicationService.fromBundle(storage, authorization, actionExecutor,
        bundle, AuthorizationMode.ONTOLOGY_TARGETS);
```

bootstrapContext必须明确tenant和稳定actor。初始化后按`namespace:ref`获取实际ID，例如`seeds.references().get("example.library:book-dune")`。再次初始化返回零新增并保留后续业务修改；种子定义变化需要迁移，不是更新数据的接口。

bundle同时提供原manifest、全局类型所有者、字段策略、FGA源文件、连接器声明和capabilities。字段策略自动装入ApplicationService；其余外部能力需宿主显式接线。读取一个Pack不会发送通知、连接数据源或发布权限模型。

依赖支持精确SemVer及>=，跨Pack引用需要声明依赖。输入目录顺序不影响摘要，冲突不会隐式覆盖。FGA模型编译和部署仍使用独立流程，见openfga-integration.md；完整边界见ADR-0014。
