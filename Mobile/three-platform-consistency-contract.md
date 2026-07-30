# MineG 三端一致性契约

## 1. 契约信息

- 契约版本：v1.1
- 核定日期：2026-07-26
- 最近修订：2026-07-30（明确 C++ 领域数据主权与 PlatformEffect 边界）
- 适用平台：Android、iOS、HarmonyOS
- 首个参考实现：Android
- 上位基线：[功能需求](../Requirement/functional-requirements.md)、[技术需求](../Requirement/technical-requirements.md)

本契约约束三端可见的业务名称、数据语义、数据所有权、桥接接口、UI 操作和状态。Android 先实现完整功能，但不能把 Android SDK 类型或 Compose 细节写入公共契约；iOS 和 HarmonyOS 后续按已经冻结的契约实现。v1.1 对分层所有权作强制澄清，不回写已发布的 `contracts/*-v1.json`；现存不符合项按第 15 节迁移，不视为可以继续复制的平台参考实现。

## 2. 执行规则

1. 开发 Android 某项功能前，先在本契约登记该功能的模型、方法、状态、事件、错误和 UI 语义标识。
2. Android 实现和自动化测试通过后，该功能契约进入 `FROZEN`；iOS、HarmonyOS 不重新命名或重新定义行为。
3. 平台能力不同，只允许适配实现不同；对用户的业务结果、状态迁移和错误语义必须一致。
4. 新增字段和枚举值可以向后兼容；删除、改名或改变既有语义必须提升契约主版本并提供迁移方案。
5. 公共名称以本文件和共享契约清单为准，不以任一平台内部类名为准。
6. 领域数据必须由 C++ Core 唯一拥有；同名平台接口、相同 DTO 或相同测试结果不能替代共享实现。
7. 已冻结业务语义若缺少 Core 命令或 PlatformEffect，新增兼容契约版本补齐，不直接改写历史冻结清单。

## 3. 一致性级别

| 级别 | 必须一致 | 允许不同 |
| --- | --- | --- |
| L0：数据主权 | 领域模型所有者、API/RPC 编排、缓存、状态机、Core 命令/查询/事件与 PlatformEffect | UI 临时状态和平台原语实现 |
| L1：名称一致 | 领域模型、字段、业务方法、事件、错误码、页面与操作语义标识 | 语言保留字导致的受控映射 |
| L2：语义一致 | 前置条件、状态迁移、幂等性、排序、分页、空值和错误含义 | `suspend`、`async`、回调等语言写法 |
| L3：体验一致 | 页面入口、关键操作、确认规则、加载/空/错/成功状态 | 系统权限弹窗、返回手势、原生控件外观 |
| L4：平台专属 | 无公共等价能力的系统实现细节 | MediaStore、PhotoKit、PhotoAccessHelper 等内部名称 |

L0～L3 是验收项。L4 只能存在于平台适配器内部。

## 4. 命名规范

### 4.1 通用名称

- 类型、命令、查询和事件类型使用 `UpperCamelCase`，例如 `BackupTask`、`SignInCommand`、`BackupStateChanged`。
- 方法和字段的逻辑名称使用 `lowerCamelCase`，例如 `signIn`、`mediaId`、`capturedAt`。
- 枚举值和稳定错误码使用 `UPPER_SNAKE_CASE`，例如 `WAITING_FOR_WIFI`、`AUTH_INVALID_CREDENTIALS`。
- C ABI 使用 `mineg_` 前缀和 `snake_case`；其注释必须标明对应的公共逻辑名称。
- JSON 字段使用 `lowerCamelCase`；时间统一为 UTC RFC 3339，精确到毫秒；ID 是不透明字符串。
- 同一概念禁止出现近义词分叉，例如不得同时使用 `signIn`、`login`、`logIn` 表示同一业务方法。

### 4.2 原生语言映射

公共逻辑名称在三端保持相同基础标识：

| 契约名称 | Kotlin | Swift | ArkTS |
| --- | --- | --- | --- |
| `signIn` | `signIn` | `signIn` | `signIn` |
| `observeBackupState` | `observeBackupState` | `observeBackupState` | `observeBackupState` |
| `setAutoBackupEnabled` | `setAutoBackupEnabled` | `setAutoBackupEnabled` | `setAutoBackupEnabled` |
| `requestFullLibraryAccess` | `requestFullLibraryAccess` | `requestFullLibraryAccess` | `requestFullLibraryAccess` |
| `savePrivateMedia` | `savePrivateMedia` | `savePrivateMedia` | `savePrivateMedia` |
| `restoreFromTrash` | `restoreFromTrash` | `restoreFromTrash` | `restoreFromTrash` |

参数标签、异步封装和返回容器可遵循语言惯例，但参数含义、默认值和结果状态不得改变。若语言保留字造成冲突，必须在契约变更记录中登记唯一映射。

## 5. 公共层与数据桥接

### 5.1 固定分层

三端均使用以下逻辑层名和依赖方向：

```text
Screen → ViewModel → UseCase → CoreClient → C ABI → C++ Core
                                  ↑                 │
                                  │                 └─ PlatformEffect
                                  │                         ↓
                                  └──────── PlatformEffectResult ← PlatformPort
```

- `Screen`：渲染状态并发送用户操作。
- `ViewModel`：把 Core 领域快照转换成页面状态，不定义新的业务状态机，也不持久化领域缓存。
- `UseCase`：暴露一个可测试的业务动作或查询。
- `CoreClient`：三端数据桥接统一入口。
- `PlatformPort`：执行 Core 请求的相册、安全存储、网络、后台调度等平台原语，不解释业务结果。

### 5.2 CoreClient 基础接口

三端桥接必须提供同名职责：

| 方法 | 语义 |
| --- | --- |
| `initialize` | 打开核心、执行 migration、恢复当前账号上下文 |
| `execute` | 执行版本化命令并返回稳定结果或错误 |
| `query` | 执行只读查询，不隐式改变业务状态 |
| `subscribe` | 订阅版本化领域事件 |
| `unsubscribe` | 解除订阅，之后不得继续回调 |
| `cancel` | 取消调用方不再需要的进行中操作 |
| `close` | 释放句柄并阻止新调用 |

Android 的 JNI、iOS 的 Objective-C++、HarmonyOS 的 Node-API 只负责类型、线程、回调、Effect 转发和生命周期转换，不复制业务判断。

### 5.3 PlatformPort 基础接口

Port 类型名三端必须一致：`MediaSourcePort`、`SecureStorePort`、`TransportPort`、`BackgroundSchedulerPort`、`ConnectivityPort`、`FilePort`、`MediaPlaybackPort`、`SystemAlbumWriterPort`。

首批关键方法名：

| Port | 公共方法名 |
| --- | --- |
| `MediaSourcePort` | `getPermissionSnapshot`、`requestFullLibraryAccess`、`listAlbums`、`listMedia`、`openMediaResource`、`observeLibraryChanges` |
| `SecureStorePort` | `readSecret`、`writeSecret`、`deleteSecret` |
| `TransportPort` | `sendApiRequest`、`uploadPart`、`downloadObject`、`cancelTransfer` |
| `BackgroundSchedulerPort` | `scheduleBackup`、`cancelBackup`、`reportExecutionWindow` |
| `ConnectivityPort` | `getConnectivitySnapshot`、`observeConnectivityChanges` |
| `FilePort` | `createEncryptedTempFile`、`getAvailableSpace`、`deleteTempFile` |
| `SystemAlbumWriterPort` | `savePrivateMedia` |

平台实现类可使用平台后缀，如 `AndroidMediaSourcePort`、`IosMediaSourcePort`、`HarmonyMediaSourcePort`；传入核心的数据必须先转换为公共模型，禁止跨过 Port 传递平台对象。

### 5.4 公共数据语义

- 缺失、空集合、空字符串和 `null` 是不同语义，不能互相替代。
- 所有列表都明确 `items`、`nextCursor` 和稳定排序；无下一页时 `nextCursor = null`。
- 布尔字段使用肯定命名，如 `autoBackupEnabled`，不使用双重否定。
- 状态只使用契约枚举；客户端不得以显示文案推断状态。
- 错误统一包含 `code`、`messageKey`、`retryable`、`requestId` 和可选 `details`；UI 根据 `code` 和 `retryable` 决策，不解析错误文本。
- 平台相册标识只作为 `platformAssetRef` 保存在本机，不上传为跨端业务主键。

### 5.5 领域数据主权

满足任一条件的数据均属于领域数据，必须由 C++ Core 建模并作为唯一客户端真实来源：

- 来自服务端 API/RPC；
- 需要本地缓存、离线回退或跨进程恢复；
- 被两个及以上页面、UseCase 或后台任务共享；
- 参与权限门禁、页面准入、排序、分页、去重、重试、幂等或状态迁移；
- 代表账号、资料、成员、媒体、分享、回收站、反馈结果、备份设置或任务状态。

原生层可以持有 Core 输出的不可变快照和派生 `UiState`，但不得把该副本用于恢复、合并、冲突裁决或后台任务真相。输入草稿、焦点、滚动位置、动画、弹窗、导航栈和可重建图片缓存不属于领域数据。

平台对象只允许存在于 Port 内部。若权限、连接、存储空间、媒体可用性或系统任务回调影响业务，Port 必须返回公共结果，由 Core 决定领域状态。大媒体数据使用文件描述符、流、受控路径或不透明句柄，不要求复制进 Core 内存。

### 5.6 PlatformEffect 与结果回传

Core 命令需要外部副作用时返回版本化 `PlatformEffect`；平台执行后通过同一 operation ID 回传 `PlatformEffectResult`，Core 再继续状态机。首批 Effect 类型至少覆盖：

| Effect | Core 决定 | Port 只负责 |
| --- | --- | --- |
| `TransportEffect` | API/RPC 操作、路径/方法、业务头和正文、幂等与后续状态 | 建连、TLS、发送字节、接收状态/头/正文、取消 |
| `SecureStoreEffect` | 凭据逻辑名称、读取/替换/删除时机 | KeyStore/Keychain/HUKS 加解密和原子读写 |
| `MediaSourceEffect` | 扫描批次、恢复游标、索引与任务迁移 | 权限原语、媒体分页和资源句柄 |
| `BackgroundSchedulerEffect` | 是否需要任务、账号和业务约束 | 向系统申请/取消执行机会并报告执行窗口 |
| `File/SystemAlbumEffect` | 文件生命周期、校验和业务完成条件 | 临时文件、空间查询、安全删除和系统相册写入 |

平台不得在 EffectResult 返回后自行解析领域 DTO、刷新 Token、重试业务请求、更新业务缓存或决定成功状态。HTTPS REST 或后续其他传输只改变 `TransportPort`，不改变 ViewModel 与领域 UseCase。

任何绕过 Core 的领域数据必须进入版本化 `platformDataExceptions` 清单，至少登记所有者、原因、平台、生命周期、安全边界、测试和移除条件；没有清单项的绕过实现不得合入。

## 6. 关键业务方法

首版公共业务方法名称如下；新增功能必须沿用同一动词表。

| 领域 | 方法名称 |
| --- | --- |
| 账号 | `signUp`、`signIn`、`signOut`、`restoreSession`、`refreshReviewStatus` |
| 资料与密钥 | `getProfile`、`updateProfile`、`getKeyBundle`、`completeFamilyKeyGrant` |
| 权限与设置 | `getPermissionSnapshot`、`requestFullLibraryAccess`、`getBackupSettings`、`updateBackupSettings` |
| 本地媒体与备份 | `scanLocalMedia`、`backupSingleMedia`、`getSingleMediaBackup`、`observeBackupState`、`retryBackup`、`cancelCurrentOperation` |
| 私人空间 | `listPrivateMedia`、`getPrivateMediaDetail`、`savePrivateMedia`、`moveToTrash` |
| 家庭相册 | `shareMedia`、`unshareMedia`、`listFamilyMedia`、`getFamilyMediaDetail` |
| 回收站 | `listTrashItems`、`restoreFromTrash` |
| 帮助与反馈 | `listFaqItems`、`submitFeedback` |

`cancelCurrentOperation` 只取消当前可取消的前台读取或传输，不等于提供单个备份任务的产品控制入口。

## 7. UI 交互契约

### 7.1 页面语义标识

关键页面使用稳定语义 ID：

```text
auth.login
auth.signup
auth.reviewPending
permission.library
backup.overview
private.list
private.detail
family.list
family.detail
trash.list
profile.home
help.faq
feedback.form
```

Android `testTag`、iOS `accessibilityIdentifier`、HarmonyOS 自动化测试 key 均使用同一个语义 ID；子元素采用 `<screenId>.<element>`，例如 `auth.login.submit`。

### 7.2 操作和页面状态

- 操作使用业务动词：`submitSignIn`、`submitSignUp`、`openBackupSettings`、`requestPermission`、`openMediaDetail`、`confirmDelete`、`confirmUnshare`、`confirmRestore`、`submitFeedback`。
- 页面状态统一使用 `INITIAL`、`LOADING`、`CONTENT`、`EMPTY`、`ERROR`、`BLOCKED`、`SUCCESS`；页面可增加已登记的领域子状态，但不能用平台控件状态代替业务状态。
- 一次性效果统一称为 `UiEffect`，首批类型为 `Navigate`、`ShowToast`、`ShowDialog`、`OpenSystemSettings`、`OpenSystemViewer`。
- 导航动画、系统返回手势和权限弹窗样式遵循平台规范；目标页面、确认规则和最终业务结果保持一致。
- 展示文案可以按平台和语言本地化；语义 ID、触发条件和对应错误码不能随文案变化。

## 8. Android 优先实施流程

每个 Android 功能按以下顺序完成：

1. 在契约登记模型、字段、方法、事件、错误码、页面 ID 和关键操作。
2. 先补共享核心/C ABI/PlatformEffect/Port 的契约测试，证明领域数据所有权和状态机已在 Core，再实现 Android Bridge、UseCase、ViewModel 和 Screen。
3. 使用真实后端或正式测试替身完成 Android 正常、空、错、权限失效和恢复路径。
4. 生成该功能的契约清单快照并标记 `FROZEN`。
5. iOS、HarmonyOS 开工时先跑同一套清单和测试，不根据 Android 源码反向猜测行为。

Android 功能未登记契约不得合入；契约未通过测试不得算 Android 功能完成。仅在 Android 中实现业务 Client、DTO 解析、领域缓存或状态迁移，即使端到端流程可运行，也不能标记为 `FROZEN`。

## 9. 一致性验证

- C++ 核心：同一组命令、查询、事件、状态机、加密格式和 SQLite migration 测试。
- Bridge：三端读取同一契约清单，校验公开名称、字段、枚举、错误和资源释放行为。
- PlatformPort：使用统一场景用例验证成功、取消、权限失效、网络切换和进程恢复。
- UI：使用同一页面/元素语义 ID 清单和关键交互场景；允许平台截图不同，不允许操作缺失或结果不同。
- API：移动端模型必须通过 OpenAPI 兼容性检查，禁止平台自行维护不同字段含义。
- 数据主权：CI 扫描 Kotlin、Swift、ArkTS 生产代码中的业务 API/RPC 路径、领域响应解析、业务偏好缓存和平台端状态迁移；只允许命中登记的例外。
- Effect：相同命令与 EffectResult 测试向量在三端必须得到相同 Core 状态、事件和错误，Port 测试不得包含领域判断。
- CI 在 Android 阶段校验“契约 + Android”；iOS/HarmonyOS 开始后逐端加入同一门禁。

## 10. 首版状态

| 范围 | 当前状态 | 冻结条件 |
| --- | --- | --- |
| 分层、命名和 Bridge 基础接口 | `FROZEN` | Android M0 契约测试已通过；变更需按本契约版本规则执行 |
| F-01～F-03 账号、审核、资料 | `FROZEN` | Android M1 契约、自动化测试与真实注册审核闭环已通过；变更需按本契约版本规则执行 |
| F-04～F-06 权限、设置、本地相册 | `FROZEN` | Android M2 与隔离 OSS/权限矩阵验收已通过 |
| F-07 与单媒体 F-08 加密备份 | `BASELINED` | Android M3 隔离 OSS 与代表性真实媒体验收通过 |
| F-08 批量队列 | `PLANNED` | Android M4 通过 |
| F-09～F-10 私人空间与保存 | `PLANNED` | Android M5 通过 |
| F-11～F-14 家庭、回收站、帮助反馈 | `PLANNED` | Android M6 通过 |

契约状态只允许 `PLANNED → BASELINED → FROZEN → DEPRECATED`。废弃项至少保留一个发布周期，并明确替代名称。

## 11. M0 基座冻结记录

- 冻结清单：[`contracts/foundation-v1.json`](./contracts/foundation-v1.json)
- 冻结范围：固定分层、CoreClient 基础方法、C ABI 生命周期、PlatformPort 类型名、基座错误码与纵向探针语义 ID。
- 回调规则：事件回调发生在触发命令的线程；回调不得重入 `unsubscribe` 或 `close`。`unsubscribe` 返回后不会再有该订阅的回调。
- 所有权规则：传入缓冲区只在调用期间借用；返回的 `mineg_buffer_t` 由调用方使用 `mineg_buffer_free` 释放；核心句柄由 `mineg_core_close` 唯一释放。
- 取消规则：调用方分配非零 `operationId`；`cancel` 幂等标记该操作，核心在命令提交和耗时步骤边界检查并返回 `CANCELLED`。
- 冻结日期：2026-07-26。

## 12. M1 账号准入冻结记录

- 冻结清单：[`contracts/account-v1.json`](./contracts/account-v1.json)
- 冻结范围：F-01～F-03 的账号/会话/资料模型，`signUp`、`signIn`、`signOut`、`restoreSession`、`refreshReviewStatus`、`getProfile` 方法，账号错误码、页面语义 ID、轮询和安全存储规则。
- C ABI：版本升至 2，新增 `mineg_core_create_user_key_bundle`；使用 Argon2id 派生包装密钥，并用 XChaCha20-Poly1305 加密 X25519 私钥与用户主密钥，输出包不含明文私钥。
- 本地状态：SQLite v2 只保存用户 ID、脱敏手机号、审核状态和更新时间；Access/Refresh Token 与设备安装标识由平台安全存储保护。
- 审核语义：管理员通过只创建 `KEY_GRANT_PENDING` 协调任务；家庭 envelope 就绪前，移动端和 API 对外状态仍为 `PENDING`，不增加中间页面。
- 验证：C++ 主机测试、Android JVM 契约/校验测试、lint、arm64-v8a/x86_64 构建以及 OnePlus 8T Android 14 真实后端注册—审核—待 key grant—退出—重新登录闭环均通过。
- 冻结日期：2026-07-26。

## 13. M2 密钥、资料、权限与本地相册冻结记录

- 冻结清单：[`contracts/stage02-v1.json`](./contracts/stage02-v1.json)
- 冻结范围：家庭密钥 bootstrap/grant、资料更新、六态相册权限、设备级备份设置、MediaSourcePort 分页扫描以及本地相册三列网格。
- C ABI：版本升至 3；用户密钥包解封和家庭 sealed-box 操作留在 C++，进程恢复只保存由设备随机包装密钥再次加密的 unlock blob。
- 本地状态：SQLite v3 保存设备设置、扫描游标、媒体/相册索引与关系；不保存密码、私钥、User Master Key、Family Sharing Key 或媒体明文。
- 权限门禁：仅 `FULL` 可创建扫描与调度；`NOT_DETERMINED`、`LIMITED`、`RESTRICTED`、`DENIED`、`SYSTEM_RESTRICTED` 均保持在统一说明页。
- 当前状态：`FROZEN`；隔离 OSS 头像闭环和 Android 权限/真实相册矩阵已由项目执行方确认通过。
- 冻结日期：2026-07-26。

## 14. M3 单媒体加密备份基线

- 基线清单：[`contracts/stage03-v1.json`](./contracts/stage03-v1.json)
- 基线范围：F-07 和单媒体 F-08 的 Media Key envelope、账号私有 HMAC 去重、资源 KDF、4 MiB XChaCha20-Poly1305 块、认证清单、multipart 上传与恢复状态机。
- C ABI：版本升至 4；Media Key 明文只存在于 C++ 受控内存，平台桥接只接收加密 envelope、密文资源路径、摘要和认证清单。
- 本地状态：SQLite v4 在网络副作用前保存任务、资源、分片、服务端上传 ID 和已确认 ETag；核心测试覆盖 multipart 中途进程重启、篡改、块重排、截断与清单错配失败关闭。
- 平台适配：`MediaSourcePort` 流式打开原资源并尽力生成 `THUMBNAIL` 或 `VIDEO_COVER`；`TransportPort.uploadPart` 只消费服务签发的精确 PUT 授权。
- 当前状态：`BASELINED`；隔离 OSS 上的真实照片、视频、GIF、Live/动态资源及过期授权/断网/完成响应丢失矩阵通过后转为 `FROZEN`。

## 15. 2026-07-30 数据主权修订记录

- 修订原因：代码审核确认 Android `AndroidAccountClient` 仍拥有账号、资料、密钥协调、媒体 API 与单媒体上传编排，`MineGAppRuntime` 存在 Android 专属资料缓存，部分 ViewModel 直接修改领域列表；这些实现会迫使 iOS/HarmonyOS 复制数据层。
- 修订结论：既有 v1 清单冻结的是业务名称、模型、安全边界和用户可见语义，不再作为“数据层已完成共享”的证据。历史验收记录保持不变，不回写或伪造原结论。
- 迁移要求：新增 Foundation/Account/Stage02/Stage03 的兼容契约版本，补齐 PlatformEffect、Core 业务命令、Core 查询/事件和平台数据例外清单；完成前 Android 相关实现标记为过渡实现。
- 开发门禁：迁移完成前不得启动 iOS/HarmonyOS 业务数据层，也不得在 Android 新增业务 API DTO、领域 SharedPreferences 缓存或 ViewModel 领域状态机。
- 迁移清单：以 [`docs/android-data-layer-migration.md`](./docs/android-data-layer-migration.md) 为当前实施输入。

## 16. 数据主权迁移批次 C 基线

- 基线清单：[`contracts/stage02-v2.json`](./contracts/stage02-v2.json)。
- Core operation：家庭 Key Grant 协调、头像创建授权/对象上传/完成确认、私人媒体列表统一通过 `stage02-v2` 命令运行；平台不解析对应服务 DTO。
- 本地状态：SQLite v7 按账号保存私人媒体快照与刷新时间；退出或账号切换后查询失败关闭，不向新账号暴露旧快照。
- 平台适配：头像的选择、居中裁剪和缩放属于 Android 图片输入适配；摘要、API 请求、对象授权解释、完成确认和 Profile Snapshot 合并由 Core 决定，对象字节只通过 `TransportEffect.uploadObject` 传输。
- 兼容性：C ABI 保持 5；`stage02-v1` 继续保留历史行为基线，旧账号 UI 中的过渡实现按 AND-DATA-012 单独退役。
- 当前状态：生产主链实现及主机/Android 单元契约验证完成；真实后端头像、Key Grant、离线主页和进程恢复矩阵通过后转为 `FROZEN`。
