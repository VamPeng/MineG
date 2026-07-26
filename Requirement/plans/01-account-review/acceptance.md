# 阶段 01 验收记录：账号、审核与准入

- 验收日期：2026-07-26
- 结论：开发验收通过；正式关闭仍等待目标 ECS 密码基准和带可信 HTTPS 证书的管理端浏览器回归。
- 范围：B1、A1～A3、M1；未进入阶段 02 的家庭 key grant/envelope 实现。

## 交付版本

| 交付物 | 版本/状态 |
| --- | --- |
| PostgreSQL migration | `00002_account_review.sql`，schema version 2 |
| OpenAPI | `0.1.0` |
| Go 工具链 | Go `1.26.5` |
| 管理端 | `mineg-admin 0.1.0` |
| Android | `0.1.0-m1`，versionCode 2 |
| 移动账号契约 | `account-v1 1.0.0`，`FROZEN` |
| C ABI | 2 |
| 移动 SQLite | user_version 2 |

## 验收结果

### 后端

- `go test -race ./...`、`go vet ./...` 通过。
- sqlc 重新生成后无未同步产物，OpenAPI 契约测试通过。
- 在本机 PostgreSQL 18 专用临时库运行 `go test -count=1 -tags=integration ./tests/integration/...` 通过。
- 集成测试覆盖重复手机号、错误密码、待审核受限会话、Refresh Token 轮换与重放后整族撤销、退出撤销、管理员 bootstrap、CSRF、并发审核和批准后的 key grant 等待状态。
- Access Token 为 15 分钟，Refresh Token 为 30 天；数据库仅保存令牌摘要。管理员 Session 为 30 分钟闲置、8 小时绝对过期，Cookie 使用 `Secure`、`HttpOnly`、`SameSite=Strict`。

### 管理端

- OpenAPI 类型生成、ESLint、TypeScript 类型检查和生产构建通过。
- Vitest 共 5 个测试文件、10 个测试通过，覆盖 API/CSRF 轮换、会话恢复与失效、离线退出保持服务端会话一致性、待审核列表、详情和二次确认。
- 页面只展示脱敏手机号和审核所需时间，不包含媒体、密钥、家庭管理或完整敏感资料入口。
- HTTP 浏览器联调中，管理员登录接口返回 200 后 `Secure` Cookie 未被浏览器回传，后续列表按预期返回 401，证明非 HTTPS 部署不能绕过 Cookie 约束。自签名本地证书被浏览器信任校验拒绝，因此可信 HTTPS 下的完整 UI 回归保留为部署验证项。

### Android 与共享核心

- `bash Mobile/scripts/test-core.sh` 通过，覆盖 C ABI、SQLite v2 恢复、账号状态、Argon2id/XChaCha20-Poly1305 key bundle、加解密与篡改失败。
- `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 通过。
- OnePlus 8T（Android 14）真机 `connectedDebugAndroidTest` 共 2 项通过、0 项失败。
- 真机使用真实临时 PostgreSQL 和真实 API 完成：注册并上传客户端生成的公钥/加密 key bundle → 管理员 Cookie/CSRF 登录 → 审核 → key grant 未就绪仍返回待审核 → 退出 → 勾选协议后重新登录仍待审核。
- 数据库核对结果为 `PENDING:PENDING:true`：产品状态保持 `PENDING`、key grant 任务为 `PENDING`、加密 key bundle 已持久化。测试临时库和 adb reverse 均已清理。

## 安全与状态边界

- 用户私钥明文只在 C++ 受控内存中生成和加密，不进入 Kotlin、HTTP、日志或 SQLite。
- 管理端不会返回公钥、加密 key bundle 内容或任何媒体解密信息。
- 审核动作只创建 `key_grant_tasks.PENDING`；用户在 envelope 就绪前始终看到 `REVIEW_PENDING`，不能进入主导航。
- 注册和审核写操作使用请求指纹与幂等键；并发审核只允许一个请求产生状态迁移，其余返回已处理结果。

## 已知限制与关闭条件

1. 必须在目标 ECS 对服务端 Argon2id 做基准并确认 150～300 ms 目标，再锁定参数；当前 PHC 字符串已携带算法与参数，便于后续兼容校验。
2. 必须在带可信证书、与 `MINEG_ADMIN_ORIGIN` 完全一致的 HTTPS 管理端环境回归登录—列表—详情—确认—退出，验证真实浏览器 Cookie/Origin/CSRF 行为。
3. 管理端生产包目前有约 1.05 MB 的单 JavaScript chunk 构建警告，不影响功能；阶段 09 再做按路由拆包和发布性能加固。
4. key grant、家庭 envelope、资料编辑和头像属于阶段 02，不应在本阶段把审核结果直接暴露为 `APPROVED`。

## 阶段 02 输入

- `key_grant_tasks` 提供待协调任务和首成员 bootstrap 标记。
- `users`、`devices`、`user_key_bundles`、协议记录、移动会话及最小资料读取接口已可复用。
- `account-v1 1.0.0` 的 F-01～F-03 基础方法、错误码、轮询规则和语义 ID 已冻结；阶段 02 的新增契约需按三端一致性规则登记。
- 阶段 02 完成 envelope 后，后端才可将用户迁移为 `APPROVED`，移动端才可进入 `APP_HOME`。
