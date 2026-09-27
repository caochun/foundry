# ADR-0014：Pack组合、资产加载与种子初始化

状态：Accepted。日期：2026-09-28。

## 组合契约

新增loadBundle，把输入Pack作为一个部署单元：先读取namespace/version、声明与引用，再校验依赖图，最后统一解析接口继承和类型引用并编译。类型所有者可追溯到原namespace。单Pack load和旧loadAll保留独立编译语义；包含跨Pack引用时应使用loadBundle，不能把loadAll返回值当成已组合模型。

依赖按namespace定位，支持SemVer精确版本与>=比较，正确区分预发布与正式版；不支持的范围语法明确拒绝。缺失、循环、重复Pack身份与未声明的跨Pack类型/资产引用均拒绝。已提供的openfoundry.core全局可见，但本阶段不自动查找或嵌入Core Pack。

按依赖和namespace生成确定性顺序，不依赖调用方列表顺序。对象、关系、动作、枚举、接口的同名声明冲突失败，不靠加载顺序静默覆盖。完全一致的平台标量声明可以共享；仍未实现的计算字段、结构值类型、自定义标量执行等不能算作完成。

组合schema使用openfoundry.bundle命名空间及带内容摘要的版本，保留原manifest列表和typeOwners。内容摘要包括标准加载文件；x-*和provides/description元数据保留，但自定义扩展指向的文件由上层消费者负责，不伪称平台已执行。

## 资产

- permissions中的.fga原文、来源namespace和路径被保留，尚不自动转换、合并或发布模型。
- 按约定读取permissions/field-permissions.yaml，严格校验已存储字段，转换为运行时FieldPolicy。
- 对照上游authorization-service.ts，fieldsByRelation在运行时按用户的角色名精确匹配。加载到的策略仅控制存储字段；虚拟关系字段继续走自身可见性及端点授权，不因未出现在字段文件中而被隐藏。原程序式FieldPolicy构造器行为保持兼容。
- seed和connectors文件被严格读取为不可变声明；capabilities保留。加载动作不启动连接器、不访问远端、不执行种子或推送权限。
- 列出的文件缺失、YAML重复键、路径越界及指向Pack外的符号链接均失败；标准文件只读取一次用于解析及摘要。YAML日期保持字符串，交给本体标量规则解释。

## 显式种子初始化

宿主先对目标Provider应用bundle.ontology.schema，再调用PackSeeder.apply并指定tenant及稳定bootstrap actor。种子、历史、回执、审计与outbox在一个事务内提交；先创建全部待处理对象，再建立关系，支持跨种子文件前向引用。

引用按namespace限定；本地ref、namespace:ref及符合目标类型的已有实体ID可用于关系端点。未提供主键时生成基于namespace/type/ref或文件位置的确定性ID。初始化结果返回限定引用到EntityKey的映射。

每个种子文件拥有稳定回执，原定义重放不修改任何业务数据。新增文件可引用已初始化文件；已提交文件内容改变会要求显式迁移，不把业务状态重置为新种子值。已有实体冲突不会被当作成功初始化。回执与引用跨JDBC Provider重建保留；bootstrap actor改变不会绕过已有回执所有权。

种子事件/审计ID包含tenant，避免同一Pack在不同租户初始化时发生全局ID冲突。失败或并发冲突不会留下部分种子；内存冲突有界重试。

## 应用装配与验证范围

ApplicationService.fromBundle使用组合schema、actions和加载的字段策略。原Library验证现在通过loadBundle和PackSeeder装配，不再由测试手工创建其目录记录。真实OpenFGA集成同时验证模型、字段角色隐藏与虚拟关系读取；JSON模型仍由官方工具预先生成，并与加载到的DSL核对SHA，不能算作Java模型编译部署能力。

旧数据Schema迁移、FGA生成/合并/发布及元组管理、连接器运行/同步检查点、完整ODL、动态插件激活、生产运维等仍按总计划继续。本次只在隔离测试中初始化种子，未改动运行中的Mirror数据库。
