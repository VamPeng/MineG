# Deployment

应用部署配置、环境说明和运维流程。

现行部署基线为公网 ECS：Go 业务服务和 PostgreSQL 部署在 ECS，移动端和管理端通过 HTTPS 访问；媒体对象保存在私有阿里云 OSS，由 ECS 完成授权和元数据校验。媒体上传与加载不做客户端应用层加密，传输安全依赖 HTTPS/TLS，Bucket 不公开。

部署入口：

- [private-album-infra](./private-album-infra/README.md)

预计包含：

- 应用服务部署配置。
- 环境变量模板。
- 数据库迁移和备份流程。
- 健康检查和回滚说明。
- 与 OSS、ECS 和域名 TLS 相关的应用部署说明。
- 高权限人工清理流程的调用说明。

敏感凭据、真实云资源标识和私钥不得写入仓库。
