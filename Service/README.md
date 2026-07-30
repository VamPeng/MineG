# Service

私人相册后端服务相关代码与文档。

> 当前部署与媒体基线：Go 业务服务和 PostgreSQL 运行在公网 ECS，App 与管理端通过 HTTPS REST/JSON 访问，媒体对象保存在私有阿里云 OSS。上传与加载不做客户端应用层媒体加密；旧密文上传接口只保留为迁移兼容，不得继续作为新客户端契约。

当前职责范围：

- 注册、登录和账号审核状态。
- 用户昵称和头像。
- 账号准入状态；旧 key bundle 与家庭 envelope 只作为兼容迁移数据。
- 媒体上传记录、账号内去重、对象完整性和权限控制。
- 私人空间与家庭共享状态。
- 逻辑回收站。
- 私人原文件下载授权。
- 家庭相册只读浏览授权。
- 帮助反馈和审计记录。

已确认的服务端基础方案：

- 使用 Go 实现模块化单体后端。
- 基线使用 Go 1.26、chi、pgxpool、sqlc 和 goose。
- 使用 PostgreSQL 18 保存账号、权限、媒体元数据和任务状态；旧密钥封装字段不再用于新媒体流程。
- 对移动端 App 和 Web 管理端提供 HTTPS REST/JSON API。
- 原文件、缩略图和预览不做客户端应用层加密，经后端授权后通过 HTTPS/TLS 传输到私有阿里云 OSS；后端不代理完整媒体流量，但负责授权、元数据与完整性校验。
- ECS 内通过实例 RAM 角色和阿里云 Go SDK 获取临时凭据，不保存长期 AccessKey。
- MVP 不使用 Redis、消息队列、微服务或 Kubernetes。

详细模块顺序、数据模型、API 和上传流程见 [技术需求](../Requirement/technical-requirements.md)。

## 阶段 01 账号与审核

阶段 01 已在阶段 00 基座上实现手机号注册、Argon2id 密码、移动端 Access/Refresh Token 轮换与重放撤销、管理员 bootstrap、服务端 Cookie 会话、CSRF/Origin 校验、待审核游标列表、幂等审核与结构化审计。migration `00005_approval_without_key_grant.sql` 已将准入切换为管理员通过后直接 `APPROVED`；新注册不要求或创建 key bundle，审核不创建 key-grant 任务。

OpenAPI 位于 [`api/openapi.yaml`](./api/openapi.yaml)，开发、bootstrap 与部署约束见 [`docs/development.md`](./docs/development.md) 和 [`docs/deployment-config.md`](./docs/deployment-config.md)。本阶段不处理媒体正文、家庭密钥明文或个人资料修改。

## 阶段 02 旧密钥协议与个人资料

阶段 02 已实现固定家庭、80 字节 X25519 sealed-box envelope、key grant、本人资料更新和头像元数据接口。其中 envelope/key grant 属于旧媒体加密协议，只保留兼容迁移；当前准入、媒体上传和家庭共享不得依赖这些字段。审计与指标不记录 envelope、Token 或对象签名地址。

头像使用独立 `avatars/` 前缀和精确对象键、长度、类型、摘要的短期签名 PUT；服务通过 ECS RAM Role 的 IMDSv2 临时凭据访问私有 OSS，以内网 `HeadObject` 复核后才更新资料，并为读取签发短期 GET。服务不持有长期 AccessKey，也不代理对象正文。部署参数见 [`docs/deployment-config.md`](./docs/deployment-config.md)。

## 阶段 03 单媒体上传（stage03-v2 已实现）

schema version 6 与 OpenAPI 0.5.0 已接入 `stage03-v2`：`POST/GET /api/v1/uploads`、分片上报、完成以及本人媒体列表均要求获批成员 Bearer；新请求使用 `MEDIA_ORIGINAL`、`content_size/content_sha256` 和 `.original` 对象键，不接收 Media Key、加密清单或密文副本。旧密文字段与 `MEDIA_CIPHERTEXT` 仅保留兼容。

完成前服务通过 OSS `ListParts`、ETag、长度和 `HeadObject` 的 `mineg-content-sha256` 元数据复核；随后在一个数据库事务中创建媒体、资源和本人默认相册关系。新契约不写 `media_key_envelopes`；管理员 Cookie 无法访问上传、媒体或对象授权响应。真实 ECS + 私有 OSS 仍需按验收清单执行。
