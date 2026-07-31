# Requirement

以当前有效 Stitch 设计为行为基线，维护产品范围、功能实现和技术方案。现行部署架构统一为公网 ECS 业务服务 + PostgreSQL + 私有阿里云 OSS；媒体上传与加载不做客户端应用层加密，传输统一使用 HTTPS/TLS。

- [MVP 产品需求基线 v2.1](./product-requirements.md)
- [MVP 功能实现基线 v1.0](./functional-requirements.md)
- [实施技术基线 v1.2](./technical-requirements.md)
- [移动端三端一致性契约 v1.1](../Mobile/three-platform-consistency-contract.md)
- [Stitch 有效设计索引](./Prototype/Stitch/README.md)
- [MVP UI 内容约束与原型清理规则](./Prototype/Stitch/UI-CONTENT-RULES.md)
- [MVP 分阶段执行计划](./plans/README.md)

冲突时，部署和媒体传输先服从[实施技术基线](./technical-requirements.md)，页面行为再服从设计索引中的有效页面、当前 HTML 交互和正式主题规范；`-dep` 页面、旧媒体密文协议和原始导出包只用于历史追溯。后续变更必须同步更新基线、阶段计划和移动契约。
