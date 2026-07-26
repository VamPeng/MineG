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

## 阶段 01 Android 账号准入

阶段 01 已在 Android 完成登录、注册、协议确认、待审核轮询、会话恢复/轮换、退出清理和最小个人中心。注册时由 C++/libsodium 创建 X25519 密钥材料，Argon2id 派生包装密钥，XChaCha20-Poly1305 加密私钥与用户主密钥；服务端只接收公钥和密文 key bundle。

冻结清单位于 [`contracts/foundation-v1.json`](./contracts/foundation-v1.json) 与 [`contracts/account-v1.json`](./contracts/account-v1.json)。SQLite v2 只存非敏感账号路由状态，Token 和设备安装标识由 Android Keystore 包装。构建与真实后端验证命令见 [`docs/development.md`](./docs/development.md)。本阶段仍不创建 iOS 或 HarmonyOS 空壳。

## 阶段 02 Android 密钥、资料与本地相册

Android 已实现首成员家庭密钥 bootstrap、现有成员离线 key grant、昵称/头像入口、六态相册权限、设备级备份设置、MediaStore 分批扫描及 SQLite 本地相册分页。C ABI 升至 3，用户私钥、User Master Key 与 Family Sharing Key 只在 C++ 受控内存中解封；SQLite v3 只保存设置、游标、相册/媒体元数据、关系和下载回执。

阶段清单位于 [`contracts/stage02-v1.json`](./contracts/stage02-v1.json)。每批最多 500 条，10 万条索引与分页、编辑/删除对账、相册改名和多相册关系均由共享核心测试覆盖；中断扫描会复用持久化 generation/cursor。隔离 OSS 与 Android 权限矩阵验收完成后，当前清单已转为 `FROZEN`。

## 阶段 03 Android 单媒体加密备份

阶段 03 已建立 F-07 与单媒体 F-08 基线：C ABI 4 在 C++ 内创建和封装 Media Key，按资源派生密钥，以 4 MiB 逻辑块执行 XChaCha20-Poly1305 独立认证加密，并生成账号私有 HMAC 去重指纹和加密资源清单。SQLite v4 在任何网络副作用前持久化任务、密文临时文件、分片摘要与 ETag；进程中止后可跳过已由服务端确认的分片继续上传。

Android 通过 MediaStore 文件描述符流式读取原资源，并尽力生成加密缩略图或视频封面；无法生成派生资源时保留原文件密文降级路径。阶段清单位于 [`contracts/stage03-v1.json`](./contracts/stage03-v1.json)，固定向量位于 [`core/test-vectors/media-encryption-v1.json`](./core/test-vectors/media-encryption-v1.json)。在隔离 OSS 与真实照片、视频、GIF、动态资源验收完成前保持 `BASELINED`。
