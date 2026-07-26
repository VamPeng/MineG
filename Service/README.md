# Service

私人相册后端服务相关代码与文档。

当前职责范围：

- 注册、登录和账号审核状态。
- 用户昵称和头像。
- 用户加密 key bundle 与家庭密钥 envelope 的密文元数据。
- 媒体密文上传记录、账号内去重和权限控制。
- 私人空间与家庭共享状态。
- 逻辑回收站。
- 私人原文件下载授权。
- 家庭相册只读浏览授权。
- 帮助反馈和审计记录。

已确认的服务端基础方案：

- 使用 Go 实现模块化单体后端。
- 基线使用 Go 1.26、chi、pgxpool、sqlc 和 goose。
- 使用 PostgreSQL 18 保存账号、权限、密钥封装、媒体元数据和任务状态。
- 对移动端 App 和 Web 管理端提供 HTTPS REST/JSON API。
- 原文件、缩略图和预览由客户端端到端加密，经后端授权后直接传输到私有阿里云 OSS；后端不代理完整媒体流量，也不处理媒体明文。
- ECS 内通过实例 RAM 角色和阿里云 Go SDK 获取临时凭据，不保存长期 AccessKey。
- MVP 不使用 Redis、消息队列、微服务或 Kubernetes。

详细模块顺序、数据模型、API 和加密上传流程见 [技术需求](../Requirement/technical-requirements.md)。

## 阶段 00 基座

阶段 00 已建立 Go 1.26.5 模块、PostgreSQL migration、OpenAPI 3.1、RFC 9457 错误、健康检查、公共平台探针、观测和 OSS/STS 受限假实现。开发与部署说明见 [`docs/development.md`](./docs/development.md) 和 [`docs/deployment-config.md`](./docs/deployment-config.md)。本阶段没有账号或媒体业务表。
