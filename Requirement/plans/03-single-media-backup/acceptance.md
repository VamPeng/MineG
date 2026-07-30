# 阶段 03 验收记录：单媒体加密备份

> 2026-07-30 架构复核附注：加密格式、C++ 持久状态和既有自动化证据保持有效；上传网络步骤与下一状态仍由 Kotlin 编排，不符合技术需求 v1.2。完成[Stage03 v2 迁移](../../../Mobile/docs/android-data-layer-migration.md)是本阶段转为 `FROZEN` 的新增必要条件。

- 验收日期：2026-07-26
- 当前结论：代码实现和本地自动化验收通过，进入 `BASELINED`；隔离 OSS 与代表性 Android 真机媒体矩阵完成后才能转为 `FROZEN`。
- 范围：B3、M3、管理端上传隔离回归；不包含 M4 批量队列、私人详情、分享或回收站。

## 交付版本

| 交付物 | 版本/状态 |
| --- | --- |
| PostgreSQL migration | `00004_media_upload.sql`，schema version 4 |
| OpenAPI | `0.3.0` |
| Android | `0.3.0-m3`，versionCode 4 |
| 移动阶段 03 契约 | `stage03-v1 1.0.0`，`BASELINED` |
| C ABI | 4 |
| 移动 SQLite | user_version 4 |

## 已完成实现

### 后端

- 新增上传会话、分片、媒体、资源、相册关系和加密 Media Key envelope 数据模型；对象键只由服务端在 `media/<owner>/<session>/` 下生成。
- 实现上传创建/恢复、分片上报、服务端完成和本人媒体查询；写操作校验成员身份、幂等键、状态、资源摘要和 4 MiB 逻辑块到 multipart part 的一一映射。
- OSS 适配器逐 part 签发精确短期 PUT，不向客户端返回可复用 RAM 凭据；完成前使用 `ListParts`、ETag、长度和 `HeadObject` 密文摘要元数据核对。已完成对象可在数据库提交失败后幂等复核。
- 同账号唯一键为 `(owner_id, dedupe_fingerprint, content_revision)`；并发落败上传收敛到既有媒体，资源标记为无效。管理员 Cookie 调用媒体或上传接口返回 401。

### Android 与共享核心

- C++ 内创建 Media Key，以 User Master Key 封装；按资源域派生 Resource Key，以随机 128-bit 前缀和大端块序号组成 Nonce，并将格式、媒体、资源、类型、块序号和明文长度绑定到 AAD。
- 原资源按 4 MiB 流式分块执行 XChaCha20-Poly1305；账号私有 HMAC 指纹、逐块/整资源摘要和加密清单均不暴露明文 Media Key。
- Android 从 MediaStore 文件描述符读取原资源，尽力生成缩略图或视频封面；派生失败时只上传原资源密文。待上传密文保存在 `noBackupFilesDir` 的专用目录，服务端确认后删除。
- SQLite v4 在外部副作用前保存任务、资源、分片、服务端上传 ID 和 ETag。宿主机测试实际在首个 part 后关闭并重开核心，恢复已确认 part 后完成剩余状态迁移。

### 管理端

- 从 OpenAPI 0.3.0 重新生成 TypeScript 类型，但 API client、路由、菜单和页面仍只有阶段 01 审核能力。
- 服务端负测试确认管理员 Cookie 无法访问 `/api/v1/uploads` 和 `/api/v1/media`；生产 bundle 扫描未发现上传端点、OSS 主机、AccessKey 或 SecurityToken。

## 自动化证据

- `go test ./...` 通过。
- 在本机 PostgreSQL 18 专用临时数据库执行 `go test -count=1 -tags=integration ./tests/integration/...` 通过；migration 实际升级至 version 4，临时数据库随后删除。
- CMake 构建与 `ctest --test-dir Mobile/core/build --output-on-failure` 通过；固定向量、三块加解密、篡改、重排、截断、清单错配、非法状态迁移和进程恢复均被执行。
- Android `:app:testDebugUnitTest`、`:app:lintDebug` 与 `:app:assembleDebug` 通过。
- Frontend OpenAPI 生成、ESLint、12 项 Vitest、TypeScript 检查和生产构建通过；单 chunk 体积警告沿用既有管理端限制。

## 安全边界

- 数据库、HTTP、日志字段和 OSS 元数据只含密文、加密 envelope、账号私有指纹及尺寸/摘要；不新增明文媒体、密码、User Master Key、Family Sharing Key 或 Media Key 字段。
- API 进程不代理媒体正文；客户端授权没有读、删除、列桶或跨前缀能力。在线 ECS 角色仍不得拥有永久删除、列桶或 Bucket 管理权限。
- 管理员面与成员 Bearer 认证相互隔离；管理端构建不携带移动上传调用面。

## 转为 `FROZEN` 的剩余门槛

1. 在隔离私有 OSS 与目标 ECS RAM Role 上完成真实照片密文直传、服务端确认和本人查询，并验证越权对象键、过期授权、对象缺失、摘要不符及完成响应丢失。
2. 在 Android 真机覆盖照片、视频、GIF 与设备可提供的 Live/动态代表样本；验证断网、杀进程、分片重试和重复扫描不产生重复媒体。
3. 核对数据库、应用/API 日志、追踪和 OSS 对象元数据无明文资源、明文 Media Key 或可复用凭据。以上证据通过后，将 `contracts/stage03-v1.json` 与一致性文档的 M3 状态转为 `FROZEN`。

## 阶段 04 输入

- 复用已冻结的单媒体资源图、加密格式、上传 DTO、服务端状态查询和幂等键规则，不在批量队列中另造第二套上传状态机。
- 批量调度只负责选取任务、并发/网络/电量策略和重试退避；单任务完成仍以服务端 `COMPLETED` 为唯一真相。
