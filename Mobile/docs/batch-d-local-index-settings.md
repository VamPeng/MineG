# 批次 D 技术方案：前台本地索引与备份偏好

## 1. 设计结论

批次 D 采用一次性的前台扫描：C++ Core 决定扫描顺序并保存索引，Android 只通过 `MediaSourcePort` 读取系统相册。扫描执行上下文只存在内存中，进程结束即丢弃；下次调用重新从第一页开始。

> 验收记录（2026-08-02）：项目负责人已确认批次 D 完成，`stage02-v2` 2.1.0 已冻结。自动上传、后台调度与故障恢复仍不属于本批次。

SQLite 保存的是可查询的业务结果和设置，不保存“上次执行到哪一步”。WorkManager、后台调度、上传队列和 operation 恢复均不属于本方案。

上位需求：[批次 D 需求](../../Requirement/plans/02-keys-profile-local-media/batch-d-requirements.md)。

## 2. 分层职责

| 层 | 职责 | 不负责 |
| --- | --- | --- |
| Compose / ViewModel | 发起刷新、显示扫描中状态、订阅结果、分页展示 | 扫描循环、游标恢复、索引合并、设置持久化 |
| Android Bridge | 驱动当前前台调用，把 Effect 交给对应 Port | 后台唤醒、业务判断、自动重试 |
| `AndroidMediaSourcePort` | 查询权限、读取相册、按游标读取最多 500 条媒体 | 决定是否继续、写业务数据库、创建上传任务 |
| C++ Core | 权限门禁、分页顺序、批次校验、索引写入、最终切换、设置读写、Query/Event | 持有 Android URI 对象、调用 MediaStore、安排后台时间 |
| SQLite | 保存设置、已完成索引和构建中的候选索引数据 | 保存运行栈、下一 Effect、重试次数、Worker ID |

## 3. 对外契约

实施时将 `stage02-v2` 从当前批次 C 基线扩展为兼容的 `2.1.0`，不修改冻结的 `stage02-v1`。

### 3.1 Core Command

| 名称 | 类型 | 输入 | 结果 |
| --- | --- | --- | --- |
| `StartForegroundLocalScan` | 前台流程 | `userId` | 完成摘要或 `PERMISSION_REQUIRED` |
| `UpdateBackupSettings` | 短命令 | `userId`、`deviceInstallationId`、两个开关 | 最新 `BackupSettingsSnapshot` |

`StartForegroundLocalScan` 虽然通过现有 start/resume Bridge 逐步交换 Effect，但它不是可恢复业务任务。其恢复策略固定为 `RESTART_FROM_BEGINNING`，App 启动时不得恢复该类型的旧 operation。

`deviceInstallationId` 使用批次 B 已建立的当前账号上下文提供的稳定不透明值；批次 D 不新增一次 SecureStore 读取流程，也不解释该值的内容。

### 3.2 Core Query

- `GetBackupSettings(userId, deviceInstallationId)`；
- `GetLocalLibrarySummary(userId)`；
- `ListLocalAlbums(userId, cursor, limit)`；
- `ListLocalMedia(userId, albumRef, cursor, limit)`。

### 3.3 Core Event

- `BackupSettingsChanged`：设置事务提交后发布；
- `LocalLibraryIndexChanged`：新索引成为当前索引后发布；
- `LocalScanProgressChanged`：仅用于当前进程 UI，可丢弃，不写 SQLite。

### 3.4 Platform Effect

批次 D 只使用 `MediaSourceEffect`：

- `getPermissionSnapshot`；
- `listAlbums`；
- `listMedia(cursor, limit)`。

不使用 `BackgroundSchedulerEffect`、`TransportEffect`、`SecureStoreEffect` 或上传相关 Effect。

## 4. 内存执行模型

### 4.1 C++ Core

每次扫描只创建一个临时上下文：

```text
ForegroundScanContext
  operationId        本次前台调用标识
  userId             当前账号
  generationId       本次候选索引标识
  phase              CHECK_PERMISSION / READ_ALBUMS / READ_MEDIA / FINALIZE
  nextCursor          下一页 MediaStore 游标，仅在内存中
  indexedCount        当前进程内的展示计数
```

`ForegroundScanContext` 不序列化进 `core_operations`，也不参与 `recoverOperations()`。进程结束后这些字段全部消失。

`generationId` 不是断点恢复 ID。它只用于区分“当前可见的完整索引”和“正在构建的候选索引”。

### 4.2 Android

Android 只保留可丢弃的页面状态：

```text
LocalLibraryUiState
  isScanning
  scannedCount
  permissionPromptVisible
  selectedAlbumRef
  visiblePage
```

`CoreOperationRunner` 只驱动当前协程中的 Effect。Android 不保存业务游标、不复制全量媒体列表、不在 SharedPreferences 记录扫描状态，也不创建 Worker。

## 5. SQLite 数据模型

### 5.1 `backup_settings`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `user_id` | TEXT | 账号 ID |
| `device_installation_id` | TEXT | 当前 App 安装实例 ID |
| `auto_backup_enabled` | INTEGER | 是否允许阶段 04 自动创建备份工作 |
| `allow_cellular_backup` | INTEGER | 阶段 04 是否允许移动网络 |
| `updated_at` | TEXT | 最近修改时间 |

主键为 `(user_id, device_installation_id)`。不存在记录时，两个开关都返回 `false`。

冻结的 `stage02-v1` 和历史 SQLite v3 migration 仍记录旧的“默认开启”基线，不回写历史文件；当前运行默认由 Core 的缺省 Query 和即将登记的 `stage02-v2 2.1.0` 定义为 `false`。已有用户明确保存的设置保持原值，不因本次默认值变化被覆盖。

### 5.2 `local_library_active`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `user_id` | TEXT PRIMARY KEY | 账号 ID |
| `generation_id` | TEXT | 当前可见的完整索引代次 |
| `indexed_count` | INTEGER | 当前索引媒体数量 |
| `completed_at` | TEXT | 完成时间 |

页面查询必须先读取此表，再按 `generation_id` 查询索引，不能读取其他候选代次。

### 5.3 `local_albums`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `user_id` | TEXT | 账号 ID |
| `generation_id` | TEXT | 所属索引代次 |
| `platform_album_ref` | TEXT | 平台相册稳定引用 |
| `name` | TEXT | 相册名称 |

主键为 `(user_id, generation_id, platform_album_ref)`。

### 5.4 `local_media`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `user_id` | TEXT | 账号 ID |
| `generation_id` | TEXT | 所属索引代次 |
| `platform_asset_ref` | TEXT | 平台媒体稳定引用 |
| `media_type` | TEXT | PHOTO / VIDEO / GIF / LIVE_PHOTO / DYNAMIC |
| `mime_type` | TEXT | MIME 类型 |
| `width`、`height` | INTEGER | 像素尺寸 |
| `duration_ms` | INTEGER NULL | 视频时长 |
| `captured_at` | TEXT | 拍摄时间 |
| `modified_at` | TEXT | 平台修改时间 |
| `modified_version` | INTEGER | 平台排序/变更版本 |
| `content_version` | TEXT | 内容版本 |
| `availability` | TEXT | 本地资源可用状态 |
| `thumbnail_uri` | TEXT NULL | 可重建的缩略图引用 |

主键为 `(user_id, generation_id, platform_asset_ref)`；索引排序为 `captured_at DESC, platform_asset_ref DESC`。

### 5.5 `local_media_albums`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `user_id` | TEXT | 账号 ID |
| `generation_id` | TEXT | 所属索引代次 |
| `platform_asset_ref` | TEXT | 媒体引用 |
| `platform_album_ref` | TEXT | 相册引用 |

主键包含以上四个字段，以支持同一媒体属于多个相册。

### 5.6 不再作为批次 D 持久状态的字段

下列内容不应保留为恢复依据：

- `cursor_modified_version`、`cursor_asset_ref`；
- `SCANNING`、`BLOCKED_PERMISSION` 等执行状态；
- operation sequence、Effect payload、重试次数；
- Worker 名称、系统任务 ID、下一运行时间。

旧 `local_scan_state` 迁移时只可用于读取最后一次 `COMPLETE` 摘要；上线新模型后不再写入。

## 6. 执行过程

1. UI 调用 `StartForegroundLocalScan`。
2. Core 产生 `MediaSourceEffect.getPermissionSnapshot`。
3. 权限不是 `FULL`：返回 `PERMISSION_REQUIRED`，不修改当前索引。
4. 权限为 `FULL`：Core 创建新 `generationId`，删除该账号没有被 `local_library_active` 引用的旧候选代次。
5. Core 请求相册列表并写入新代次。
6. Core 从空游标开始请求媒体，每页上限 500；收到一页后校验并在单个事务中写入媒体和相册关系。
7. `nextCursor` 不为空时继续请求下一页。
8. 最后一页完成后，Core 在一个短事务中更新 `local_library_active` 指向新代次。
9. Core 发布 `LocalLibraryIndexChanged`；UI 重新执行分页 Query。
10. Core 异步或在下一次扫描开始前删除不再被引用的旧代次。

这个流程允许 SQLite 暂存候选索引行，但不保存如何继续执行。若进程结束，候选行不会对页面可见；下一次调用清理后从空游标重建。

## 7. 设置过程

1. UI 读取 `GetBackupSettings`；不存在记录时 Core 返回默认值 `false / false`。
2. 用户切换任一开关，UI 提交完整的新设置。
3. Core 以 `(userId, deviceInstallationId)` 为主键执行 upsert。
4. 事务提交后返回新 Snapshot 并发布 `BackupSettingsChanged`。
5. Android 只更新展示，不调用调度器，也不自动开始扫描或上传。

## 8. 必须处理与明确不处理

必须处理：

- 数据库事务失败时不得切换当前索引；
- 单页超过 500、账号不匹配或媒体字段非法时拒绝该页；
- 当前账号变化后旧调用结果不得切换新账号的索引；
- 10 万条数据始终分页读取和分页写入。

明确不处理：

- 恢复被杀进程中的扫描；
- 从已保存游标续扫；
- 后台继续、系统唤醒、自动重试和退避；
- 网络状态、上传条件和备份任务状态。

## 9. 实施拆分

1. 将 `stage02-v2` 扩展到 `2.1.0`，登记上述 Command、Query、Event 和 `MediaSourceEffect` action；
2. 在 Core 增加非恢复型 `ForegroundScanContext`，把 Kotlin 扫描循环迁入 Core；
3. 增加 SQLite migration，用 active generation 替代持久化扫描游标；
4. Android `AndroidMediaSourcePort` 保留纯系统读取，ViewModel 改为只调用 Core Client；
5. 删除 `MarkLocalScanBlocked`、Kotlin generation/恢复判断和自动备份开关对本地浏览扫描的阻断；
6. 增加 Core 主机测试、Android Port 契约测试和 10 万条分页测试；
7. 切换生产入口后删除 `AndroidAccountClient` 中的批次 D 过渡实现。

## 10. 完成定义

- 本地扫描的“是否开始、请求下一页、何时切换索引”只在 Core；
- Android 只提供权限和 MediaStore 原始结果；
- SQL 中没有可恢复的扫描步骤；
- App 重启后只读取最后完成的索引，新扫描始终从第一页开始；
- 两个备份开关只保存偏好，不产生后台或上传行为；
- 批次 D 全链路不依赖 WorkManager。
