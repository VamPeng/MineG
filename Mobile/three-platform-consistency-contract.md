# MineG 三端一致性契约

## 1. 契约信息

- 契约版本：v1.0
- 核定日期：2026-07-26
- 适用平台：Android、iOS、HarmonyOS
- 首个参考实现：Android
- 上位基线：[功能需求](../Requirement/functional-requirements.md)、[技术需求](../Requirement/technical-requirements.md)

本契约约束三端可见的业务名称、数据语义、桥接接口、UI 操作和状态。Android 先实现完整功能，但不能把 Android SDK 类型或 Compose 细节写入公共契约；iOS 和 HarmonyOS 后续按已经冻结的契约实现。

## 2. 执行规则

1. 开发 Android 某项功能前，先在本契约登记该功能的模型、方法、状态、事件、错误和 UI 语义标识。
2. Android 实现和自动化测试通过后，该功能契约进入 `FROZEN`；iOS、HarmonyOS 不重新命名或重新定义行为。
3. 平台能力不同，只允许适配实现不同；对用户的业务结果、状态迁移和错误语义必须一致。
4. 新增字段和枚举值可以向后兼容；删除、改名或改变既有语义必须提升契约主版本并提供迁移方案。
5. 公共名称以本文件和共享契约清单为准，不以任一平台内部类名为准。

## 3. 一致性级别

| 级别 | 必须一致 | 允许不同 |
| --- | --- | --- |
| L1：名称一致 | 领域模型、字段、业务方法、事件、错误码、页面与操作语义标识 | 语言保留字导致的受控映射 |
| L2：语义一致 | 前置条件、状态迁移、幂等性、排序、分页、空值和错误含义 | `suspend`、`async`、回调等语言写法 |
| L3：体验一致 | 页面入口、关键操作、确认规则、加载/空/错/成功状态 | 系统权限弹窗、返回手势、原生控件外观 |
| L4：平台专属 | 无公共等价能力的系统实现细节 | MediaStore、PhotoKit、PhotoAccessHelper 等内部名称 |

L1～L3 是验收项。L4 只能存在于平台适配器内部。

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
                                  ↓
                              PlatformPort
```

- `Screen`：渲染状态并发送用户操作。
- `ViewModel`：把公共状态转换成页面状态，不定义新的业务状态机。
- `UseCase`：暴露一个可测试的业务动作或查询。
- `CoreClient`：三端数据桥接统一入口。
- `PlatformPort`：相册、安全存储、网络、后台调度等平台能力。

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

Android 的 JNI、iOS 的 Objective-C++、HarmonyOS 的 Node-API 只负责类型、线程、回调和生命周期转换，不复制业务判断。

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

## 6. 关键业务方法

首版公共业务方法名称如下；新增功能必须沿用同一动词表。

| 领域 | 方法名称 |
| --- | --- |
| 账号 | `signUp`、`signIn`、`signOut`、`restoreSession`、`refreshReviewStatus` |
| 资料与密钥 | `getProfile`、`updateProfile`、`getKeyBundle`、`completeFamilyKeyGrant` |
| 权限与设置 | `getPermissionSnapshot`、`requestFullLibraryAccess`、`getBackupSettings`、`updateBackupSettings` |
| 本地媒体与备份 | `scanLocalMedia`、`observeBackupState`、`retryBackup`、`cancelCurrentOperation` |
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
2. 先补共享核心/C ABI/Port 的契约测试，再实现 Android Bridge、UseCase、ViewModel 和 Screen。
3. 使用真实后端或正式测试替身完成 Android 正常、空、错、权限失效和恢复路径。
4. 生成该功能的契约清单快照并标记 `FROZEN`。
5. iOS、HarmonyOS 开工时先跑同一套清单和测试，不根据 Android 源码反向猜测行为。

Android 功能未登记契约不得合入；契约未通过测试不得算 Android 功能完成。

## 9. 一致性验证

- C++ 核心：同一组命令、查询、事件、状态机、加密格式和 SQLite migration 测试。
- Bridge：三端读取同一契约清单，校验公开名称、字段、枚举、错误和资源释放行为。
- PlatformPort：使用统一场景用例验证成功、取消、权限失效、网络切换和进程恢复。
- UI：使用同一页面/元素语义 ID 清单和关键交互场景；允许平台截图不同，不允许操作缺失或结果不同。
- API：移动端模型必须通过 OpenAPI 兼容性检查，禁止平台自行维护不同字段含义。
- CI 在 Android 阶段校验“契约 + Android”；iOS/HarmonyOS 开始后逐端加入同一门禁。

## 10. 首版状态

| 范围 | 当前状态 | 冻结条件 |
| --- | --- | --- |
| 分层、命名和 Bridge 基础接口 | `FROZEN` | Android M0 契约测试已通过；变更需按本契约版本规则执行 |
| F-01～F-03 账号、审核、资料 | `PLANNED` | Android M1 通过 |
| F-04～F-06 权限、设置、本地相册 | `PLANNED` | Android M2 通过 |
| F-07～F-08 加密备份 | `PLANNED` | Android M3～M4 通过 |
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
