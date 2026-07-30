# 阶段 00 验收记录

> 2026-07-30 架构复核附注：本文保留阶段 00 当时的验收事实。后续数据主权审查确认 v1 基座尚未提供 Core `PlatformEffect`/`EffectResult` 回路，因此本记录不能作为“平台业务数据层允许自行实现”的依据；兼容基座迁移见 [`Mobile/docs/android-data-layer-migration.md`](../../../Mobile/docs/android-data-layer-migration.md)。

## 结论

阶段 00 的后端、管理端与 Android/C++ 基座已完成，跨端基础契约从 `BASELINED` 转为 `FROZEN`。本阶段未实现账号、管理员真实登录、媒体上传或 iOS/HarmonyOS 空壳。

## 版本

| 项目 | 版本 |
| --- | --- |
| 验收日期 | 2026-07-26 |
| Go 工具链 | 1.26.5 |
| PostgreSQL | 18.4；migration `00001_platform_baseline` |
| OpenAPI | 3.1.0；文档版本 `0.0.1` |
| 管理端 | `mineg-admin` 0.0.1 |
| 移动契约 | `foundation-v1` 1.0.0 `FROZEN` |
| Android | 独立工程 `Mobile/MineG_Android`；`0.0.1-m0`；min API 29 / target API 36 |
| SQLite / libsodium | SQLite 3.51.3 / libsodium Android 5.2.0 |
| 真机验收用局域网 Debug APK SHA-256 | `bb0f1bc4ab6cd401ec437e2044e2dc2f040e97f4efc8b5c899bb7921a8c6825b` |

## 自动化与运行验证

| 范围 | 命令或场景 | 结果 |
| --- | --- | --- |
| Go | `go test -race ./...`、`go vet ./...` | 通过 |
| API 契约 | OpenAPI 载入校验；request ID、RFC 9457 Content-Type/结构、未知路由 | 通过 |
| PostgreSQL | 临时 PostgreSQL 18.4：空库、重复 up、上一快照 down/up、失败事务回滚 | 通过 |
| API 运行 | migration → readiness → platform probe → 404 problem → SIGTERM 排空 → 日志扫描 | 通过 |
| OSS/STS 假实现 | 拒绝非密文对象；上传授权无 DeleteObject/DeleteObjectVersion | 通过 |
| 管理端 | OpenAPI 类型生成、ESLint、5 个 Vitest、vue-tsc、Vite 生产构建 | 通过 |
| 前端依赖 | `npm audit --omit=dev` | 0 个生产依赖漏洞 |
| C++ 核心 | CMake/CTest：50 次句柄重建、SQLite 重开、命令/查询/事件/取消 | 通过 |
| 加密格式 | 1 MiB+ 分块 XChaCha20-Poly1305、正确解密、篡改拒绝且无部分明文 | 通过 |
| Android JVM | 冻结清单、CoreClient/Port 名称、公共接口无 Android/Compose 类型 | 通过 |
| Android 构建 | arm64-v8a + x86_64 JNI、`lintDebug`、`assembleDebug` | 通过 |
| Android 设备 | API 35 模拟器与 Android 14 OnePlus 真机：25 次 CoreClient/JNI 创建、订阅、执行、释放和重开恢复 | 通过 |
| Android UI 探针 | Android 14 OnePlus 真机：同局域网 Debug API、完整媒体权限、SQLite/C ABI、安全存储、媒体句柄与流式加密 | 通过（`SUCCESS`） |

## 交付物

- 后端：`Service/Dockerfile`、`Service/api/openapi.yaml`、`Service/migrations/`、`Service/cmd/api`、`Service/cmd/migrate`。
- 管理端：可构建 SPA、登录/受保护布局、生成的 API 类型和统一状态组件。
- 移动端：Android debug APK、`libmineg_core`、`mineg_core.h`、SQLite v1 migration、冻结契约和加密测试向量。
- CI：`.github/workflows/stage-00.yml` 对三端执行格式、静态检查、测试、migration、契约和构建门禁。
- Deployment 交接：`Deployment/stage-00-runtime.md`。

## 已知限制与下一阶段输入

- 当前工作机没有 Docker 命令，因此本地以真实 Go 二进制完成运行验收；Docker 镜像构建由 CI 门禁执行。
- OpenAPI breaking-change 脚本已接入；主分支首次纳入 OpenAPI 前没有可比较的历史基线，首次合入后开始阻断破坏性变更。
- Android Debug 完整 UI 探针已在同一局域网内通过 RFC1918 HTTP API 真机验证；Release 仍强制有效 HTTPS，正式 HTTPS 环境留待部署阶段验证。
- 阶段 01 可以在冻结的 request ID、Problem、CoreClient、C ABI、PlatformPort 和语义 ID 规则上实现账号审核闭环。
