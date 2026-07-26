# Mobile

移动端 App 相关代码与文档。

当前职责范围：

- 扫描设备相册中的历史媒体和新增媒体。
- 在客户端端到端加密并自动备份尚未成功备份的媒体。
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
- 三端复用 C++17 统一数据核心、SQLite、libsodium 加密格式和任务状态机。
- 平台相册、安全存储、后台任务、网络传输和生命周期通过公共平台契约分别适配。
- [三端一致性契约](./three-platform-consistency-contract.md)统一关键方法、数据字段、Bridge/Port、页面语义 ID、UI 操作、状态和错误命名。
- 先实现 Android，不要求 iOS、HarmonyOS 前置建壳；Android 每开发一个功能，先登记契约，通过后冻结该功能契约。
- iOS、HarmonyOS 后续按冻结契约实现并运行同一组一致性测试，不从 Android 平台类型反推公共接口。
- 自动备份默认开启，默认仅 Wi-Fi；只有完整相册权限才开始扫描和上传。

## 阶段 00 基座

阶段 00 已建立独立的 [`MineG_Android`](./MineG_Android) Compose 工程、C++17 共享核心、固定 SQLite、libsodium 流式加密、稳定 C ABI、JNI Bridge 和首批 Android PlatformPort。`Mobile/` 只作为 Android、iOS、HarmonyOS 和公共资产的集合目录，不作为 Gradle、Xcode 或 DevEco 工程根。冻结清单位于 [`contracts/foundation-v1.json`](./contracts/foundation-v1.json)，构建与验证命令见 [`docs/development.md`](./docs/development.md)。本阶段未创建 iOS 或 HarmonyOS 空壳。
