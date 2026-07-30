# MineG MVP 分阶段执行计划

## 1. 计划用途

本目录把[实施技术基线](../technical-requirements.md)中的 B0～B7、A1～A3、M0～M8 重组为 0～9 十个跨端阶段。每个阶段必须交付可运行的纵向闭环，不允许一个系统一次性超前实现全部接口后再等待其他系统。

每个阶段文件夹固定包含三份计划：

- `backend.md`：`Service/` 中的 Go 服务、PostgreSQL、OSS/STS、必要受限 CLI；涉及基础设施时明确与 `Deployment/` 的交接物。
- `frontend.md`：`Frontend/` 中的 Vue 管理端。A3 后保持范围冻结，后续阶段只做契约回归、安全和发布保障。
- `mobile.md`：`Mobile/` 中的 C++ 共享核心与 Android、iOS、HarmonyOS 原生实现。

## 2. Plans

| 阶段 | 三份执行计划 | 技术映射 | 功能范围 | 核心交付 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| 00 工程、契约与技术基座 | [后端](./00-foundation-contract/backend.md) / [前端](./00-foundation-contract/frontend.md) / [移动端](./00-foundation-contract/mobile.md) | B0 / 前端初始化 / M0 | 公共基础，无业务功能 | 可部署空服务、可登录壳、Android+C++ 纵向探针 | 无 |
| 01 账号、审核与准入 | [后端](./01-account-review/backend.md) / [前端](./01-account-review/frontend.md) / [移动端](./01-account-review/mobile.md) | B1 / A1～A3 / M1 | F-01、F-02，F-03 基础展示 | 注册、审核动作、待密钥准入闭环 | 00 |
| 02 密钥、资料、权限与本地相册 | [后端](./02-keys-profile-local-media/backend.md) / [前端](./02-keys-profile-local-media/frontend.md) / [移动端](./02-keys-profile-local-media/mobile.md) | B2 / A3 冻结 / M2 | F-03、F-04、F-05、F-06 | key grant、资料、授权与本地索引 | 01 |
| 03 单媒体加密备份 | [后端](./03-single-media-backup/backend.md) / [前端](./03-single-media-backup/frontend.md) / [移动端](./03-single-media-backup/mobile.md) | B3 / 管理端回归 / M3 | F-07、F-08 单媒体子集 | 单照片及代表性媒体上传闭环 | 02 |
| 03D Android 数据层主权迁移 | 后端 B1～B3 回归 / 管理端回归 / [Android/C++ 迁移](../../Mobile/docs/android-data-layer-migration.md) | M3-D | 不新增功能 | Core 数据主权、PlatformEffect 与平台扫描门禁 | 03 |
| 04 完整队列与自动备份 | [后端](./04-backup-queue/backend.md) / [前端](./04-backup-queue/frontend.md) / [移动端](./04-backup-queue/mobile.md) | B3+B4 / 管理端回归 / M4 | F-08 完整队列 | 历史/增量扫描、断点续传、备份状态 | 03D |
| 05 私人媒体闭环 | [后端](./05-private-media/backend.md) / [前端](./05-private-media/frontend.md) / [移动端](./05-private-media/mobile.md) | B4+B6 删除子集 / 管理端回归 / M5 | F-09、F-10、F-13 删除子集 | 私人空间、原文件保存和逻辑删除 | 04 |
| 06 家庭、回收站与反馈 | [后端](./06-family-trash-feedback/backend.md) / [前端](./06-family-trash-feedback/frontend.md) / [移动端](./06-family-trash-feedback/mobile.md) | B5+B6+B7 功能子集 / 管理端回归 / M6 | F-11、F-12、F-13、F-14 | 分享、家庭只读、恢复、帮助反馈 | 05 |
| 07 iOS 契约一致实现 | [后端](./07-ios-parity/backend.md) / [前端](./07-ios-parity/frontend.md) / [移动端](./07-ios-parity/mobile.md) | B7 观测 / 管理端回归 / M7 | F-01～F-14 iOS | iOS 全功能一致 | 06 |
| 08 HarmonyOS 契约一致实现 | [后端](./08-harmonyos-parity/backend.md) / [前端](./08-harmonyos-parity/frontend.md) / [移动端](./08-harmonyos-parity/mobile.md) | B7 观测 / 管理端回归 / M8 | F-01～F-14 HarmonyOS | 三端全功能一致 | 07 |
| 09 发布候选与故障演练 | [后端](./09-release-hardening/backend.md) / [前端](./09-release-hardening/frontend.md) / [移动端](./09-release-hardening/mobile.md) | 全系统加固 | F-01～F-14 回归 | RC、恢复演练、安全与性能报告 | 08 |

阶段 5 把 B6 中“逻辑删除”的最小事务提前，与 M5 的删除确认形成真实纵向闭环；阶段 6 再完成回收站列表、恢复、清理 CLI 和审计。这是对技术基线阶段表的依赖细化，不改变功能范围。

2026-07-30 增加 M3-D 数据层主权门禁：阶段 01～03 的历史验收不回写，但阶段 04 开始前必须完成[Android 已实现数据层迁移](../../Mobile/docs/android-data-layer-migration.md)。该门禁不新增产品功能，只把已经位于 Kotlin 的业务数据处理迁回 C++ Core，并建立 PlatformEffect 与静态扫描约束。

## 3. 执行规则

1. 严格按阶段推进；前一阶段完成门槛未通过时，下一阶段只能做不依赖它的准备工作。
2. 每阶段开始前确认 OpenAPI、数据库迁移、C ABI、CoreClient、PlatformPort、错误码和 UI 语义 ID 的变更范围。
3. Android 功能编码前登记公共契约，通过阶段验收后将对应契约状态改为 `FROZEN`；iOS、HarmonyOS 只实现冻结契约。
4. 集成测试使用真实 PostgreSQL、真实鉴权和密文对象链路；OSS 可在日常 CI 使用协议一致的假实现，但阶段退出必须在隔离 OSS 环境验证。
5. 不把 Stitch HTML、静态假数据、模拟成功或仅内存任务状态当成交付结果。
6. 管理端完成 A3 后不扩展为媒体、成员、反馈或永久清理后台。
7. 每阶段独立提交数据库 migration 和 OpenAPI 变更；共享环境已执行的 migration 不回写。
8. 移动业务数据必须由 C++ Core 唯一拥有；平台计划只交付 UI、Bridge 和 PlatformPort。阶段验收不得以 Android 专属业务 Client、DTO、领域缓存或 ViewModel 状态机替代共享实现。

## 4. 统一完成定义

每个阶段只有同时满足以下条件才能关闭：

- 三份计划中的完成门槛全部通过，跨端接口和状态没有未登记差异。
- 正常、空、等待、错误、重试、权限失效和重复请求路径由真实数据驱动并有测试。
- 日志、指标、错误响应和审计可定位问题，且不包含 Token、密钥、对象签名地址或媒体明文。
- App 重启、网络切换、进程回收和幂等重试后的状态仍正确。
- 相关需求条目、OpenAPI、迁移、契约测试和运行说明随代码同步更新。
- 生产平台代码中不存在未登记的业务 API/RPC 路径、领域响应解析、业务偏好缓存或平台端领域状态迁移；同一 Core 命令和 EffectResult 在三端得到相同结果。
- 不新增超出 MVP 的页面、权限、基础设施组件或服务端明文处理能力。

## 5. 阶段交接

每个阶段结束时由三端共同产出一份验收记录，至少包含构建版本、迁移版本、OpenAPI 版本、移动契约版本、自动化测试结果、已知限制和下一阶段输入。验收记录可在实际执行时添加为阶段文件夹内的 `acceptance.md`，本轮计划不预填虚假结果。
