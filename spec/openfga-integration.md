# OpenFGA本地集成验证

本阶段验证OpenFGA 1.21.0的真实关系计算。使用独立内存实例和随机命名测试store，不读写生产store。OIDC及服务凭据测试不包含在此流程。

## 启动隔离服务

```sh
openfga run --datastore-engine=memory \
  --http-addr=127.0.0.1:8081 --grpc-addr=127.0.0.1:8082 \
  --metrics-enabled=false --playground-enabled=false \
  --profiler-enabled=false --trace-enabled=false
```

此次运行使用官方Darwin arm64 v1.21.0归档，其SHA-256与GitHub发布资产一致：`cb8adb607357c735c97412fe6a9c6bd30e10a0ab3838ef00f802f55276fb9baa`。归档不提交到仓库。

## 执行

在Foundry仓库目录：

```sh
FOUNDRY_OPENFGA_URL=http://127.0.0.1:8081 mvn -Popenfga-integration -pl foundry-api -am test
```

仅运行真实集成用例：

```sh
FOUNDRY_OPENFGA_URL=http://127.0.0.1:8081 mvn -Popenfga-integration -pl foundry-api -am \
  -Dtest=OpenFgaLibraryIT -Dsurefire.failIfNoSpecifiedTests=false test
```

普通`mvn test`不执行`*IT`，不会把外部服务缺失计作跳过后通过。显式启用该profile而没有可用服务会失败。测试只接受loopback地址，每个用例创建独立store并在结束时删除；服务进程由运行者关闭。

模型来源是固定上游Library的原始library-roles.fga，由官方`@openfga/syntax-transformer@0.2.2`的`transformer.transformDSLToJSONObject`生成。DSL和JSON校验和见`foundry-api/src/test/resources/openfga/README.md`，无手写补充can_*关系。

## 应用接线

```java
var relationshipAuthorizer = OpenFgaHttpAuthorizer.forOntology(endpoint, storeId, modelId, schema);
var application = new ApplicationService(storage, new AuthorizationService(relationshipAuthorizer),
        actionExecutor, schema, manifests, fieldPolicies, AuthorizationMode.ONTOLOGY_TARGETS);
```

元组写入方使用`OpenFgaResourceIds.user(principal)`与`OpenFgaResourceIds.resource(tenant, key, OpenFgaModelContract.typeNames(schema))`。完整语义和旧编码/旧回执边界见ADR-0013。已有默认构造器仍为STRICT_RESOURCES，不能只换模型文件而省略权限模式及元组迁移。
