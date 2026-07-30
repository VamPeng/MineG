# Deployment

应用部署配置、环境说明和运维流程。

新的目标架构正在重设计：完整业务服务、PostgreSQL 和照片对象存储迁到家庭 Linux 节点；ECS 只保留 WebRTC 协调与 STUN，不中继业务流量。方案与实施边界见[家庭节点私人云架构 vNext](../Requirement/private-cloud-architecture-vnext.md)。

家庭笔记本开始获取项目和执行最小验证时，使用[家庭 Linux 节点 V0 最小验证部署手册](./private-album-infra/docs/04-home-node-minimum-validation.md)。该手册只验证当前 REST 服务和家庭局域网路径；WebRTC 与本地对象存储需要后续 V1 实现。

以下现有 ECS + 阿里云 OSS 基础设施资料用于历史验证和迁移参考，在 vNext 草案冻结前不得继续视为最终部署目标：

- [private-album-infra](./private-album-infra/README.md)

预计包含：

- 应用服务部署配置。
- 环境变量模板。
- 数据库迁移和备份流程。
- 健康检查和回滚说明。
- 与 OSS、ECS 和域名 TLS 相关的应用部署说明。
- 高权限人工清理流程的调用说明。

敏感凭据、真实云资源标识和私钥不得写入仓库。
