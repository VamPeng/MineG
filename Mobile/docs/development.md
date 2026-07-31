# M0～M3 移动基座、账号准入与单媒体备份开发验证

阶段 00 只创建 Android 工程；iOS、HarmonyOS 后续直接消费冻结的 `contracts/foundation-v1.json` 与 `core/include/mineg/mineg_core.h`，本阶段不创建空壳。

## C++ 宿主机测试

```bash
brew install cmake pkg-config libsodium
bash Mobile/scripts/test-core.sh
```

测试覆盖 C ABI 重复创建/释放、SQLite v4 重开恢复、账号非敏感状态、Argon2id/XChaCha20-Poly1305 用户 key bundle、设备包装恢复、X25519 sealed-box 家庭 envelope、备份设置、可恢复媒体扫描、10 万条本地索引和 500 条游标分页，以及单媒体 4 MiB 认证块、固定向量、篡改/重排/截断/清单错配失败和 multipart 中途进程重启恢复。

## Android

推荐使用 APK 构建脚本完成单元测试、Lint 和 Debug 打包。默认 API 地址为通过 USB `adb reverse` 访问的本机 `http://127.0.0.1:8080`：

```bash
cd Mobile/MineG_Android
./build-apk.sh
```

构建并安装到已授权的单台设备；脚本会为本机 HTTP 地址自动建立 `adb reverse`，并使用 `adb install -r` 保留应用数据：

```bash
./build-apk.sh --install
```

构建 Release APK 时必须显式提供 HTTPS API 地址。当前工程未在源码中配置签名凭据，因此产物为待签名 APK：

```bash
./build-apk.sh --release --api-base-url https://api.example.com
```

底层 Gradle 命令仍可用于单项验证：

```bash
cd Mobile/MineG_Android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PminegDebugApiBaseUrl=http://192.168.1.6:8080
```

开发机和设备位于同一局域网时，先用 `ipconfig getifaddr en0` 获取开发机 IP。Debug 包的 `minegDebugApiBaseUrl` 接受无凭据 HTTPS，或 `10/8`、`172.16/12`、`192.168/16`、回环地址上的 HTTP；主清单和 Release 包继续禁止明文流量。后端监听 `:8080`，因此同网设备可直接访问开发机 IP，macOS 防火墙需允许 Go API 接收入站连接。

正式包必须使用具有有效证书的 HTTPS 地址：

```bash
./gradlew :app:assembleRelease -PminegReleaseApiBaseUrl=https://api.example.com
```

阶段 01 默认页面是账号准入流程。`account-v3` 注册不生成或提交用户密钥材料；管理员审核通过后直接进入 `APPROVED`。待审核页可见时每 10 秒轮询，连续失败按 10/20/40/60 秒退避，手动和回前台刷新不受退避限制。

连接设备后运行 JNI 生命周期测试：

```bash
./gradlew :app:connectedDebugAndroidTest
```

真实账号闭环测试默认跳过；只对专用临时数据库执行时，可通过 instrumentation runner 参数启用 `AccountFlowInstrumentedTest`。测试覆盖无 key bundle 注册 → 管理员 Cookie/CSRF 登录 → 幂等通过并直接 `APPROVED` → 进入资料页 → 退出 → 协议确认重新登录 → 相册权限页。不得把真实生产账号、密码或数据库用于该测试。

阶段 02 的权限验收必须分别验证 Android 14 的完整授权、部分照片授权、拒绝和系统设置撤销；非 `FULL` 状态不得创建扫描或 WorkManager 任务。OEM 若拦截 adb 安装，需要由设备所有者在手机上确认，自动化不得代为放宽“未知来源安装”设置。

阶段 03 真机验收必须使用隔离私有 OSS 和专用测试账号。完整权限下从本地相册触发一条本地媒体，确认原始内容不做客户端应用层加密，经 HTTPS 与逐分片短期授权直传私有 OSS；对象键以 `.original` 结尾，服务端按长度、SHA-256、分片与 ETag 完成确认。验收至少覆盖一张非敏感测试照片，以及授权过期、越权对象键、对象缺失、长度或摘要不符和完成响应丢失；日志、审计与错误响应不得包含 Bearer、OSS 签名 URL 或媒体正文。批量队列、后台调度与进程恢复属于阶段 04。

Android Studio 使用 JDK 17、SDK/Target 36、NDK 27.0.12077973 与 CMake 3.22.1。SQLite 固定为 3.51.3 并由共享核心编译；libsodium 5.2.0 AAR 仅提供锁定的各 ABI 原生库，C++ 直接调用稳定 libsodium C API。

Android Studio 必须单独打开 `Mobile/MineG_Android`。`Mobile` 是 Android、iOS、HarmonyOS 与共享核心的集合目录，不属于任一平台 IDE 工程。
