# Mobile

移动端 App 相关代码与文档。

Android 业务入口必须遵循[启动、登录、缓存与主页流程约束](./docs/authenticated-app-flow.md)，不得以 Mock 用户或媒体状态绕过账号准入。移动端领域数据必须遵循[C++ 数据主权与三端一致性契约](./three-platform-consistency-contract.md#55-领域数据主权)；当前 Android 已实现但仍位于 Kotlin 的数据处理按[迁移技术文档](./docs/android-data-layer-migration.md)整改。

> 当前媒体基线（2026-07-30）：媒体上传、预览和原文件加载不执行客户端应用层加密；通过公网 ECS 获取私有 OSS 的短期对象授权，并使用 HTTPS/TLS 与长度/SHA-256 校验。已有 Stage03 v1 加密实现和字段只作为迁移兼容，不得接入新上传主链。

当前职责范围：

- 扫描设备相册中的历史媒体和新增媒体。
- 通过安全传输自动备份尚未成功备份的媒体，并验证对象完整性。
- 浏览私人空间和家庭相册。
- 下载本人私人空间中的原文件。
- 共享和取消共享单张媒体。
- 使用逻辑回收站删除和恢复媒体。
- 修改本人昵称和头像，使用帮助与反馈。

客户端目标平台：

- Android 10（API 29）及以上。
- iOS 13 及以上。
- HarmonyOS 6.0 及以上。

客户端技术方案：

- 三端分别使用原生 UI，不使用 Flutter。
- Android 使用 Kotlin 和 Jetpack Compose，并优先完成第一套完整参考实现。
- iOS 使用 SwiftUI 为主，UIKit、PhotoKit 和 AVFoundation 包装补齐 iOS 13 能力。
- HarmonyOS 使用 ArkTS 和 ArkUI。
- 三端复用 C++17 统一数据核心、SQLite 和任务状态机；libsodium 仅保留旧数据兼容用途。
- 服务端数据、业务缓存、跨页面/跨进程领域状态、API/RPC DTO 与副作用编排由 C++ Core 唯一拥有；三端 ViewModel 不建立业务真实来源。
- 平台相册、安全存储、后台任务、网络传输和生命周期通过公共平台契约分别适配。
- [三端一致性契约](./three-platform-consistency-contract.md)统一关键方法、数据字段、Bridge/Port、页面语义 ID、UI 操作、状态和错误命名。
- 先实现 Android，不要求 iOS、HarmonyOS 前置建壳；Android 每开发一个功能，先登记契约，通过后冻结该功能契约。
- iOS、HarmonyOS 后续按冻结契约实现并运行同一组一致性测试，不从 Android 平台类型反推公共接口。
- 自动备份默认关闭，移动网络备份默认关闭；完整相册权限允许建立本地索引，用户主动开启后才开始备份上传。

## 阶段 01 Android 账号准入

阶段 01 已在 Android 完成登录、注册、协议确认、待审核轮询、会话恢复/轮换、退出清理和最小个人中心。现行 `account-v3` 注册只提交账号与设备字段，不创建或上传 key bundle；管理员审核通过后账号直接进入 `APPROVED`。

2026-07-30 数据主权迁移已进入实施：Foundation v2 与 account-v3 已接通生产账号主链，Session 编排、直接审核准入、资料解析和账号隔离 Profile Snapshot 已迁入 C++ Core，Android 专属资料缓存已移除；头像、私人媒体、扫描和上传仍按 [`docs/android-data-layer-migration.md`](./docs/android-data-layer-migration.md) 继续迁移，M3-D 尚未整体完成。

冻结清单位于 [`contracts/foundation-v1.json`](./contracts/foundation-v1.json) 与 [`contracts/account-v1.json`](./contracts/account-v1.json)。SQLite v2 只存非敏感账号路由状态，Token 和设备安装标识由 Android Keystore 包装。构建与真实后端验证命令见 [`docs/development.md`](./docs/development.md)。本阶段仍不创建 iOS 或 HarmonyOS 空壳。

## 阶段 02 Android 密钥、资料与本地相册

Android 历史版本实现过家庭密钥 bootstrap 与离线 key grant；这些代码和 C ABI 仅保留旧数据兼容，不再参与注册、审核、登录或媒体访问。当前阶段继续提供昵称/头像入口、六态相册权限、设备级备份设置、MediaStore 分批扫描及 SQLite 本地相册分页。

本地索引已位于 C++；资料、头像、前台扫描与备份偏好已按 `stage02-v2` 接入 Core，批次 D 已于 2026-08-02 由项目负责人确认并冻结。旧 key-grant 编排不得从兼容代码重新接回生产入口，也不得复制到后续平台。

阶段清单位于 [`contracts/stage02-v1.json`](./contracts/stage02-v1.json)。每批最多 500 条，10 万条索引与分页、编辑/删除对账、相册改名和多相册关系均由共享核心测试覆盖；中断扫描会复用持久化 generation/cursor。隔离 OSS 与 Android 权限矩阵验收完成后，当前清单已转为 `FROZEN`。

## 阶段 03 Android 单媒体备份（stage03-v2 已实现）

阶段 03 v1 曾建立媒体加密实现：C ABI 4 在 C++ 内创建和封装 Media Key，并生成加密资源与分片状态。该实现现已退出现行需求，只作为旧数据库、接口和本地任务迁移输入；新主链不得创建 Media Key、密文副本或加密清单。

`BackupSingleMedia` 已由 C++ Core 编排原资源打开、4 MiB 分片与 SHA-256、ECS 会话、OSS PUT、ETag 上报、完成和去重；Android 只执行 MediaSource/Transport Effect。本地相册点击单条媒体可触发代表性上传，并明确显示“不加密”。

Android 通过 MediaStore 文件描述符流式读取原资源，不生成加密临时文件。旧 [`contracts/stage03-v1.json`](./contracts/stage03-v1.json) 与加密向量只作兼容证据；现行契约 [`contracts/stage03-v2.json`](./contracts/stage03-v2.json) 已于 2026-08-02 随项目负责人确认的真实 ECS + 私有 OSS 真机上传验收冻结。故障演练转入阶段 09 发布加固。
