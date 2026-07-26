# 阶段 07 移动端执行计划：iOS 完整一致实现

## 目标与范围

对应 M7。iOS 先通过 M0 基座测试，再按 M1～M6 顺序实现已冻结 F-01～F-14 契约，不从 Android SDK 类型反推公共接口。

## 实施任务

- 建立 Swift/SwiftUI 工程、C/Objective-C++ Bridge 和全部 PlatformPort；验证 C ABI 所有权、线程、取消与回调生命周期。
- 使用 Keychain 保存 Token/设备包装密钥，PhotoKit 处理授权、相册、媒体、多资源与 iCloud 下载。
- 使用 background `URLSessionUploadTask` 和固定 session identifier 直传密文；BGTaskScheduler 只争取执行机会。
- SwiftUI 实现冻结页面/元素语义与操作；iOS 13 不足的导航、视频、Live Photo 和生命周期用 UIKit/AVFoundation 包装。
- 依序完成账号准入、资料、权限、本地相册、加密备份、私人、家庭、回收站、帮助反馈。
- 使用 PhotoKit change request 写回原文件，系统确认后生成下载回执；保持多资源组成和原质量。
- 运行与 Android 相同的加密向量、状态机、API、UI 语义和端到端测试。

## 平台专项验证

- 部分照片权限统一按未完整授权处理；回前台正确复查，不创建不可执行任务。
- iCloud 资源未本地化、后台上传重连、系统杀进程、低存储、Live Photo 配对和 iOS 13 降级。
- 平台无等价能力时只使用需求规定占位/降级，不静默丢资源或降低质量。

## 完成门槛

- iOS 通过 F-01～F-14 和产品 MVP 验收，业务结果与 Android 一致。
- 全部公共契约测试通过；平台差异均记录且不改变公共语义。
- Android 全量回归继续通过，证明兼容修复未破坏参考实现。
