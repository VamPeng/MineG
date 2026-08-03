# 阶段 04 技术方案：完整队列与自动备份

> 方案状态：`BASELINED`
> 核定日期：2026-08-02
> 交付范围：B3/B4、M4、管理端兼容回归
> 上位基线：[产品需求](../../product-requirements.md)、[功能需求 F-04～F-08](../../functional-requirements.md)、[技术需求](../../technical-requirements.md)、[三端一致性契约](../../../Mobile/three-platform-consistency-contract.md)

## 1. 阶段结论

阶段 04 不建立第二套上传协议，也不引入服务端消息队列。实施基于已经冻结的 `stage03-v2 / MEDIA_ORIGINAL` 单媒体链路，新增以下能力：

1. C++ Core 持久化历史/增量扫描进度、媒体任务、资源、分片确认、重试和服务端会话；
2. Android WorkManager 只提供受网络约束的后台执行机会，任务真相和下一步动作仍由 Core 决定；
3. 服务端补齐精确分片核对、授权续签、游标私人列表、相册关系、稳定错误与观测；
4. 备份页由 Core 的聚合快照驱动，覆盖关闭、扫描、上传、等待、失败和完成状态；
5. Android 验收通过后将 `stage04-v1` 从 `BASELINED` 转为 `FROZEN`，供 iOS/HarmonyOS 后续复用。

阶段 04 只上传平台提供的原始资源集合（实况/动态媒体包含其必要配对原资源），不生成客户端媒体密文或派生缩略图/预览。私人详情、原文件保存、分享、删除和回收站仍属于阶段 05/06。

## 2. 开工门禁与已知基线

### 2.1 已满足输入

- `stage02-v2 2.1.0` 已冻结：Core 拥有完整权限判断、本地相册索引和两个备份设置；
- `stage03-v2 2.0.0` 已冻结：单条原始媒体可通过 ECS 授权直传私有 OSS，并由服务端完成长度/SHA-256 核对；
- Android 数据主权批次 D 已完成：平台层只执行 `PlatformEffect`，不得恢复 Kotlin 业务队列；
- 自动备份和移动网络备份均默认关闭，且配置按账号和设备隔离。

### 2.2 退出前必须满足

- `account-v3` 直接准入契约完成冻结；阶段 04 可以先开发队列，但不得在账号契约仍为 `BASELINED` 时关闭阶段；
- 服务端顺序 migration 和 OpenAPI 版本确定；当前计划使用下一个可用 migration，禁止回写 00001～00008；
- 移动 SQLite 使用下一个顺序 migration，禁止恢复已由 migration 009 删除的旧密文任务表；
- 阶段 04 的命令、查询、事件、状态、错误和 Effect action 先登记在 [`stage04-v1.json`](../../../Mobile/contracts/stage04-v1.json)，再进入功能编码。

## 3. 范围与非范围

### 3.1 必须交付

- 首次历史扫描：从拍摄时间最新项开始，分页发现全部可备份媒体；
- 增量扫描：覆盖新增、编辑、删除、资源可用性及相册归属变化；
- 持久队列：进程回收、App 重启、设备重启、版本升级后可核对恢复；
- 网络门禁：默认仅非计量网络，开启移动网络后允许任意已连接网络；
- 并发：最多 2 条媒体同时执行，每条媒体最多 2 个分片同时传输，全局最多 4 个对象传输；
- 幂等和去重：同设备版本、本账号跨设备、请求重放和完成响应丢失均能收敛；
- 聚合状态：真实扫描、上传、等待 Wi-Fi、离线、空间不足、服务失败、主动重试和完成；
- 后端本人媒体游标分页以及上传会话/分片精确恢复；
- 管理端在上传压力下的登录、审核列表、批准和权限边界回归；
- 真实 PostgreSQL、真实鉴权和隔离私有 OSS 的阶段退出验证。

### 3.2 明确不做

- iOS、HarmonyOS 功能实现；本阶段只冻结可复用契约；
- 单任务暂停、取消、忽略、重排或详情页；
- 用户可配置同步频率、并发数、分片大小或相册选择；
- 私人媒体详情、预览/下载授权、系统相册写回、分享、删除和恢复；
- 管理端上传监控、配额面板、媒体入口或运维指标页面；
- Redis、Kafka/RabbitMQ、服务端异步任务队列、微服务或 Kubernetes；
- 重新启用 Media Key、key envelope、媒体密文副本或加密清单。

## 4. 分层与运行模型

```text
Compose / BackupViewModel
        │ Command / Query / Event
        v
CoreClient → C ABI → C++ Core → SQLite 队列（业务真相）
                         │
                         ├─ MediaSourceEffect ───────> MediaStore
                         ├─ ConnectivityEffect ──────> Android 网络状态
                         ├─ TransportEffect ─────────> ECS / 私有 OSS
                         └─ BackgroundSchedulerEffect > WorkManager
                                                        │
                                                        └─ 只回报执行窗口
```

### 4.1 C++ Core

Core 独占以下职责：

- 决定是否扫描、扫描模式、分页游标和何时创建任务；
- 任务排序、状态迁移、并发槽位、幂等键、退避和错误分类；
- API 路径、请求 JSON、响应解析、Token 刷新和 OSS 授权续签编排；
- 持久化任务、分片和服务端确认，生成 `BackupOverviewSnapshot`；
- 根据设置、权限、网络和最早 `nextRetryAt` 决定是否请求系统再次调度。

Core 不持久化 OSS 签名 URL、临时 STS 凭据、文件描述符或 Android Worker ID。

### 4.2 Android 平台层

- `AndroidMediaSourcePort` 分页读取 MediaStore、打开/释放资源描述符并接收变化通知；
- `AndroidConnectivityPort` 返回 `OFFLINE / METERED / UNMETERED` 原始快照；
- `AndroidTransportPort` 发送 Core 给出的 API 字节或上传指定文件区间，回传原始 HTTP/ETag/进度；
- `AndroidBackgroundSchedulerPort` 用唯一工作申请或取消执行机会；
- `CoroutineWorker` 只调用 `RunBackupCycle`、执行 Effect、回传 EffectResult，并在 Core 请求让出时结束。

Kotlin 不得保存扫描游标、复制任务表、解析上传业务响应、选择重试分片或根据 WorkManager 状态显示“备份完成”。

### 4.3 服务端

服务端继续是 Go 模块化单体。PostgreSQL 保存上传会话和已完成媒体，OSS 保存媒体正文。服务端不保存客户端队列，不因阶段 04 引入后台消费者。

## 5. 公共契约

### 5.1 Command

| Command | 调用方 | 语义 |
| --- | --- | --- |
| `ReconcileBackupQueue` | 用户开启备份、手动刷新、相册变化或后台窗口 | 检查门禁并执行可恢复历史/增量核对 |
| `RunBackupCycle` | 前台或 Worker | 在给定执行窗口内领取可运行任务并推进，安全让出后可再次调用 |
| `RetryBackupQueue` | 备份页全局“重试” | 重置当前账号可重试/永久失败任务的连续失败计数，不提供单任务操作 |
| `NotifyLibraryChanged` | MediaStore 变化观察器 | 只记录需要增量核对并请求调度，不直接修改最终索引或任务 |

`UpdateBackupSettings` 沿用 `stage02-v2`。关闭自动备份后，Core 不再发现新任务或发起新的对象字节上传；正在执行的外部请求允许回传结果，Core 只做保存已确认分片、完成确认或释放资源所需的安全收尾，然后进入 `PAUSED_BY_SETTING`。

### 5.2 Query 与 Event

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Query | `GetBackupOverview` | 备份总览页的业务状态来源 |
| Query | `GetLocalAlbumBackupProgress` | 当前本地相册按当前媒体版本统计的已完成数/总数及每项同步状态 |
| Query | `GetBackupQueueSummary` | 数量、最早重试时间和调度诊断；不形成单任务产品入口 |
| Event | `BackupOverviewChanged` | 聚合状态或计数变化 |
| Event | `BackupProgressChanged` | 当前媒体/资源瞬时进度，可高频且不要求逐字节持久化 |
| Event | `BackupQueueChanged` | 任务创建、终态或批量重试后的低频事件 |
| Event | `BackupScheduleRequested` | Core 的期望调度发生变化，平台仍只执行调度原语 |

### 5.3 Effect action

- `MediaSourceEffect`：`getPermissionSnapshot`、`getLibraryCheckpoint`、`listBackupCandidates`、`openMediaResource`、`releaseMediaResource`；
- `ConnectivityEffect`：`getConnectivitySnapshot`；
- `TransportEffect`：`sendApiRequest`、`uploadPart`、`cancelTransfer`；
- `BackgroundSchedulerEffect`：`scheduleBackup`、`cancelBackup`、`reportExecutionWindow`；
- `FileEffect`：仅在平台资源必须落入受控临时文件时使用 `getAvailableSpace`、`createTaskTempFile`、`deleteTempFile`，原始照片/视频优先直接从可重开资源读取。

所有 Effect 带 `operationId`、`sequence` 和账号作用域；账号变化后的迟到结果必须被 Core 拒绝。

## 6. 扫描方案

### 6.1 历史扫描

1. Core 取得完整权限和一个平台媒体库上界 `upperBound`；
2. 使用 `HISTORICAL_DESC` 按 `capturedAt DESC, platformAssetRef DESC` 分页，每页最多 500 条；
3. 每页在一个事务中写入候选索引、相册关系并 `INSERT OR IGNORE` 对应备份任务；
4. 扫描游标和计数随页提交，进程中断后从最后提交页继续；重复一页不会重复任务；
5. 历史扫描完成后，把扫描开始时的 `upperBound` 作为增量起点，再核对扫描期间发生的变化；
6. 候选索引完整后原子切换 `local_library_active`，未完成代次不得对 UI 可见。

任务调度始终按 `capturedAt DESC, taskId DESC`，因此近期内容优先；扫描和上传可以重叠，但最多使用既定的 2 条媒体并发。

### 6.2 增量扫描

- `MODIFIED_ASC` 使用 `(modifiedVersion, platformAssetRef)` 游标，查询严格大于已提交游标且不超过本轮上界的数据；
- 新增或 `contentVersion` 改变时创建新任务；相同版本只更新索引/相册关系；
- 平台能够返回稳定删除标识时按页写入 tombstone；Android 版本/厂商不能提供可靠删除游标时，变化通知标记 `FULL_RECONCILE`，通过新旧完整代次差集收敛删除和相册归属变化，不能假装 `DATE_MODIFIED` 查询能够发现删除；
- 删除或资源不可用时更新本地可用性，不删除已经完成的云端媒体；未完成任务转为 `WAITING_RESOURCE`；
- 系统变化通知只把 `reconcileRequested=true` 写入 Core，并触发唯一后台工作；
- 手动刷新、权限恢复、版本迁移和周期性安全核对可以启动完整扫描，但 UI 不增加“同步频率”设置。

### 6.3 下载回执

扫描候选先匹配 `download_receipts`。回执证明该平台条目由 MineG 写回且云端媒体仍已知时，不创建新任务；回执缺失时仍走服务端账号内去重，不能仅凭文件名或相册名忽略内容。

## 7. Core SQLite 模型

实施使用顺序 migration 新建当前明文媒体队列表，不复用历史 Stage03 v1 密文表名和字段。

### 7.1 `backup_scan_state`

| 字段 | 说明 |
| --- | --- |
| `user_id`、`device_installation_id` | 账号和安装实例联合主键 |
| `mode` | `HISTORICAL / INCREMENTAL / FULL_RECONCILE` |
| `state` | `IDLE / SCANNING / WAITING_PERMISSION / FAILED` |
| `generation_id` | 当前候选索引代次 |
| `cursor_json`、`upper_bound_json` | Core 校验后的公共游标，不保存 Android SDK 对象 |
| `reconcile_requested` | 变化通知是否要求再核对一次 |
| `discovered_count` | 本轮已提交数量 |
| `started_at`、`completed_at`、`updated_at` | UTC 时间 |

### 7.2 `backup_tasks`

| 字段 | 说明 |
| --- | --- |
| `task_id` | Core 生成 UUID |
| `user_id`、`device_installation_id` | 账号隔离和调度作用域 |
| `platform_asset_ref`、`content_version` | 本设备快速去重键 |
| `client_media_id`、`idempotency_key` | 创建后保持稳定，所有重放复用 |
| `media_type`、`mime_type`、`captured_at` | 服务端媒体元数据和排序 |
| `state`、`resume_state` | 当前状态与暂停/失败后的恢复点 |
| `server_upload_id`、`server_media_id` | 服务端会话和完成结果 |
| `retry_count`、`next_retry_at` | 连续自动重试控制 |
| `failure_code`、`failure_scope` | 稳定错误及 LOCAL/NETWORK/SERVICE/OSS/AUTH 分类 |
| `lease_token`、`lease_expires_at` | 防止两个 Core 执行窗口重复推进同一任务 |
| `created_at`、`updated_at` | UTC 时间 |

唯一约束为 `(user_id, device_installation_id, platform_asset_ref, content_version)`；领取索引按可运行状态、`next_retry_at`、`captured_at DESC, task_id DESC`。

### 7.3 `backup_resources` 与 `backup_parts`

- 资源保存稳定 `resource_id`、类型、长度、SHA-256、准备状态和服务端确认状态；
- 分片固定 4 MiB，最后一片允许不足；保存 `part_number`、offset、长度、SHA-256、ETag 和 `PENDING / TRANSFERRED / CONFIRMED`；
- `TRANSFERRED` 表示 OSS PUT 已返回但 ECS 尚未确认，恢复时先向服务端核对，不直接当作完成；
- 只持久化服务端确认字节数；当前传输字节通过进度事件展示；
- 不保存签名 URL、Authorization、Cookie、Refresh Token、媒体正文或文件描述符。

### 7.4 事务与租约

- Core 继续使用单 SQLite 写线程、WAL、外键和短事务；
- 领取任务、状态迁移、确认分片和发布低频事件必须在一致事务边界内；
- 进程退出后租约到期，下一次执行先 `GET upload session` 核对服务端，再决定补传、重新上报或完成；
- 同一个 `(task, resource, part)` 在本机最多有一个活动传输 Effect，但网络超时仍按幂等语义处理未知结果。

## 8. 任务状态机

### 8.1 持久任务状态

```text
DISCOVERED
  ├─> WAITING_PERMISSION
  ├─> WAITING_RESOURCE
  ├─> WAITING_NETWORK
  └─> PREPARING
          └─> CREATING_SESSION
                  ├─> UPLOADING
                  │       └─> SERVER_VERIFYING
                  │                └─> COMPLETED
                  └─> COMPLETED          # 服务端去重命中

可执行状态 ─> RETRYABLE_FAILED ─> resume_state
可执行状态 ─> PERMANENT_FAILED ─> 用户全局重试后重新 PREPARING/核对
未完成状态 ─> PAUSED_BY_SETTING ─> 原 resume_state
```

`ENCRYPTING` 已从当前状态机删除；旧客户端密文任务只作为 migration 输入，不能映射成新上传步骤。

### 8.2 状态规则

- 权限、设置、网络是业务门禁，不消耗自动失败次数；
- 本地云相册资源尚未落地时为 `WAITING_RESOURCE`，资源变化通知后重新打开；
- `CREATING_SESSION` 复用同一个幂等键；未知响应先重放，不生成新键；
- `UPLOADING` 只调度服务端未确认分片；同会话内不得因进程恢复重传已确认分片；
- 上传会话过期时服务端可以创建新的 OSS multipart upload，并明确返回确认集合已重置；任务、资源摘要和幂等键仍复用；
- `SERVER_VERIFYING` 必须通过服务端完成响应或会话查询确认后才进入 `COMPLETED`；
- 自动备份关闭不回滚已确认分片，不删除任务；重新开启后从 `resume_state` 恢复；
- 会话失效不显示备份错误页，Core 停止账号工作、清理凭据并触发登录流程。

### 8.3 重试

- 指数退避加全抖动：基数 5 秒，上限 15 分钟；若服务端返回合法 `Retry-After`，取不早于服务端建议的时间；
- 每个失败作用域默认最多 12 次连续自动失败；耗尽后保留 `RETRYABLE_FAILED`。用户全局“重试”可重置全部连续失败；网络重新连通只重置 NETWORK 作用域，明确服务恢复信号只重置 SERVICE/OSS 作用域；
- 离线、等待 Wi-Fi、权限、设置关闭不计入 12 次；
- 参数/摘要/资源格式不一致、越权对象键和确定不可恢复的 4xx 进入 `PERMANENT_FAILED`；
- STS/签名过期先查询原会话取得新授权；401 只允许 Core 执行一次标准 Token 刷新，失败后走会话失效。

## 9. 服务端方案

### 9.1 沿用与新增接口

沿用每媒体接口，不新增批量会话：

```text
POST /api/v1/uploads
GET  /api/v1/uploads/{upload_id}
POST /api/v1/uploads/{upload_id}/parts
POST /api/v1/uploads/{upload_id}/complete
```

阶段 04 新增规范本人列表 `GET /api/v1/private/media?cursor=&limit=`；现有 `GET /api/v1/media` 在一个兼容周期内保留并返回同一结果，Stage03 客户端不被破坏。列表只返回已完成、未删除、当前账号媒体摘要，不返回对象地址。

### 9.2 OpenAPI 增量

- `MediaResourceStatus` 增加精确 `confirmed_part_numbers`，不能只返回数量；
- 会话返回 `grant_generation`，授权重签或 multipart 重建后递增；若重建导致旧分片失效，返回新的空确认集合；
- `CreateMediaUploadRequest` 兼容增加客户端相册集合：稳定 `client_album_id` 和名称；服务端按当前账号/设备 upsert，并在完成或去重命中时幂等补齐关系；
- 私人列表增加不透明 `cursor`、`next_cursor`；游标用现有 HMAC 配置签名并绑定 owner、排序和过滤条件；
- 409/429/503 可返回 `Retry-After`，RFC 9457 `Problem.retryable` 与 HTTP 状态保持一致；
- Stage03-v2 请求继续可用；新增字段必须向后兼容，breaking check 不允许删除旧字段或枚举。

### 9.3 数据库与查询

- 媒体列表条件固定为 `owner_id = current_user AND upload_status = COMPLETED AND trashed_at IS NULL`；阶段 04 尚无 `trashed_at` 时预留查询边界，阶段 05 migration 后启用；
- 游标排序固定为 `(captured_at DESC, id DESC)`，SQL 使用 seek 条件，不使用 offset；
- 为客户端相册补齐名称和设备作用域，`media_album_links` 使用唯一约束幂等附加；
- 会话、资源和分片继续使用条件更新；完成事务必须在相同内容并发时收敛为同一个本账号媒体；
- 账号内去重使用 owner 作用域和内容/资源清单摘要。由于当前架构为未加密原文件且服务端必须保存 SHA-256 做完整性校验，不再宣称服务端对跨账号内容具有密码学不可比较性；但任何去重查询、唯一约束和结果复用都禁止跨 owner；
- 增加过期未完成 multipart 候选查询和受限清理命令的 dry-run；清理只处理已过期且未完成会话，绝不能触及 `READY`/`COMPLETED` 资源。

### 9.4 稳定错误分类

| 服务端/对象错误 | Core 结果 |
| --- | --- |
| `UPLOAD_VERIFYING`、连接池/服务 503、OSS 限流 | 可重试并尊重 `Retry-After` |
| `UPLOAD_NOT_ACTIVE` / 授权过期 | 查询会话并续签或重建 multipart |
| `UPLOAD_PARTS_INCOMPLETE` | 查询精确分片集合并只补缺失项 |
| `UPLOAD_PART_MISMATCH`、越权键、非法摘要 | 永久失败，用户重试时重新准备并重新核对 |
| `REMOTE_STORAGE_FULL` | 阻止新上传，聚合页展示服务/云端空间不足 |
| Access Token 过期 | Core 刷新一次；刷新失败执行会话失效 |

### 9.5 观测和资源保护

- 指标至少覆盖会话创建结果、活跃/过期会话、分片成功/重试、授权重签、完成时延、完成结果、去重命中、OSS/数据库错误；
- 指标标签不得包含 user/media/upload/object ID，避免敏感信息和高基数；
- 日志只记录 request ID、稳定错误码、阶段和耗时，不记录 Token、签名 URL、临时凭据、对象正文或完整摘要；
- 上传完成/OSS 调用设置独立并发上限，避免耗尽数据库连接；管理员路由不与移动上传共享业务限流额度；
- 1 GiB 资源按 4 MiB 分片为 256 片，服务端不得把资源正文读入内存。

## 10. Android 调度与页面

### 10.1 WorkManager

- 每个账号/安装实例使用一个不可逆哈希命名的唯一工作；不在工作名、tag 或日志暴露手机号和原始 Token；
- 默认约束 `UNMETERED`，允许移动网络后改为 `CONNECTED`；设置变化由 Core 请求替换唯一工作；
- Worker 不使用自身 `Result.retry()` 表达业务退避，业务 `nextRetryAt` 由 Core 持久化并请求下一次执行；只有 Core 无法初始化等平台级瞬时失败才使用 Worker retry；
- 每个执行窗口先回报可用时间/是否允许长任务，Core 在 Effect 边界安全让出；需要长时间上传时按 Android 规则启用 long-running Worker/前台通知；
- App/设备重启后 WorkManager 重新提供机会，Core 先恢复过期租约并核对服务端；
- 设置关闭、权限撤销、退出登录时由 Core 请求取消唯一工作。相册变化观察器只请求重新调度。

### 10.2 备份聚合状态

页面使用 `BackupOverviewSnapshot`，至少包含：

- `state`、`autoBackupEnabled`、`allowCellularBackup`；
- `discoveredCount`、`pendingCount`、`completedCount`、`failedCount`；
- 当前媒体的本地展示引用、媒体类型、资源类型、`confirmedBytes`、`transferredBytes`、`totalBytes`；
- `nextRetryAt`、`failureCode`、`retryable`；
- `lastScanCompletedAt`、`lastServerConfirmedAt`。

页面状态优先级：会话失效（直接导航）→ 非完整权限 → 自动备份关闭 → 活动上传 → 扫描 → 等待 Wi-Fi/离线 → 空间不足 → 服务失败/待重试 → 完成。

“同步完成”必须同时满足：最近一次请求的扫描已结束、没有待发现变化、没有未完成任务、没有待服务端确认分片。自动备份关闭、权限不足或存在失败任务时不得显示完成。

进度百分比表示当前资源。持久值只计算服务端确认字节，当前上传可叠加 `transferredBytes` 平滑展示；进程回收后允许回落到最后确认值，但不得虚假前进或提前完成。

原型映射：

- `10-backup-scanning` → `SCANNING`；
- `03-backup-uploading` → `UPLOADING / SERVER_VERIFYING`；
- `05-backup-waiting-for-wifi` → `WAITING_FOR_WIFI`；
- `06-backup-network-offline` → `OFFLINE`；
- `09-backup-service-unavailable` → `SERVICE_UNAVAILABLE / REMOTE_STORAGE_FULL`；
- `11-backup-complete` → `COMPLETED`；
- 设置关闭沿用主页面“自动备份已关闭 + 开始备份”；
- 设备空间不足使用相同状态卡组件补充真实阻塞文案，不新增导航页面。

## 11. 实施批次

| 批次 | 交付 | 退出条件 |
| --- | --- | --- |
| 04A 契约与迁移 | `stage04-v1`、OpenAPI diff、服务端/移动 migration、错误和状态测试向量 | breaking check、migration 正反向测试、Core 状态属性测试先红后绿 |
| 04B 服务端恢复性 | 精确分片状态、授权续签、相册关系、私人游标列表、指标和清理 dry-run | 乱序/重复/并发/过期/完成响应丢失集成测试通过 |
| 04C Core 队列 | 可恢复扫描、任务/资源/分片表、状态机、退避、聚合 Query/Event | 10 万索引、非法跃迁、崩溃恢复和账号隔离主机测试通过 |
| 04D Android Port/调度 | Connectivity/BackgroundScheduler、Worker、变化观察、并发 Effect 驱动 | 强杀、设备重启、Wi-Fi/蜂窝切换、设置开关仪器测试通过 |
| 04E 页面接线 | 真实状态卡、进度、全局重试、开始备份 | 原型状态均由 Core 快照驱动，无 Mock/WorkManager 业务判断 |
| 04F 压测与冻结 | 隔离 OSS、管理端压力回归、故障演练、文档与契约冻结 | 三端计划完成门槛全部通过并形成真实 acceptance 记录 |

批次顺序以契约和数据模型为先；04B 与 04C 可在契约冻结候选确定后并行，Android 页面不得先于 Core 聚合快照建立第二套临时业务状态。

## 12. 验收矩阵

### 12.1 Core/Android

- 100,000 条历史媒体按页扫描，近期任务先上传，峰值内存不随条目总数线性增长；
- 1 GiB 视频使用 256 个 4 MiB 分片，任意分片失败/乱序时只补传服务端未确认项；
- 在扫描页提交、摘要计算、OSS PUT、分片上报、完成请求每个边界强杀进程，重启后最终收敛；
- 设备重启、Wi-Fi→蜂窝→离线→Wi-Fi、设置开关、权限撤销/恢复均不产生非法跃迁；
- 自动备份关闭不创建新任务或上传新字节，重新开启从已确认位置恢复；
- 同设备重复扫描、同账号两设备同内容、MineG 下载回执和完成响应丢失均只产生一个有效云端媒体；
- 另一个账号的任务、缓存、媒体 ID 和错误详情不出现在当前账号快照；
- Kotlin 生产代码静态扫描不包含业务 API 路径、上传 DTO 解析、任务数据库或重试状态机。

### 12.2 服务端/OSS

- 真实 PostgreSQL 覆盖 migration、owner 约束、seek cursor、相册 upsert 和并发唯一约束；
- 假 OSS 日常测试覆盖授权/分片协议；阶段退出在隔离私有 OSS 覆盖授权过期、对象缺失、摘要错、限流、multipart 重建和完成响应丢失；
- 数据库提交失败不误标完成；OSS 未核对成功不创建可见媒体；
- 过期清理 dry-run 不包含已完成会话或 READY 资源；
- 普通成员、另一个成员和管理员 Cookie 均不能读取不属于其身份的会话或媒体。

### 12.3 管理端与容量

- 参考负载为两个设备、每设备最多 4 个并发分片以及并发会话恢复；连续 10 分钟时管理员登录、列表、批准无 5xx；
- 管理端核心请求 p95 不高于无上传基线的 2 倍且不超过 750 ms；最终发布 SLO 留到阶段 09；
- 管理端没有媒体、任务、配额或指标路由，日志只携带 request ID；
- 服务端内存不随 1 GiB 媒体正文增长，数据库查询不使用 offset 媒体分页。

## 13. 阶段完成定义

只有以下条件同时满足，阶段 04 才能关闭：

1. 后端、前端、移动端计划及本方案验收矩阵全部有真实测试证据；
2. Android 完成真实 ECS + 隔离私有 OSS 的历史/增量自动备份闭环；
3. “同步完成”时服务端不存在该设备尚未确认的任务；
4. 进程/设备重启、网络变化、会话/授权过期和重复请求均可自动或主动恢复；
5. 管理端范围未扩张且压力回归通过；
6. OpenAPI、数据库 migration、C ABI、Core 契约、Android 语义 ID 和运行文档同步；
7. `stage04-v1` 契约测试通过并从 `BASELINED` 转为 `FROZEN`；
8. 新建 `acceptance.md` 记录实际版本、命令、测试结果、真机/OSS 证据、已知限制和阶段 05 输入，不预填虚假完成结果。
