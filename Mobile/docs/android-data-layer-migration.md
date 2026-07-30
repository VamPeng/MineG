# Android 已实现数据层迁移技术方案

## 1. 文档信息

- 文档状态：`MIGRATION_IN_PROGRESS`
- 建立日期：2026-07-30
- 适用基线：Android `0.3.0-m3`、C ABI 4、SQLite v4
- 上位约束：[MineG 技术需求 v1.2](../../Requirement/technical-requirements.md)、[三端一致性契约 v1.1](../three-platform-consistency-contract.md)
- 审核范围：`Mobile/MineG_Android` 与 `Mobile/core`
- 非目标：本文件不改变已验收的产品行为、不回写 `contracts/*-v1.json`、不在迁移中顺便实现尚未存在的服务端接口

### 1.1 实施进度（2026-07-30）

- 批次 A 已完成：新增 `foundation-v2`、ABI 5、SQLite v5 可恢复 operation、Android 通用 Effect Dispatcher、五类 Port 测试替身和默认拒绝的数据主权扫描门禁。
- 批次 B 的生产账号主链已完成：新增 `account-v2` 与 SQLite v6，注册、登录、Session 恢复/轮换/退出、审核查询和 Profile 查询/更新由 Core operation 驱动；Token 仅通过批量 `SecureStoreEffect` 原子写入，Android 专属 `mineg_profile_cache` 已移除。
- 批次 C 的生产主链已实现：新增 `stage02-v2` 与 SQLite v7，Key Grant 协调、头像创建授权/对象上传/完成确认、私人媒体列表与账号隔离缓存均由 Core operation 驱动；主页只消费 Core 返回的 Profile/PrivateMedia Snapshot。
- `AndroidAccountClient` 仍作为扫描、备份设置、单媒体上传和未退役旧账号 UI 的过渡依赖保留；旧路径中的 KeyGrant、头像和私人媒体实现继续按 AND-DATA-012 登记，不再由当前 `MainActivity` 生产入口调用。不得据此将整体 M3-D 标记完成。
- 当前迁移实现版本：C ABI 5、SQLite v7；既有 ABI 4/SQLite v4 行为继续兼容。

本文件列出 Android 已实现但仍在 Kotlin/Compose 层拥有领域数据、接口编排、业务缓存或状态迁移的代码。迁移目标不是让 C++ 直接持有 Android 网络、权限或系统对象，而是让 C++ Core 成为业务请求、领域数据和状态机的唯一所有者，Android 只保留 UI、Bridge 与 PlatformPort。

## 2. 审核结论

当前生产入口是：

```text
MainActivity
  → MineGAppViewModel
  → AndroidMineGAppRuntime
  → CoreAccountClient / CoreStage02Client
  → AndroidAccountClient（仅扫描、设置、上传过渡路径）
  → AndroidTransportPort / AndroidSecureStorePort / AndroidMediaSourcePort
  → Service
```

当前 C++ Core 已负责：

- SQLite migration、账号路由状态、本地媒体索引、备份设置和单媒体任务状态；
- 用户/家庭/媒体密钥、内容指纹和媒体资源加解密；
- 已登记 Core 命令、查询、事件与状态迁移校验。

当前 Kotlin 仍负责：

- 扫描循环、部分备份门禁和单媒体上传业务编排；
- 审核轮询的执行时钟与页面 UiState 映射；
- 分享、删除、恢复、下载、反馈和部分备份设置的页面内模拟状态。

因此目前 C++ 更接近“加密与持久化组件”，尚未成为完整的跨端数据层。

## 3. 迁移判定标准

满足任一条件即必须迁入 C++ Core：

1. 数据来自服务端 API/RPC；
2. 数据需要缓存、离线回退、账号隔离或进程恢复；
3. 数据跨页面、后台任务或 UseCase 共享；
4. 逻辑决定分页、排序、去重、幂等、重试、权限门禁或下一业务状态；
5. 逻辑创建或修改账号、资料、媒体、分享、回收站、反馈或备份领域模型。

原生层允许保留：输入草稿、焦点、滚动、动画、导航、弹窗、展示格式、可重建图片缓存，以及 Android 系统对象。若平台状态影响业务，必须作为 PlatformEffectResult 回到 Core 决策。

## 4. 目标架构

```text
Screen
  → ViewModel（UiState 与 UiEffect）
  → UseCase（薄调用）
  → CoreClient
  → C ABI
  → C++ Core Operation
       ├─ 查询/更新领域状态
       ├─ 生成 PlatformEffect
       └─ 消费 PlatformEffectResult 后继续状态机
              │
              └─ Android Effect Dispatcher
                   ├─ AndroidTransportPort
                   ├─ AndroidSecureStorePort
                   ├─ AndroidMediaSourcePort
                   ├─ AndroidBackgroundSchedulerPort
                   └─ AndroidFile/SystemAlbum Port
```

### 4.1 Effect 最小信封

兼容契约版本至少需要表达：

```text
PlatformEffect
  contractVersion
  operationId
  sequence
  effectType
  payload

PlatformEffectResult
  contractVersion
  operationId
  sequence
  status
  payload / error
```

Core 必须拒绝 operation ID、sequence、Effect 类型或响应结构不匹配的结果，避免旧响应污染新账号或新操作。平台只传输 Effect 指定的数据，不补字段、不解释业务成功、不自行重试业务请求。

### 4.2 凭据边界

- Access/Refresh Token、设备安装 ID 和设备包装密钥继续存放在 Android Keystore 包装的 `SecureStorePort`。
- C++ 通过 SecureStoreEffect 决定读取、替换和删除时机；Token 不写入 C++ SQLite。
- Token 只在组装授权 Effect 和处理刷新结果所需的短生命周期内进入受控内存，并在操作完成后清理。
- HTTP 401、Session 重放、Refresh 过期和退出清理必须由 Core 状态机处理。

### 4.3 传输可替换性

Core 领域操作使用稳定名称，例如 `account.signIn`、`profile.getCurrent`、`media.listPrivate`。当前 REST 路径和 JSON/RFC 9457 编解码由 Core 的 REST 协议适配层生成。Android TransportPort 只发送 Core 提供的路由/消息和原始字节，不能让 ViewModel 或 Android Repository 感知传输实现细节。

## 5. 已实现迁移清单

| ID | 优先级 | 领域 | 当前状态 | 当前 Android 所有权 | 目标 |
| --- | --- | --- | --- | --- | --- |
| AND-DATA-001 | P0 | 账号与 Session | 当前生效 | API、DTO、Token 刷新、错误映射、会话内存 | Core 账号状态机 + Transport/SecureStore Effect |
| AND-DATA-002 | P0 | 用户资料与缓存 | 当前生效 | `/me` 解析、资料更新、SharedPreferences 回退 | Core Profile Snapshot + SQLite 缓存 |
| AND-DATA-003 | P0 | Key grant 与头像 | 生产主链已迁移；旧路径例外保留 | 旧账号 UI 仍编译 | Core 编排；平台只执行图片预处理、SecureStore 与 Transport Effect |
| AND-DATA-004 | P0 | 私人媒体主页列表 | 生产主链已迁移；旧路径例外保留 | 旧 Client 方法仍编译 | Core 查询、账号隔离 SQLite 快照与离线回退 |
| AND-DATA-005 | P0 | 单媒体上传 | 已实现，当前新入口未直接调用 | 上传状态机和服务端 DTO | Core 完整状态机；TransportPort 只上传字节/分片 |
| AND-DATA-006 | P1 | 本地扫描 | 当前生效 | 恢复判断、分页循环、generation、调度 | Core 扫描 operation；MediaSourcePort 返回批次 |
| AND-DATA-007 | P1 | 备份设置与门禁 | UI 部分生效 | ViewModel 本地状态；旧 Client 含真实设置逻辑 | Core Settings/Task Snapshot + Scheduler Effect |
| AND-DATA-008 | P0 | 分享/删除/恢复/下载/反馈 | 页面生效但业务为模拟 | ViewModel 直接增删列表或标记成功 | 正式实现时直接进入 Core；联调前不得宣称完成 |
| AND-DATA-009 | P1 | 审核轮询与页面准入 | 当前生效 | 轮询、错误分流、资料准入判断 | Core 审核 operation；ViewModel 只响应领域状态 |
| AND-DATA-010 | P1 | 业务校验 | 当前生效 | 手机号、密码、昵称规则 | Core 权威校验；平台可保留等价的即时展示预校验 |
| AND-DATA-011 | P1 | Kotlin 领域 Contract | 当前生效 | 平台接口和模型可承载业务判断 | 收缩为生成/验证的 Bridge Snapshot 与 Port 原语 |
| AND-DATA-012 | P2 | 旧账号 UI/数据路径 | 未被当前入口调用 | 旧 ViewModel、旧 Composable、直接 URL 图片请求 | 主链迁移稳定后单独退役，不作为迁移实现来源 |

## 6. 分项迁移范围

### AND-DATA-001：账号、Session 与审核状态

当前代码：

- `app/src/main/java/com/mineg/mobile/account/AndroidAccountClient.kt`：`signUp`、`signIn`、`signOut`、`restoreSession`、`refreshReviewStatus`、`refresh`、`sendAuthorized`、`sendSessionRequest`、`parseProblem`、`saveSession`；
- `app/src/main/java/com/mineg/mobile/app/MineGAppRuntime.kt`：账号方法转发；
- `app/src/main/java/com/mineg/mobile/app/MineGAppViewModel.kt`：登录/注册校验、审核刷新与会话路由。

迁移内容：

- 注册、登录、刷新、退出、审核查询的请求/响应/错误模型；
- Session 过期、刷新、重放、退出和账号切换状态机；
- 审核状态到下一业务步骤的映射；
- 账号非敏感状态的 SQLite 事务和领域事件；
- SecureStoreEffect 的凭据读写与删除顺序。

完成条件：Android 生产代码不再出现 `/api/v1/auth/*`、Session 业务 JSON 或 Token 刷新判断；真实注册—审核—登录—恢复—退出回归保持通过。

### AND-DATA-002：用户资料与资料缓存

当前代码：

- `AndroidAccountClient.kt`：`getProfile`、`updateProfile`；
- `MineGAppRuntime.kt`：`loadProfile`、`updateProfile`、`AndroidProfileCache`；
- `MineGAppViewModel.kt`：资料 ID 校验、资料合并与主页准入。

迁移内容：

- `/me` 与资料更新协议适配；
- `CurrentProfileSnapshot` 的 SQLite schema、版本、更新时间和账号隔离；
- 网络失败时是否允许读取缓存的 Core 判断；
- 返回资料 ID 与当前账号不一致时的失败关闭；
- 昵称更新成功后的服务端结果合并。

完成条件：删除 `mineg_profile_cache` 业务用途；所有受保护页面所用用户资料来自 Core Query/Event；退出和账号切换不残留上一账号资料。

### AND-DATA-003：Key grant 与头像

当前代码：

- `AndroidAccountClient.kt`：`updateAvatar`、`getKeyBundle`、`completeFamilyKeyGrant`；
- `updateAvatar` 内部直接创建 `HttpURLConnection`，绕过 `TransportPort`。

迁移内容：

- key bundle/grant DTO、grant 选择、envelope 提交与幂等状态；
- Core 加密操作与 Transport/SecureStore Effect 的组合 operation；
- 头像创建授权、对象上传、完成确认和 Profile Snapshot 更新；
- 对象传输必须统一经过 TransportPort，不允许业务 Client 自建连接。

完成条件：密码学密钥继续只在 C++ 受控内存；Kotlin 不解析 key grant/头像业务响应，不决定 grant 完成状态。

### AND-DATA-004：私人媒体主页列表

当前代码：

- `AndroidAccountClient.kt`：`listOwnerMedia`；
- `MineGAppRuntime.kt`：`listOwnerMedia`；
- `MineGAppViewModel.kt`：`loadHomeModels`、`OwnerMediaSummary.toMediaItem`。

迁移内容：

- 私人媒体 DTO、稳定游标、排序、空值和错误映射；
- Core 中按账号隔离的列表/详情快照和缓存失效；
- ViewModel 仅保留日期、本地化时长、颜色和展示标签转换。

完成条件：主页领域列表由 Core Query/Event 驱动；ViewModel 不能作为刷新、恢复或冲突合并来源。

### AND-DATA-005：单媒体上传状态机

当前代码：

- `AndroidAccountClient.kt`：`backupSingleMedia` 及其 prepare/recovery/part helper；
- C++ 已保存任务、资源、分片、ETag 和完成状态，但 Kotlin 决定下一网络步骤。

迁移内容：

- `CreateUpload → UploadPart → ReportPart → CompleteUpload` 全状态机；
- 去重命中、授权过期、完成响应丢失、可重试/永久失败判断；
- 每个外部副作用前的 Core 事务；
- 分片授权到 UploadPartEffect、上传结果到 ETag 状态的回传；
- 服务端确认后临时密文清理 Effect。

完成条件：相同 Core 数据库和 EffectResult 测试向量在三端得到相同下一 Effect/最终状态；Kotlin 不再包含 `/api/v1/uploads` 或上传业务 JSON。

### AND-DATA-006：本地扫描

当前代码：

- `AndroidAccountClient.kt`：`scanLocalMedia`、`listLocalAlbums`、`listLocalMedia`；
- `MineGAppRuntime.kt`：`refreshLocalLibrary`。

迁移内容：

- 是否允许扫描、是否恢复、generation、cursor 和批次循环；
- MediaSourceEffect 的分页参数与平台结果；
- 批次事务、相册关系、扫描完成和后续任务创建；
- Core Query 输出仍由 Bridge 转换成不可变平台 Snapshot。

完成条件：Android MediaSourcePort 只读取权限、相册、媒体和资源句柄；不决定扫描状态或是否创建备份工作。

### AND-DATA-007：备份设置与门禁

当前代码：

- `AndroidAccountClient.kt`：`getBackupSettings`、`updateBackupSettings`；
- `MineGAppViewModel.kt`：`setAutoBackupEnabled`、`setCellularBackupEnabled` 当前仅修改 UiState；
- `AndroidBackgroundSchedulerPort`：保存系统调度所需派生配置。

迁移内容：

- Settings command/query、权限/网络/用户开关到任务门禁的 Core 决策；
- SchedulerEffect 只描述申请或取消执行机会；
- 系统调度派生配置可以保留，但不得成为业务设置真相。

完成条件：切换设置后必须先由 Core 持久化并发布事件，再更新 UI 和调度系统任务；进程重启以后 Core 状态仍为准。

### AND-DATA-008：页面中的模拟领域操作

当前代码：

- `MineGAppViewModel.kt`：`finishMockDownload`、`toggleShare`、`confirmDialog` 中的删除/恢复、`submitFeedback`；
- `MineGApp.kt`：这些方法已经绑定到当前页面操作。

处理要求：

- 这些逻辑不是“迁移既有真实业务”，而是尚未联调的 UI 占位；
- 在对应 M5/M6 服务接口与 Core 契约完成前，不得把本地列表变化或模拟成功作为产品完成状态；
- 正式实现直接从 Core command/query/event 开始，不先补 Android Repository。

完成条件：生产页面只显示真实 Core 结果；Mock 仅存在于 Preview、截图夹具或显式测试源集。

### AND-DATA-009～011：轮询、校验与 Bridge Contract

- 审核 10 秒轮询可以由平台时钟/协程提供执行机会，但退避、并发、状态结果和是否继续轮询由 Core operation 决定；
- 手机号、密码、昵称可以在 UI 做即时预校验，但 Core 必须执行相同或更严格的权威校验并返回稳定错误码；
- Kotlin `AccountSession`、`Profile`、`LocalMedia` 等类型只能作为生成/验证的 Bridge Snapshot，接口实现不得包含业务算法；
- API snake_case 与 Core lowerCamelCase 的转换必须在 Core 协议适配层或生成器中完成，不能在各端手写三次。

### AND-DATA-012：旧路径

以下代码未被当前 `MainActivity` 生产入口使用，但仍参与编译：

- `AccountViewModel.kt`；
- `MainActivity.kt` 中私有 `MineGAccountApp` 及旧账号页面；
- 旧 `MediaThumbnail` 中直接 URL 网络读取。

主迁移期间保持不动并禁止新增调用。待新主链回归稳定后单独确认退役范围；不得从旧路径复制数据层到 Core 或未来平台。

## 7. 保留的 Android 平台实现

以下代码属于合理的平台边界，不作为业务迁移删除目标：

- `AndroidTransportPort`：HTTPS/对象字节传输、超时、取消和原始响应；
- `AndroidSecureStorePort`：Android Keystore AES-GCM 包装与原子读写；
- `AndroidMediaSourcePort`：权限、MediaStore 分页、资源描述符和派生资源；
- `AndroidBackgroundSchedulerPort`：WorkManager 执行机会；
- `AndroidConnectivityPort`：网络连接与计量状态；
- `AndroidFilePort`：临时文件、空间和安全删除；
- Compose Screen 与 ViewModel 中纯展示 `UiState`、导航、弹窗和输入草稿。

这些实现后续要改为统一消费 Core Effect。平台派生缓存必须可丢弃、可从 Core 重建，并在文档中明确不具备业务权威性。

## 8. 契约版本策略

既有 `foundation-v1.json`、`account-v1.json`、`stage02-v1.json` 和 `stage03-v1.json` 保留作为历史行为与安全基线，不直接修改。迁移应新增：

- `foundation-v2`：PlatformEffect、EffectResult、operation 恢复、取消、序列和错误；
- `account-v2`：账号/Session/审核/Profile Core 命令、查询、事件和 SecureStore/Transport Effect；
- `stage02-v2`：Profile/Avatar/KeyGrant/Scan/Settings 的 Core 所有权；
- `stage03-v2`：完整上传状态机和分片 Effect；
- `platform-data-exceptions-v1`：确需保留的平台领域数据例外，初始应为空或仅包含有明确移除条件的诊断路径。

v2 通过并切换生产入口后，v1 进入 `DEPRECATED`，至少保留一个发布周期。业务字段和用户可见行为无变化时可以提供兼容 Bridge；不得通过同时维护两套可写数据源完成迁移。

## 9. 实施批次

### 批次 A：Foundation v2

1. 定义 PlatformEffect/EffectResult 与 C ABI operation 生命周期；
2. Android 建立通用 Effect Dispatcher；
3. 为 Transport、SecureStore、MediaSource、Scheduler、File 建测试替身；
4. 增加数据主权静态扫描门禁。

### 批次 B：账号、审核与资料

1. 迁移 AND-DATA-001、002、009、010；
2. 接通登录—审核—资料—权限/主页；
3. 移除 Android 资料缓存权威性；
4. 完成进程恢复、Token 轮换、退出和账号隔离回归。

### 批次 C：主页与密钥协调

1. 迁移 AND-DATA-003、004；
2. 主页只消费 Core 的 Profile/PrivateMedia Snapshot；
3. 头像、key grant 和对象传输统一走 Effect。

### 批次 D：扫描与备份设置

1. 迁移 AND-DATA-006、007；
2. WorkManager/MediaStore 退回纯 Port；
3. 验证权限撤销、进程重启、10 万索引和账号切换。

### 批次 E：单媒体上传

1. 迁移 AND-DATA-005；
2. 用隔离对象存储验证断网、授权过期、分片重试和完成响应丢失；
3. `stage03-v2` 达标后再决定 M3 是否转为 `FROZEN`。

### 批次 F：后续领域与旧路径

1. M5/M6 功能从 Core 开始实现，不迁移模拟成功代码；
2. 清除生产入口 Mock 领域操作；
3. 主链稳定后另行确认 AND-DATA-012 的退役范围；
4. Android 数据层门禁通过后才启动 iOS/HarmonyOS 业务页面开发。

## 10. 验收与停止条件

每个批次必须同时满足：

- C++ 主机测试覆盖正常、空、错、重试、取消、乱序 EffectResult 和进程恢复；
- Android Bridge/Port 测试证明只执行 Effect，不包含领域判断；
- Android 生产代码扫描不再出现已迁移领域的业务路径、响应解析或业务偏好缓存；
- 同一 operation 测试向量在 Android 测试 Port 与主机 FakePort 得到相同 Core 状态和事件；
- 真实后端 Transport 完成对应纵向闭环；
- 退出、账号切换和应用进程重建不显示其他账号资料、媒体或任务；
- 日志、SQLite、偏好设置和崩溃信息不包含明文密码、Token、密钥或不必要个人数据；
- ViewModel 只保存可丢弃 UiState，不能在无 Core 结果时模拟领域成功。

以下情况必须停止扩展并先修正架构：

- 为赶通 Android 流程准备新增业务 API Client、Repository 或 SharedPreferences 缓存；
- Core Effect 无法表达需求，准备让平台自行解释响应或决定下一状态；
- 同一业务逻辑需要在 Kotlin、Swift、ArkTS 各写一次；
- 迁移要求同时写旧平台缓存与 Core 数据库，但没有单向切换和回滚方案。

## 11. 完成定义

Android 数据层迁移完成后应满足：

```text
Android = Compose UI + UiState Mapper + Core Bridge + PlatformPort
C++ Core = 领域模型 + API/RPC 协议 + 缓存 + 状态机 + Repository + 事件
```

届时 iOS 与 HarmonyOS 只需要实现对应 UI、Bridge 和 PlatformPort，即可直接复用账号、资料、媒体、备份、家庭、回收站与反馈数据层，不再根据 Android 源码重写业务实现。
