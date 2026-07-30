# 阶段 00 移动端执行计划：一致性契约与 Android 基座

> 2026-07-30 数据主权修订：本阶段历史交付与验收记录保持不变；现有 v1 基座缺少 Core `PlatformEffect`/`EffectResult` 回路，后续按 [`Mobile/docs/android-data-layer-migration.md`](../../../Mobile/docs/android-data-layer-migration.md) 增补兼容契约版本，不把 Android 业务 Client 视为基座能力。

## 目标与范围

对应 M0。仅要求 Android 可运行，打通 Android—稳定 C ABI—C++17 核心—SQLite/libsodium—平台端口的最小纵向链路，并冻结公共基座契约。

## 实施任务

- 建立 CMake C++17 共享核心、SQLite migration、libsodium 初始化、领域结果/错误和资源生命周期规范。
- 定义稳定 C ABI，覆盖命令、查询、事件订阅、取消和释放；所有权、线程和回调规则写入契约测试。
- Android 初始化 Kotlin/Compose 工程和 JNI Bridge；公共接口不得暴露 URI、Compose 或 Android SDK 类型。
- 建立 `SecureStorePort`、`TransportPort`、`MediaSourcePort`、网络状态与后台调度的最小 Android 适配器。
- 完成 SQLite 事务读写、一次 HTTPS JSON、读取一条媒体句柄并流式加密一个资源的纵向探针。
- 建立公共名称、字段、错误码、页面/元素语义 ID 清单及 Android 一致性测试。
- 验证密钥和敏感缓冲区不写 SQLite/日志，并在释放路径清零受控内存。

## 交付物

- Android 调试包、C++ 核心库、C ABI 头文件、SQLite v1 migration 和契约测试报告。
- 更新后的三端一致性契约；基础接口状态由 `BASELINED` 转为 `FROZEN`。
- iOS 与 HarmonyOS 后续可直接消费的公共测试向量和桥接样例，不创建两端空壳。

## 验证与完成门槛

- Android 进程重建后能重新打开 SQLite，JNI 重复创建/释放无崩溃和悬空回调。
- 代表性资源以流式方式完成加密，篡改密文验证失败且不输出部分明文。
- 网络和媒体探针通过端口进入核心，核心不持有平台对象。
- C++、JNI 和 Android 基座测试全部通过后才允许进入账号功能。

## 不在本阶段

正式登录注册、完整相册扫描、上传会话和 iOS/HarmonyOS 工程实现。
