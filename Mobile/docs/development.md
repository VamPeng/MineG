# M0 移动基座开发与验证

阶段 00 只创建 Android 工程；iOS、HarmonyOS 后续直接消费冻结的 `contracts/foundation-v1.json` 与 `core/include/mineg/mineg_core.h`，本阶段不创建空壳。

## C++ 宿主机测试

```bash
brew install cmake pkg-config libsodium
bash Mobile/scripts/test-core.sh
```

测试覆盖 C ABI 重复创建/释放、SQLite v1 重开恢复、命令/查询/事件/取消、1 MiB+ 分块加密、正确解密、密文篡改失败以及失败时无部分明文输出。

## Android

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

真机点击“运行探针”后，会请求完整相册权限，读取一条媒体的文件描述符并经 C++ 分块加密；密钥只短暂写入 Android Keystore 包装的存储，测试完成后删除并清零受控缓冲区。探针密文只位于 App cache，完成后删除。

连接设备后运行 JNI 生命周期测试：

```bash
./gradlew :app:connectedDebugAndroidTest
```

Android Studio 使用 JDK 17、SDK/Target 36、NDK 27.0.12077973 与 CMake 3.22.1。SQLite 固定为 3.51.3 并由共享核心编译；libsodium 5.2.0 AAR 仅提供锁定的各 ABI 原生库，C++ 直接调用稳定 libsodium C API。

Android Studio 必须单独打开 `Mobile/MineG_Android`。`Mobile` 是 Android、iOS、HarmonyOS 与共享核心的集合目录，不属于任一平台 IDE 工程。
