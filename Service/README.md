# Service

私人相册后端服务相关代码与文档。

> vNext 方向：服务目标运行位置将从 ECS 迁到家庭 Linux 节点，App 通过 ECS 完成 WebRTC 信令后使用 DataChannel 与本服务直连；照片改由家庭节点本地 `ObjectStore` 接收和发送。具体边界见[家庭节点私人云架构 vNext](../Requirement/private-cloud-architecture-vnext.md)。当前 REST + 阿里云 OSS 实现继续保留，待直连原型和迁移方案验证后再分阶段替换。

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

## 阶段 01 账号与审核

阶段 01 已在阶段 00 基座上实现手机号注册、Argon2id 密码、移动端 Access/Refresh Token 轮换与重放撤销、管理员 bootstrap、服务端 Cookie 会话、CSRF/Origin 校验、待审核游标列表、幂等审核与结构化审计。管理员通过只创建 key grant 待办；家庭 envelope 就绪前账号对外仍为 `PENDING`。

OpenAPI 位于 [`api/openapi.yaml`](./api/openapi.yaml)，开发、bootstrap 与部署约束见 [`docs/development.md`](./docs/development.md) 和 [`docs/deployment-config.md`](./docs/deployment-config.md)。本阶段不处理媒体明文、家庭密钥明文或个人资料修改。

## 阶段 02 密钥授权与个人资料

阶段 02 新增固定家庭、80 字节 X25519 sealed-box envelope、并发安全且幂等的 key grant、本人资料更新和头像元数据接口。审核只产生待办；首成员或已持有家庭密钥的成员提交合法 envelope 后，服务才在同一事务中把目标账号置为 `APPROVED`。审计与指标不记录 envelope、Token 或对象签名地址。

头像使用独立 `avatars/` 前缀和精确对象键、长度、类型、摘要的短期签名 PUT；服务通过 ECS RAM Role 的 IMDSv2 临时凭据访问私有 OSS，以内网 `HeadObject` 复核后才更新资料，并为读取签发短期 GET。服务不持有长期 AccessKey，也不代理对象正文。部署参数见 [`docs/deployment-config.md`](./docs/deployment-config.md)。

## 阶段 03 单媒体密文上传

阶段 03 新增 schema version 4 的相册、媒体、密文资源、媒体密钥 envelope、上传会话和分片记录。`POST/GET /api/v1/uploads`、分片上报、完成以及本人媒体列表均要求获批成员 Bearer；服务端生成 `media/<owner>/<session>/` 对象键，并为每个 multipart part 签发精确、短期、不可复用到其他对象的 PUT 授权。

完成前服务通过 OSS `ListParts`、ETag、长度和 `HeadObject` 密文摘要元数据复核；随后在一个数据库事务中创建媒体、资源、本人默认相册关系和加密 Media Key envelope。同账号去重只比较账号私有 HMAC 指纹，唯一键为 `(owner_id, dedupe_fingerprint, content_revision)`；管理员 Cookie 无法访问上传、媒体或对象授权响应。
