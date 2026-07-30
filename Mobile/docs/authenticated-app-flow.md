# Android 启动、登录、缓存与主页流程约束

本文约束当前 Android 参考实现的业务入口。新版 Compose 页面不得使用 Mock 状态绕过账号准入。

> 数据主权修订：本文描述目标约束。当前 Android 中仍存在 Kotlin 账号 Client、资料 SharedPreferences 缓存和 ViewModel 领域列表，属于待迁移过渡实现；准确范围见[Android 数据层迁移技术文档](./android-data-layer-migration.md)。

## 强制不变量

1. App 冷启动先进入 `Restoring`，完成本地会话校验前不得渲染受保护页面。
2. 无 Refresh Token、会话彻底失效或用户资料校验失败时进入 `Login`，并保证 `profile == null`。
3. `PrivateSpace`、`Backup`、`Profile` 及其所有子页面都要求非空、且 ID 与当前会话一致的用户资料。
4. 登录成功只表示取得会话；必须继续按 `next_step` 分流：`REVIEW_PENDING` 进入审核页，`APP_HOME` 拉取并校验 `/api/v1/me` 后才能进入权限页或主页。
5. 未取得完整相册权限时不得启动本地扫描或备份任务。用户可暂缓授权进入主页，但备份状态必须为 `PERMISSION_REQUIRED`。
6. 退出登录必须取消账号任务、撤销或清理本地会话、锁定内存密钥、清理资料缓存，并清空所有受保护页面模型。
7. 业务默认状态不得包含演示手机号、密码、用户资料或媒体。Mock 数据只允许用于显式预览/测试夹具。
8. 登录、审核、资料和主页领域数据必须通过 `CoreClient` 从 C++ Core 查询；页面和 ViewModel 不直接调用业务 API、解析响应或持久化业务缓存。
9. 网络、安全存储、权限和媒体读取由 PlatformPort 执行 Core 产生的 Effect；EffectResult 必须回到 Core 完成校验和状态迁移后才能驱动 UI。

## 路由流程

```text
Restoring
├─ 无有效会话 / 会话失效 ─> Login
├─ REVIEW_PENDING ─────────> ReviewPending ──审核通过──┐
└─ APP_HOME ──────────────────────────────────────────┤
                                                     v
                                             拉取并校验 Profile
                                             ├─ 失败 -> Login
                                             ├─ 完整相册权限 -> PrivateSpace
                                             └─ 其他权限 -> Permission
                                                                ├─ 完整授权 -> PrivateSpace
                                                                └─ 暂不开启 -> PrivateSpace（禁止备份）
```

## 本地缓存边界

| 数据 | 存储 | 退出时处理 |
| --- | --- | --- |
| Access/Refresh Token 与过期时间 | Android Keystore 包装后的安全存储 | 删除 |
| 设备安装 ID、设备包装密钥、用户解锁材料 | Android Keystore 包装后的安全存储 | 安装 ID/设备包装密钥保留；当前用户解锁材料删除 |
| 用户 ID、脱敏手机号、审核状态 | 共享 C++ SQLite 账号状态 | 清除当前账号状态 |
| 昵称、脱敏手机号和资料版本 | 共享 C++ SQLite 当前用户资料快照；仅会话已验证且 Core 判定允许时离线回退 | 清除或按账号隔离策略失效 |
| 密码 | 不缓存，只在登录/注册调用期间使用 | 不适用 |
| 私人媒体、家庭媒体、回收站和备份列表 | C++ Core 查询/分页快照；ViewModel 只持有可丢弃 UiState | 清除当前账号内存快照，持久缓存按账号隔离策略处理 |

头像 URL 是短期签名地址，不进入持久资料缓存。

## Core 业务操作与传输映射

以下路径属于当前 REST Transport 的协议细节，由 C++ Core 生成并解析；Android `TransportPort` 只发送字节，ViewModel 和领域操作名称不依赖具体 HTTP 路径。

- `POST /api/v1/auth/register`：注册并提交客户端加密 key bundle。
- `POST /api/v1/auth/login`：登录并记录协议版本。
- `POST /api/v1/auth/refresh`：启动恢复或授权请求时静默轮换 Token。
- `POST /api/v1/auth/logout`：退出并撤销 Refresh Token。
- `GET /api/v1/auth/approval-status`：待审核页手动、前台及定时刷新。
- `GET /api/v1/me`：取得主页所需的当前用户资料。
- `PATCH /api/v1/me/profile`：保存昵称，由 Core 更新当前资料快照。
- `GET /api/v1/media`：由 Core 取得、解析和分页本人已完成备份的媒体元数据。

家庭相册、回收站、分享、下载和反馈接口尚未出现在当前 OpenAPI 中；对应页面仍不属于本流程“已联调完成”的范围。

## 平台层禁止事项

- `MineGAppRuntime`、ViewModel 或平台 Repository 不得硬编码上述业务路径或解析业务 JSON。
- Android SharedPreferences 不得保存用户资料、媒体列表、审核状态或任务状态作为业务回退来源。
- ViewModel 不得通过本地增删列表模拟分享、删除、恢复、下载或反馈成功。
- Session 过期、Token 刷新、审核分流、资料身份校验和缓存回退条件必须由 Core 决定。
