# Mobile

移动端 App 相关代码与文档。

当前职责范围：

- 扫描设备相册中的历史媒体和新增媒体。
- 自动备份尚未成功备份的媒体。
- 浏览私人空间和家庭相册。
- 下载本人私人空间中的原文件。
- 共享单张媒体。
- 使用逻辑回收站删除和恢复媒体。

客户端目标平台：

- Android 10（API 29）及以上。
- iOS 13 及以上。
- HarmonyOS 6.0 及以上。

客户端技术方案：

- 三端分别使用原生 UI，不使用 Flutter。
- Android 使用 Kotlin 和 Jetpack Compose，并优先完成第一套完整参考实现。
- iOS 使用 Swift；SwiftUI 与 UIKit 的具体边界在 iOS 实施前确认。
- HarmonyOS 使用 ArkTS 和 ArkUI。
- 三端复用 C++17 统一数据核心和 SQLite 数据库规则。
- 平台相册、安全存储、后台任务、网络传输和生命周期通过公共平台契约分别适配。
- 在扩大 Android 业务实现前，先完成共享数据核心在 Android、iOS 和 HarmonyOS 的最小接入验证。
