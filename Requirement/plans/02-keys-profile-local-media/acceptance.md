# 阶段 02 验收记录：密钥、资料、权限与本地相册

- 验收日期：2026-07-26
- 结论：已完成并关闭。开发与自动化证据如下；隔离 OSS 头像闭环和 Android 权限矩阵/真实相册回归已由项目执行方确认通过。
- 范围：B2、A3 范围冻结、M2；未进入阶段 03 的媒体加密上传。

## 交付版本

| 交付物 | 版本/状态 |
| --- | --- |
| PostgreSQL migration | `00003_keys_profile.sql`，schema version 3 |
| OpenAPI | `0.2.0` |
| Android | `0.2.0-m2`，versionCode 3 |
| 移动阶段 02 契约 | `stage02-v1 1.0.0`，`FROZEN` |
| C ABI | 3 |
| 移动 SQLite | user_version 3 |

## 验收结果

### 后端

- `go test ./...` 与 OpenAPI 契约测试通过；在本机 PostgreSQL 18 一次性数据库运行 `go test -count=1 -tags=integration ./tests/integration/...` 通过，测试库随后删除。
- 集成测试覆盖首成员 bootstrap、后续成员离线等待、自授权拒绝、错误公钥、重复提交和两请求并发完成；并验证只有 envelope 与任务同时就绪才把账号迁移为 `APPROVED`。
- 资料测试覆盖昵称校验、版本递增、头像授权幂等、对象未就绪拒绝和摘要/大小/类型复核。管理员 Cookie 调用移动接口、移动 Bearer 调用管理员接口均返回 401。
- key grant 记录可见积压量、完成结果、服务端耗时和审核到就绪时延；成功/失败审计都不写 envelope。
- 阿里云 OSS v2 SDK 适配器使用 ECS RAM Role/IMDSv2，上传签名固定 PUT、单一对象键、Content-Length、Content-Type、摘要元数据和禁止覆盖；读取为短期 GET。单元测试使用临时静态测试凭据只验证本地签名结构，不访问公网 OSS。

### Android 与共享核心

- `bash Mobile/scripts/test-core.sh` 通过；C ABI 3 覆盖错误密码、设备包装恢复、家庭 sealed-box、密钥清零、SQLite v3 设置和可恢复扫描。
- 共享核心实际写入 100,000 条媒体索引并以 500 条分页读取；同时覆盖相册改名、媒体编辑、删除对账和多相册关系。完整核心测试约 11 秒通过。
- `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 通过。
- OnePlus 8T（Android 14）常规 instrumentation 通过；随后连接一次性 PostgreSQL/API 的真实账号闭环共 2 项通过、0 跳过、0 失败。
- 真机闭环实际完成注册与密钥包上传 → 管理员审核 → 首成员家庭密钥 bootstrap → `APPROVED` 资料页 → 退出并协议登录 → 相册权限说明页。临时 API、adb reverse 和数据库均已清理。
- 相册权限不是 `FULL` 时会取消调度并把扫描置为 `BLOCKED_PERMISSION`；完整权限下 MediaStore 以稳定 ID、修改版本和每批最多 500 条扫描，进程中断后复用 generation/cursor。

### 管理端

- 从 OpenAPI 0.2.0 重新生成 TypeScript 类型；ESLint、Vitest、类型检查和生产构建通过。
- Vitest 共 5 个测试文件、12 个测试通过；新增 API client 与路由负向测试，管理端没有资料、头像、密钥、媒体或成员入口。
- 生产构建仍只有阶段 01 的账号审核界面；约 1.05 MB 单 chunk 警告沿用阶段 01 已知限制。

## 安全与状态边界

- 密码只在登录/注册调用范围内使用；用户私钥、User Master Key 和 Family Sharing Key 不进入 Kotlin、HTTP、日志或 SQLite。
- 服务端和普通数据库读取只能得到 key bundle/envelope 密文及元数据；envelope 固定为 X25519 sealed-box 80 字节。
- 头像与阶段 03 媒体对象使用分离前缀和授权用途；当前阶段不上传媒体原文件、缩略图或预览。
- 本地索引按用户 ID 隔离，退出会撤销后台任务、删除会话和设备 unlock blob，并立即清空 C++ 内存密钥。

## 正式关闭记录

1. 隔离私有 OSS 与目标 ECS RAM Role 的真实头像链路及最小权限边界已由项目执行方确认通过。
2. Android 14 的完整授权、部分照片、拒绝、系统设置撤销、空相册和真实相册扫描矩阵已由项目执行方确认通过。
3. `contracts/stage02-v1.json` 与一致性文档中的 M2 状态已转为 `FROZEN`；阶段 03 可消费该冻结输入。

## 阶段 03 输入

- 已有获批会话、用户主密钥解封和家庭 envelope；C++ 内存中可继续派生账号私有去重键与媒体资源密钥。
- SQLite v3 提供真实本地媒体、相册关系、内容版本、可用性、扫描状态和设备备份设置；阶段 03 应消费索引，不重复扫描全库。
- 后端头像对象授权与媒体密文授权已分离；阶段 03 新增媒体对象时不得复用 `avatars/` 前缀或资料对象权限。
