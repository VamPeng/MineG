# 阶段 04 验收记录：持久队列与自动备份

> 状态：`COMPLETED_WITH_RETAINED_NOTES`（2026-08-02 主流程验收完成；不得据此冻结契约）
> 代码范围：Android、C++ Core、Service；iOS/HarmonyOS 不在本阶段实现范围。

## 本轮验收结论

阶段 04 的主流程与常见交互已验收完成：

- [x] 上传会话创建、分片确认和完成已成功；留存服务端请求 ID：`c3e076b3c78d9d126ce38ec1ebb19772`、`acb63ecf1804c88f43331b452994971d`、`5eeb4514ae5ebaa0743ecdc9fbfea6aa`、`9297ebf3a2495525f6b3476be94d0b50`、`f2c06f82ac827c7d6da5bdc3b9162d25`、`e682cdfcb4e73687a6deae911c8d6dd8`。
- [x] 已验证上传去重命中。
- [x] 新增媒体后可创建并执行上传会话。
- [x] 上传途中从最近任务中滑出 App 后重进，主流程可正常恢复。

以下完整异常矩阵改为后续留存备注，不阻塞本阶段主流程完成。`stage04-v1` 仍保持
`BASELINED`，只有完成完整矩阵并留存全部证据后才可冻结。

## 已完成的本地自动化证据

在仓库根目录执行并通过：

```bash
cmake --build Mobile/core/build -j4
ctest --test-dir Mobile/core/build --output-on-failure
(cd Mobile/MineG_Android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug)
(cd Service && go test ./... && make openapi-check)
git diff --check
```

这些检查覆盖 Core 契约、Android 单测/Lint/Debug APK、服务端单测和 OpenAPI 校验；它们不是
真实设备、PostgreSQL 或私有 OSS 的替代品。

## 后续留存：完整环境验收矩阵

### 1. 部署与账号

- [ ] 在隔离环境执行 Service migration，确认 `00009_backup_queue_upload_recovery.sql` 已应用。
- [ ] 配置真实 PostgreSQL、真实鉴权和隔离私有 OSS；准备两个普通用户、一个管理员，以及两个 Android 设备。
- [ ] 确认 `account-v3` 已完成其独立验收并转为 `FROZEN`；阶段 04 的 `stage04-v1` 在本次验收完成前保持 `BASELINED`。

### 2. 首次历史备份与并发

- [ ] 设备 A 授予完整媒体权限，关闭自动备份后启动 App：页面显示“自动备份已关闭”，不创建新自动任务。
- [ ] 开启自动备份并保持 Wi-Fi：唯一 WorkManager 工作被安排，首次历史扫描立即开始；按拍摄时间从新到旧创建任务。
- [ ] 用 3 个以上大文件观察网络：同时最多 2 条媒体，每条最多 2 个对象分片，全局不超过 4 个对象 PUT。
- [ ] 等待全部完成：页面仅在扫描完成、无未完成任务、无待服务端确认分片时显示“同步完成”。

### 3. 网络、开关与重试

- [ ] 默认 Wi-Fi 策略下切到蜂窝：任务显示“等待 Wi-Fi”，不发送新的对象字节；开启移动网络后继续。
- [ ] 断网再恢复：任务从 `WAITING_NETWORK` 恢复，网络门禁不消耗 12 次传输失败额度。
- [ ] 注入 429/503 与 `Retry-After`：Core 持久化不早于服务端建议时间的 5 秒～15 分钟全抖动退避。
- [ ] 连续注入 12 次同一可重试传输错误：任务保留 `RETRYABLE_FAILED / BACKUP_RETRY_EXHAUSTED`；点击全局重试后恢复。
- [ ] 上传一个分片期间关闭自动备份：在飞行结果可安全确认，但不会发起新的对象 PUT；任务进入 `PAUSED_BY_SETTING`。重新开启后仅补传服务端未确认分片。

### 4. 恢复与媒体变化

- [ ] 在扫描页提交、OSS PUT、分片上报、完成请求四个边界分别强杀 App；重启后任务租约恢复并先查询服务端会话。
- [ ] 让上传会话授权过期和 multipart 重建：已确认集合正确保留或按服务端空集合重置，绝不把未确认分片误记为完成。
- [ ] 新增、编辑、删除媒体并触发 MediaStore 通知：下一轮完整代次核对能收敛删除和相册归属；未完成且本地已删除项变为 `WAITING_RESOURCE`。
- [ ] 为 M5 原文件保存预留回归：该流程写入 `download_receipts` 后，后续 M4 扫描不为同一平台条目创建重复备份任务。M5 的写回 UI 不属于本阶段范围。

### 5. 服务端与管理端回归

- [ ] 验证 `GET /api/v1/private/media` 的 owner 隔离、HMAC seek cursor 与 `GET /api/v1/media` 的兼容响应。
- [ ] 验证同账号双设备相同内容只收敛为一个云端媒体；不同账号绝不复用媒体或对象。
- [ ] 两设备各保持最多 4 个对象上传 10 分钟；管理员登录、审核列表和批准无 5xx，核心请求 p95 不超过 750 ms 且不高于空载基线两倍。
- [ ] 用管理员 Cookie 请求上传/私人媒体接口，以及用普通 Bearer 请求管理员接口，均被拒绝。

## 冻结条件

所有复选项必须留存真实环境日志、请求 ID、测试版本和测得时延后，才可将
`Mobile/contracts/stage04-v1.json` 从 `BASELINED` 改为 `FROZEN`。本文件不预填任何真实环境结果。
