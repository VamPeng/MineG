# 阶段 05 技术方案：私人浏览、原文件保存与逻辑删除

> 方案状态：`BASELINED`
> 核定日期：2026-08-03
> 交付范围：B4、B6 逻辑删除子集、M5、管理端权限隔离回归
> 上位基线：[产品需求](../../product-requirements.md)、[功能需求 F-09/F-10/F-13](../../functional-requirements.md)、[技术需求](../../technical-requirements.md)、[三端一致性契约](../../../Mobile/three-platform-consistency-contract.md)
> 执行计划：[后端](./backend.md)、[管理端](./frontend.md)、[移动端](./mobile.md)

## 1. 阶段结论

阶段 05 基于阶段 04 已完成的原媒体上传、本人列表、账号隔离游标和持久队列，按纵向闭环补齐以下能力：

1. 服务端提供本人私人媒体列表、详情、短期读取授权和逻辑删除；
2. C++ Core 唯一拥有列表分页、详情、媒体读取、下载保存、下载回执和删除状态；
3. Android 只负责 Compose 展示、受控文件与网络传输、媒体播放和 MediaStore 写入；
4. 原文件、缩略图和预览不做客户端应用层加密，不生成 Media Key、envelope 或密文副本；
5. 注册资源使用 HTTPS/TLS、私有 OSS、5 分钟读取授权、字节长度和 SHA-256 完整性校验；照片/GIF 的 OSS 动态缩略图另按签名、响应图片类型和 5 MiB 输出上限校验；
6. 删除只改变 PostgreSQL 业务状态，首次 `trashed_at` 不可变，同时关闭分享并写审计，不移动或删除 OSS 对象；
7. 阶段 05 只提供删除入口，回收站列表、恢复和永久清理仍属于阶段 06。

方案使用新的 `stage05-v1` 移动契约。Android 阶段验收通过后，该契约由 `BASELINED` 转为 `FROZEN`，供 iOS 和 HarmonyOS 后续复用。

## 2. 开工门禁与当前基线

### 2.1 已满足输入

- 阶段 04 状态为 `COMPLETED_WITH_RETAINED_NOTES`，上传、去重、新媒体入队和 App 重进恢复主流程已完成；
- `stage03-v2` 原媒体 HTTPS 上传链路已冻结，`stage04-v1` 队列契约保持 `BASELINED`；
- 服务端已有 `GET /api/v1/private/media`、所有者隔离、HMAC seek cursor 和完成媒体查询；
- PostgreSQL 已有 `media`、`media_resources`、上传会话、资源长度和 SHA-256；
- Core 已有账号隔离的私人摘要缓存、备份任务与 `server_media_id` 映射；
- Core SQLite 已有 `download_receipts`，阶段 04 扫描会排除有效下载回执对应的系统媒体。

阶段 04 的完整异常矩阵仍作为保留备注继续补证，不阻塞阶段 05 开工，也不得据此把 `stage04-v1` 提前标记为 `FROZEN`。

### 2.2 功能编码前必须落库

以下产物属于阶段 05 的 P0 门禁，必须先于页面和业务实现合入：

- `Mobile/contracts/stage05-v1.json`：模型、命令、查询、事件、错误、Effect action 和语义 ID；
- OpenAPI 3.1：列表增量、详情、读取授权、逻辑删除和新增 DTO；
- 服务端顺序 migration：媒体展示元数据、资源 MIME、分享最小表、回收站记录和删除幂等；
- Core 顺序 SQLite migration：私人分页缓存、资源清单、保存操作和下载回执增量；
- Go、C++、JNI/Kotlin 的契约测试骨架。

禁止先在 Kotlin 中实现私人媒体 Client、DTO 或列表状态，再反向补 Core 契约。

## 3. 范围与非范围

### 3.1 必须交付

- 私人三列网格：首次加载、空状态、内容、首次失败、继续加载和继续加载失败；
- 按拍摄时间倒序、账号与过滤条件绑定的稳定游标分页；
- 照片、GIF、视频、实况/动态媒体详情和安全降级；
- 缩略图、封面或预览的按需授权、受控下载、完整性校验和缓存失效；照片/GIF 可用 OSS 动态缩略图补齐仅有原图的历史数据；
- 本人原始资源集合下载、进度、失败分类、取消、重试、MediaStore 写入和下载回执；
- 重复保存检测，系统相册条目丢失后允许重新保存；
- 删除二次确认、幂等逻辑删除、分享关闭、审计和客户端缓存收敛；
- 其他用户、管理员、过期会话、篡改 ID 和删除并发的权限负测试；
- 真实 PostgreSQL、真实鉴权和隔离私有 OSS 的 Android 纵向验收。

### 3.2 明确不做

- 分享、取消分享、家庭列表和家庭详情；详情页本阶段不显示不可用的分享占位按钮；
- 回收站列表、恢复、永久清理 API 或永久清理 CLI；
- iOS、HarmonyOS 功能实现；本阶段只冻结两端以后必须复用的公共契约；
- 搜索、筛选、收藏、编辑地点、编辑媒体、系统分享、文件位置选择器；
- ECS API 服务端读取媒体正文、代理媒体、生成缩略图或视频转码；OSS 在已签名精确 GET 上执行即时图片缩放不属于服务端媒体处理；
- CDN、公开 Bucket、长期对象地址、长期云凭据或在线服务 OSS 删除权限；
- 客户端媒体加密、Media Key、key envelope、认证标签或 SQLCipher；
- 对阶段 04 已完成且仅有原资源的云媒体执行批量派生资源回填。

## 4. 关键技术决策

### 4.1 媒体安全基线

- 上传、预览和原文件保存均处理原始或客户端派生的普通私有媒体对象；不执行应用层加密或解密。
- ECS 只返回短期 GET 授权和资源清单，不代理媒体正文，不返回 OSS 对象键、Bucket、长期凭据或管理权限。
- 签名 URL、签名请求头和临时文件句柄只存在于当前 Core operation/PlatformEffect 生命周期，不写 PostgreSQL、SQLite、日志、错误响应、埋点或反馈。
- `ORIGINAL_RESOURCE` 在交给解码器、播放器或 SystemAlbumWriter 前必须核对最终字节长度与 SHA-256；不匹配时删除临时文件并失败关闭。`OSS_IMAGE_THUMBNAIL` 的输出摘要必然不同于源原图，因此只接受已签名 HTTPS、允许的位图响应类型（JPEG/PNG/GIF/WebP/BMP）和最多 5 MiB 的响应，且同样在受控临时文件中使用后删除。
- 已签发 URL 无法即时撤销。删除的线性化点之后不再签发新 URL，之前签发的 URL 最多在 5 分钟内自然失效。

### 4.2 原资源与派生资源

资源分为两组：

| 资源组 | 类型 | 用途 |
| --- | --- | --- |
| 原始集合 | `ORIGINAL`、`LIVE_PHOTO_VIDEO` | 本人保存；受支持照片/GIF 的 `ORIGINAL` 可作为 OSS 动态网格缩略图的源，但原图字节不会交给网格 |
| 派生集合 | `THUMBNAIL`、`VIDEO_COVER`、`PREVIEW`、`DYNAMIC_PREVIEW` | 网格、详情和兼容预览，不替代原文件 |

阶段 05 扩展既有上传资源清单，但不建立第二套上传服务：

- 新创建的 `stage05-v1` 上传任务仍使用 `/uploads`、分片上报和完成接口；
- `ORIGINAL` 始终必需，实况媒体的必要配对原资源也必须完整；
- 当前上传链路只登记 `ORIGINAL`（及实况照片必要配对资源），不额外生成或上传 `THUMBNAIL`、`VIDEO_COVER`、`PREVIEW`；服务端仍兼容这些将来可登记的派生类型；
- 受支持照片/GIF 的网格缩略图由 OSS 在 GET 时以最长边 512 px 动态缩放；生成的响应不落 OSS 对象。视频封面、视频预览和 HEIC/HEIF 预览尚无生成链路，显示中性占位；
- 派生资源可以降低展示尺寸，但原资源集合不得转码、降质或替换；
- 阶段 04 已完成且没有云端派生资源的照片/GIF：使用签名 OSS 动态缩略图；其他媒体同设备存在本地源时可使用受控本地预览，否则显示中性占位；不得由 ECS 服务端读取原文件补图；
- 私人列表和详情必须容忍派生资源缺失，原文件保存能力不受影响。

派生资源缺失不得阻塞原文件完成状态。阶段退出样本必须至少证明照片/GIF 的 OSS 动态缩略图、视频无封面占位和原文件保存各一条；未来生成视频封面/预览后再扩展相应样本。

### 4.3 展示名称与隐私元数据

- 服务端不保存或返回 GPS、EXIF 正文、设备绝对路径和平台相册 URI；
- 首版不上传原始文件名，Core 按媒体类型和设备本地时区生成展示用默认名称；
- 服务端只保存媒体类型、MIME、拍摄时间、可选宽高、可选时长、资源大小和摘要；
- 详情总大小由原始资源集合求和，派生资源大小不计入“原文件大小”。

### 4.4 读取授权用途

服务端使用固定用途，客户端不能把授权用途互换：

| 用途 | 变体 | 允许资源 |
| --- | --- | --- |
| `VIEW` | `THUMBNAIL` | 已登记缩略图或视频封面；仅照片/GIF 缺失时可签发 OSS 动态缩放原图 |
| `VIEW` | `DETAIL` | 预览、缩略图、封面；服务端按可用性排序选择 |
| `STREAM` | 无 | 已登记的 `PREVIEW` 或 `DYNAMIC_PREVIEW`，不允许原资源 |
| `DOWNLOAD` | 无 | 完整原始资源集合，只允许所有者 |

`DOWNLOAD` 必须返回完整原始集合，不能只返回实况照片中的一半资源。`VIEW`/`STREAM` 授权不能传给 SystemAlbumWriter，`DOWNLOAD` 授权不得出现在家庭接口。

## 5. 分层与运行模型

```text
Compose Screen / ViewModel
          │ Command / Query / Event
          v
CoreClient → C ABI → C++ Core → SQLite（列表、资源、保存与回执真相）
                          │
                          ├─ TransportEffect ───────> ECS API / 私有 OSS GET
                          ├─ FileEffect ────────────> 受控临时文件、长度、SHA-256、空间
                          ├─ MediaSourceEffect ─────> 本地源与派生展示资源
                          ├─ MediaPlaybackEffect ───> 已验证的本地资源句柄
                          └─ SystemAlbumEffect ─────> MediaStore 校验、写入、回滚

ECS media/trash service → PostgreSQL
          │
          └─ MediaReadObjects → OSS 5 分钟精确对象 GET 授权
```

### 5.1 C++ Core 独占职责

- API 路径、请求/响应 DTO、游标、错误映射、Token 刷新和重试判断；
- 私人列表排序、分页合并、账号隔离、详情和资源清单缓存；
- 读取用途选择、授权续签、下载进度、取消、完整性校验和临时文件生命周期；
- 下载回执检查、失效回执移除、完整资源集合写入条件；
- 删除命令、幂等结果合并、列表/详情缓存失效和领域事件；
- App 重启后的保存操作核对和安全恢复。

### 5.2 Android 平台层只执行原语

- HTTPS 请求和 OSS 字节下载，回传原始状态、响应头、进度和受控文件结果；
- MediaStore 本地源读取、派生图片/封面生成和系统相册写入；
- Coil/播放器消费已经由 Core 确认完整的本地展示句柄；
- Compose 把不可变领域快照转换为 `UiState`，不解析 API DTO、不保存游标、不直接删除列表项。

### 5.3 服务端职责

- 认证、所有权、完成状态、回收站状态、用途和资源集合校验；
- 生成详情/列表 DTO 和 HMAC seek cursor；
- 签发精确对象 GET 地址；
- 逻辑删除、关闭分享、幂等记录和审计事务；
- 不读取、缓存或返回媒体正文。

## 6. API 与 OpenAPI 增量

### 6.1 接口清单

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| `GET` | `/api/v1/private/media` | 本人完成且未删除媒体的稳定游标页 |
| `GET` | `/api/v1/private/media/{media_id}` | 本人媒体详情与无 URL 资源清单 |
| `POST` | `/api/v1/private/media/{media_id}/access` | 按用途签发 5 分钟精确对象读取授权 |
| `POST` | `/api/v1/private/media/{media_id}/trash` | 幂等移入回收站 |

既有 `/api/v1/media` 只保留兼容，不作为 M5 新功能路径。所有阶段 05 客户端 API 必须使用 `/private/media` 分组。

既有上传路径不新增分组，但 OpenAPI 增加 `stage05-v1` 协议增量：

- `CreateMediaUploadRequest.protocol_version` 接受 `stage05-v1`；旧 `stage03-v2/stage04-v1` 继续兼容；
- 顶层增加可空 `width`、`height`、`duration_ms`，禁止 GPS、文件路径和 EXIF 正文；
- `MediaResourcePlan` 增加必需 `mime_type`；`stage05-v1` 为每个资源分别记录 MIME；
- `stage05-v1` 的资源清单按媒体类型校验：所有媒体必须有 `ORIGINAL`，实况媒体必须有完整配对原资源，派生资源不得重复；
- 旧协议的 `ORIGINAL` MIME 从顶层 `mime_type` 兼容回填；服务端不得根据扩展名猜测安全类型；
- 派生资源和原始资源仍在同一上传会话完成核对；任何声明资源未完成时，媒体不能进入私人可见列表。

### 6.2 列表与详情 DTO

`PrivateMediaSummary` 至少包含：

```text
id
media_type
captured_at
created_at
duration_ms?            # 仅适用媒体
original_total_size
preview_resource?       # resource_id/type/mime_type/content_size/content_sha256；无 URL
```

`PrivateMediaPage` 固定为 `items` 和可空 `next_cursor`。第一页默认 50，允许 1～100；排序固定为 `captured_at DESC, id DESC`。游标由服务端 HMAC 签名并绑定 `owner_id`、过滤条件、排序版本和最后一项，不能跨账号或跨查询复用。

`PrivateMediaDetail` 至少包含：

```text
id
media_type
captured_at
created_at
width?
height?
duration_ms?
original_total_size
resources[]             # 不含 object_key 和签名 URL
```

每个资源描述只包含不透明 `resource_id`、`resource_type`、`mime_type`、`content_size` 和 unpadded Base64 SHA-256。默认展示名称由 Core 生成，不通过 API 返回设备文件名。

### 6.3 读取授权 DTO

请求：

```json
{
  "purpose": "VIEW",
  "variant": "THUMBNAIL"
}
```

- `purpose` 为 `VIEW | STREAM | DOWNLOAD`；
- `variant` 只允许在 `VIEW` 时使用，值为 `THUMBNAIL | DETAIL`；
- 客户端不提交对象键和任意资源 ID，服务端根据登记资源和用途选择允许集合。

响应：

```text
media_id
purpose
variant?
issued_at
expires_at
resources[]
  resource_id
  resource_type
  mime_type
  content_size
  content_sha256
  supports_range
  delivery_mode          # ORIGINAL_RESOURCE | OSS_IMAGE_THUMBNAIL
  maximum_output_size?   # 仅 OSS_IMAGE_THUMBNAIL，最大 5 MiB
  grant
    url
    method = GET
    headers
    expires_at
```

`POST /access` 不缓存、不持久化签名 URL，也不使用 `Idempotency-Key`；重复调用的语义是重新鉴权并签发新地址。

#### OSS 动态图片缩略图

历史和当前上传只登记 `ORIGINAL` 的照片/GIF，无需补传派生文件。对 `VIEW + THUMBNAIL`，若没有已登记的 `THUMBNAIL` 或 `VIDEO_COVER`，服务端可以只为 `PHOTO | GIF` 的受支持图片原图签发 `delivery_mode=OSS_IMAGE_THUMBNAIL`：精确 GET 在签名时携带 `x-oss-process=image/resize,m_lfit,l_512`。服务端不读取、转发或缓存媒体字节，OSS 也不保存派生对象。

动态处理后的响应字节长度和 SHA-256 不等于登记原图；因此该模式的 `content_size/content_sha256` 仅描述源对象，Core 不以它们验证处理结果。Core 仍要求 HTTPS、短时精确签名、JPEG/PNG/GIF/WebP/BMP 响应类型以及不超过 5 MiB 的输出，并仅写入任务临时文件。`VIDEO`、`LIVE_PHOTO`、HEIC/HEIF 或不受 OSS IMG 支持的源文件不走此例外，继续等待 `VIDEO_COVER`/派生预览或显示占位。

### 6.4 逻辑删除 DTO

请求体为空对象，必须携带 `Idempotency-Key`。成功响应：

```json
{
  "media_id": "opaque-id",
  "outcome": "TRASHED",
  "trashed_at": "2026-08-03T00:00:00Z"
}
```

`outcome` 为 `TRASHED | ALREADY_TRASHED`。同一媒体重复删除返回首次 `trashed_at`，不能刷新时间；同一个 Idempotency-Key 配不同媒体或请求摘要返回 `409 IDEMPOTENCY_KEY_REUSED`。

不存在、非本人、未完成或已经永久清理的媒体统一返回 `404 MEDIA_NOT_FOUND`，避免通过错误差异枚举其他用户媒体。本人已在回收站的媒体只允许删除接口返回幂等结果，列表、详情和访问接口均不再返回。

### 6.5 稳定错误码

| 错误码 | 层/HTTP | 行为 |
| --- | --- | --- |
| `MEDIA_NOT_FOUND` | API 404 | 不存在、非本人或不可见，退出详情并失效缓存 |
| `MEDIA_RESOURCE_UNAVAILABLE` | API 409 | 对应查看资源缺失；网格/详情显示占位，保存缺原始集合则阻塞 |
| `MEDIA_ACCESS_INVALID` | API 422 | 用途或变体非法，不重试 |
| `IDEMPOTENCY_KEY_REUSED` | API 409 | 删除键复用到不同请求，不重试 |
| `OBJECT_STORAGE_UNAVAILABLE` | API 503 | 保留页面状态，有限退避后允许重试 |
| `MEDIA_ACCESS_EXPIRED` | Core | OSS 401/403 或本地过期；重新调用 `/access` 后续传一次 |
| `MEDIA_INTEGRITY_FAILED` | Core | 删除临时文件，不展示、不播放、不写系统相册 |
| `DEVICE_SPACE_INSUFFICIENT` | Core/Port | 下载前或写入时空间不足，提示释放空间 |
| `SYSTEM_ALBUM_PERMISSION_REQUIRED` | Core/Port | 请求/恢复系统权限，不把下载判为成功 |
| `MEDIA_SAVE_UNSUPPORTED` | Core/Port | 平台不能完整写入资源集合，不产生部分保存 |
| `MEDIA_SAVE_CANCELLED` | Core | 安全停止，清理或保留可验证续传文件，不显示失败成功 |

Token 失效继续使用统一会话刷新契约。Core 只对一次可判定为授权过期的 OSS 失败自动续签；所有权或回收站拒绝不得循环重试。

## 7. 服务端数据与事务方案

### 7.1 Go 模块

新增或收敛为以下边界：

```text
internal/media/        私人列表、详情、资源选择和访问授权
internal/trash/        删除幂等、逻辑删除和分享关闭
internal/objectstore/  精确对象读取授权，不决定业务权限
internal/audit/        删除审计写入
```

阶段 04 `internal/upload` 继续只负责上传。M5 新功能不得继续堆入 upload Handler/Service；HTTP Handler 不直接写 SQL，对象存储适配器不查询所有权。

### 7.2 PostgreSQL migration

使用阶段 05 开工时下一个可用顺序号，当前预期为 `00010_private_media_access_trash.sql`，禁止修改 00001～00009。migration 包含：

1. `upload_sessions`、`media` 增加可空 `width`、`height`、`duration_ms`，使用正数约束；
2. `media_resources` 增加 `mime_type`，旧 `ORIGINAL` 行从 `media.mime_type` 回填；已有派生行无法证明类型时使用受限兼容值并只显示占位，不通过扩展名猜测；
3. `media` 增加 `access_version bigint NOT NULL DEFAULT 1`，删除和后续分享可见性变化时递增；
4. 创建 `shares` 最小表，包含 `media_id`、`owner_id`、`state`、`version`、分享/关闭时间；本阶段不提供创建分享接口；
5. 创建 `trash_records`，以 `media_id` 唯一，包含 `owner_id`、首次 `trashed_at`、未来使用的 `restored_at`、`purged_at`；
6. 创建 `trash_requests`，主键为 `(owner_id, idempotency_key)`，保存 `media_id`、请求摘要、结果与首次时间；
7. 保留现有 `(owner_id, captured_at DESC, id DESC)` 完成媒体索引；`trash_records(media_id)` 唯一索引用于 `NOT EXISTS` 过滤；
8. migration 从空库和 00009 升级均测试，已执行环境不回写历史 migration。

服务端活动私人媒体定义固定为：

```sql
media.owner_id = current_user
AND media.upload_status = 'COMPLETED'
AND NOT EXISTS (
  SELECT 1 FROM trash_records
  WHERE trash_records.media_id = media.id
    AND trash_records.restored_at IS NULL
    AND trash_records.purged_at IS NULL
)
```

### 7.3 删除事务

删除按以下顺序在同一 PostgreSQL 事务执行：

1. 校验并锁定 `(owner_id, idempotency_key)`；相同键相同请求直接重放，相同键不同请求冲突；
2. `SELECT media ... FOR UPDATE`，强制本人和 `COMPLETED`；
3. 若已有活动 `trash_records`，返回 `ALREADY_TRASHED` 和原 `trashed_at`；
4. 否则插入首次 `trash_records`；数据库约束和条件 SQL禁止覆盖活动记录时间；
5. 把现有 `shares.state` 条件更新为 `INACTIVE`，递增分享版本并记录关闭时间；
6. 递增 `media.access_version`；
7. 写 `audit_events(action=MEDIA_TRASH, result=SUCCESS|REPLAY)`，metadata 只记录允许的状态，不含对象键或 URL；
8. 写入幂等结果并提交。

任何一步失败都回滚，不能出现已从私人列表消失但分享仍有效、或已关闭分享但没有删除记录的半状态。事务不调用 OSS，也不移动、复制或删除对象。

### 7.4 访问与删除并发

读取授权使用“双检查 + 版本”线性化：

1. 查询本人活动媒体、资源集合和 `access_version`；
2. 对允许对象生成 5 分钟签名 GET；
3. 返回前在短事务内锁定媒体并重新检查所有权、活动回收站状态和 `access_version`；
4. 版本变化或已删除时丢弃未返回的签名结果并返回 `MEDIA_NOT_FOUND`；
5. 删除事务与最终检查使用兼容的行锁，保证授权要么线性化在删除之前，要么在删除之后被拒绝。

签名生成不持有长数据库事务。授权线性化在删除之前时，即使客户端稍后收到响应，该地址仍属于删除前已签发地址，只能等待 5 分钟自然过期。

## 8. OSS 读取授权

### 8.1 对象存储接口

在上传 `MediaObjects` 之外增加只读接口，避免给上传适配器附加删除或列举能力：

```text
MediaReadObjects.issueMediaRead(objectKey, lifetime) -> ObjectGrant
MediaReadObjects.issueMediaImagePreview(objectKey, lifetime) -> ObjectGrant
```

每个输入由业务服务从数据库构造，包含精确对象键、期望长度、SHA-256 和用途。动态缩略图接口只接受受支持图片原图，必须在签名前加入固定 `x-oss-process=image/resize,m_lfit,l_512`。适配器只验证键前缀、GET 方法、有效期与临时凭据寿命，不决定账号和用途。

### 8.2 授权约束

- 固定有效期 5 分钟，不允许客户端指定；
- 精确单对象 GET，不允许 ListBucket、PutObject、DeleteObject、通配前缀或覆盖；
- `Cache-Control` 与客户端缓存策略使用私有、短期、可丢弃语义；
- 视频/大文件允许 Range 续传，但 Core 最终仍对完整临时文件执行长度和 SHA-256 校验；OSS 动态缩略图禁止作为 Range/保存资源，并以 5 MiB 上限替代源摘要校验；
- ECS RAM Role 只具备业务所需的读/上传权限，在线身份继续没有已完成对象删除权限；
- 日志只记录 media/resource 不透明 ID、purpose、结果、request ID 和耗时，不记录 URL、签名头或对象键。

## 9. C++ Core 公共契约

### 9.1 `stage05-v1` 模型

至少登记：

```text
PrivateMediaSummary
PrivateMediaPage
PrivateMediaDetail
PrivateMediaResource
PrivateMediaAccessPurpose / PrivateMediaViewVariant
PrivateMediaViewState
PrivateMediaSaveState / PrivateMediaSaveFailure
PrivateMediaTrashResult
DownloadReceipt
```

公共模型不包含 Android URI、OSS 对象键、永久 URL、Compose 状态或平台播放器对象。展示句柄和临时文件句柄均为短生命周期不透明值。

### 9.2 Command、Query 与 Event

| 类型 | 名称 | 语义 |
| --- | --- | --- |
| Command | `RefreshPrivateMedia` | 丢弃当前游标并加载第一页 |
| Command | `LoadMorePrivateMedia` | 使用 Core 保存的下一游标加载后续页 |
| Command | `GetPrivateMediaDetail` | 获取详情并刷新资源清单缓存 |
| Command | `OpenPrivateMedia` | 按 `VIEW` 变体重新鉴权、校验并打开临时展示句柄 |
| Command | `ClosePrivateMedia` | 关闭展示句柄并删除对应受控临时文件 |
| Command | `SavePrivateMediaToSystemAlbum` | 检查回执并保存完整原资源集合 |
| Command | `CancelPrivateMediaSave` | 取消当前保存，不产生成功回执 |
| Command | `TrashPrivateMedia` | 提交真实删除命令并合并结果 |
| Query | `GetPrivateMediaPage` | Core 列表、分页和加载状态快照 |
| Query | `GetPrivateMediaDetail` | 已校验的详情与资源清单快照 |
| Query | `GetPrivateMediaSaveOperation` | 保存资源、失败和回执状态 |
| Event | `PrivateMediaPageChanged` | 刷新、追加、删除或账号切换 |
| Event | `PrivateMediaDetailChanged` | 详情或预览状态变化 |
| Event | `PrivateMediaSaveChanged` | 下载、校验、写入与结果变化 |
| Event | `PrivateMediaTrashed` | 删除成功并携带 media ID/时间 |

对外公共方法映射为 `refreshPrivateMedia`、`loadMorePrivateMedia`、`getPrivateMediaDetail`、`openPrivateMedia`、`closePrivateMedia`、`savePrivateMediaToSystemAlbum` 和 `trashPrivateMedia`；C ABI 命令名称、JSON 版本和资源释放必须写入契约清单。

### 9.3 Effect action

| Effect | action | 平台返回原语 |
| --- | --- | --- |
| `TransportEffect` | `sendApiRequest` | 原始 HTTP 状态、头、正文 |
| `TransportEffect` | `downloadObject` | 流式字节、最终长度、SHA-256 和响应 Content-Type；动态缩略图还带最大字节上限 |
| `FileEffect` | `getAvailableSpace` | 可用字节 |
| `FileEffect` | `createTaskTempFile` | 受控临时文件路径 |
| `FileEffect` | `deleteTempFile` | 删除结果 |
| `MediaPlaybackEffect` | `openVerifiedMedia` / `closeVerifiedMedia` | 已校验文件的展示句柄 / 关闭结果 |
| `SystemAlbumEffect` | `isSystemAlbumEntryPresent` | 已有回执对应系统条目是否仍存在 |
| `SystemAlbumEffect` | `writeVerifiedMedia` | 单个已校验原资源写入后的平台 asset ref |

平台不得比较期望摘要、选择授权用途、决定续签、合并进度、生成幂等键或写下载回执。

## 10. Core SQLite 与状态机

### 10.1 SQLite migration

使用下一个顺序 migration，当前预期为 `013_private_media_stage05.sql`。方案采用新版本表迁移旧摘要，不修改已发布 migration：

- `private_media_items_v2`：账号、媒体、展示元数据、预览资源摘要和排序字段；
- `private_media_page_state_v2`：账号、`next_cursor`、首页刷新时间和列表版本；
- `private_media_resources`：账号、媒体、资源 ID、类型、MIME、长度、SHA-256；
- `private_media_save_operations`：operation ID、账号、媒体、状态、用途、总字节、完成字节、失败码和更新时间；
- `private_media_save_resources`：每个原资源的临时文件 token、续传 offset、期望/实际摘要和状态；
- `download_receipts` 增加 `content_revision`、资源集合摘要和更新时间，仍以 `(user_id, cloud_media_id)` 唯一。

旧 `private_media_snapshots` 只迁移可以证明字段语义一致的摘要，迁移完成后由新列表首次刷新补齐资源。所有表按 `user_id` 隔离；退出登录删除当前账号签名授权、临时句柄和内存快照，持久业务缓存按既有账号清理策略处理。

SQLite 不保存对象 URL、签名头、Token、媒体正文或平台可直接打开的长期路径。

### 10.2 列表状态

```text
INITIAL
  -> LOADING_FIRST
      -> EMPTY
      -> CONTENT
      -> FIRST_LOAD_FAILED
CONTENT
  -> LOADING_NEXT
      -> CONTENT
      -> NEXT_LOAD_FAILED
  -> REFRESHING
      -> CONTENT | EMPTY | REFRESH_FAILED_WITH_CONTENT
```

- 首次失败和继续加载失败不能互相覆盖；
- 追加页在 SQLite 事务内按 media ID 去重并更新 cursor；
- 刷新从空 cursor 开始，成功后原子替换可见页；失败保留旧内容；
- 账号切换先发布空快照，再加载对应账号缓存，禁止短暂显示前账号媒体。

### 10.3 查看状态

```text
IDLE
  -> AUTHORIZING
  -> DOWNLOADING
  -> VERIFYING
  -> READY
  -> ERROR
```

只有 `READY` 的本地句柄可以交给图片解码器或播放器。注册资源授权过期时清除旧 URL，重新调用 `/access` 并从受控文件已确认 offset 续传；最终完整文件重新计算 SHA-256。OSS 动态缩略图不续传，也不比较源 SHA-256；它必须重新获取签名 URL 并重下不超过 5 MiB 的图片响应。取消、详情退出和失败按缓存策略删除临时文件。

### 10.4 保存状态

```text
IDLE
  -> CHECKING_RECEIPT
      -> ALREADY_SAVED
      -> AUTHORIZING
  -> CHECKING_SPACE
  -> DOWNLOADING
  -> VERIFYING
  -> WRITING_SYSTEM_ALBUM
  -> SUCCESS
  -> FAILED | CANCELLED
```

规则：

- 命中下载回执后先由 `validateSavedAsset` 检查系统条目，媒体仍可访问且条目存在才进入 `ALREADY_SAVED`；
- 回执条目不存在时删除失效回执并允许重新保存；
- 下载前按完整原始集合大小加安全余量检查空间；多资源逐项下载，但全部验证后才能写系统相册；
- OSS 过期授权重新鉴权；所有权/删除拒绝立即失败并退出详情；
- App 被回收后清除签名 URL，从 SQLite operation 和受控临时文件重新核对；无法证明安全时删除临时文件并以可重试中断结束；
- SystemAlbumWriter 使用待发布条目写入，任一资源失败时回滚本次创建的全部条目；
- 只有平台返回完整成功和稳定 asset ref 后，Core 才在同一 SQLite 事务写下载回执并发布 `SUCCESS`；
- 完成、取消和不可恢复失败后清理原文件临时副本，不产生第二份长期明文。

### 10.5 删除状态

```text
IDLE -> CONFIRMING -> SUBMITTING -> SUCCESS | FAILED
```

- 确认弹窗属于 UI 临时状态；幂等键、请求、结果和缓存收敛属于 Core；
- `TRASHED` 与 `ALREADY_TRASHED` 都作为成功收敛；
- 成功后在一个 SQLite 事务删除私人列表/详情/资源缓存和可发现入口，停止相关查看/保存 operation，并发布 `PrivateMediaTrashed`；
- 不删除 `local_media`、设备系统媒体或有效下载回执指向的本地媒体；
- 已经进入 SystemAlbumWriter 的竞态结果由 Core 按服务端删除线性化点处理：删除先确认则不再提交写入，写入已由系统确认则保留本地结果但云端媒体仍从私人空间消失。

## 11. Android 实现

### 11.1 页面和语义 ID

页面 ID 沿用 `private.list` 和 `private.detail`，至少登记：

```text
private.list.grid
private.list.item
private.list.empty
private.list.retry
private.list.loadMoreRetry
private.detail.media
private.detail.metadata
private.detail.download
private.detail.delete
private.detail.confirmDelete
private.detail.saveProgress
private.detail.saveCancel
private.detail.saveRetry
```

页面由 Core 快照驱动。网格只对进入视口的媒体请求缩略图授权，并使用有界并发；快速滚出视口可以取消尚未需要的查看 operation。UI 不持有 next cursor，也不通过本地列表删除模拟服务端成功。

### 11.2 媒体展示

- 照片：已登记预览完整验证后交给图片解码器；没有派生图时使用 OSS 动态缩略图的签名/类型/大小验证；
- GIF：同样优先已登记预览，受支持源文件可使用 OSS 动态缩略图；没有预览时显示占位并保留原文件保存；
- 视频：完整验证客户端派生视频预览后播放；没有预览时显示占位并保留原文件保存，不把原资源或未经完整性校验的签名 URL 交给播放器；
- 实况/动态媒体：平台原生能力可用时传完整验证资源集合，否则显示静态预览；
- 不可预览格式：显示中性文件类型占位，仍保留本人原文件保存；
- 详情不显示 GPS、对象键、摘要或内部授权信息。

### 11.3 MediaStore 写入

- API 29+ 使用 MediaStore 待发布写入语义；平台适配器不能把插入成功等同于业务成功；
- MIME、相对目录和展示名由公共写入描述决定，不向用户开放文件位置选择；
- 实况/动态媒体只有在 Android 能完整保存资源组成时才成功，否则在任何可见条目发布前返回 `MEDIA_SAVE_UNSUPPORTED`；
- 失败时清理由本次 operation 创建的待发布条目；已有系统媒体不受影响；
- 回传 Core 的 `platform_asset_ref` 只用于下载回执和后续扫描排重。

### 11.4 传输并发

- 阶段 04 全局最多 4 个对象传输的上限继续有效；
- 用户主动查看/保存可优先取得空闲 slot，但不取消正在上传的分片；
- 同一详情同时最多 2 个资源下载，多资源原文件保存按资源顺序推进；
- ViewModel 生命周期取消只通知 Core，是否保留可恢复下载由 Core 状态机决定。

## 12. 管理端隔离

管理端不增加路由、菜单、类型驱动页面或调试入口。必须验证：

- 管理员 Cookie 请求私人列表、详情、访问和删除全部拒绝；
- 普通 Bearer Token 不能调用管理员接口；
- 管理端错误展示、source map 和日志不包含签名 URL、资源清单摘要、对象键或内部拒绝原因；
- OpenAPI DTO 扩展不得自动生成可枚举用户媒体的页面或菜单。

## 13. 实施批次

阶段 05 仍按纵向闭环交付，不先一次性完成所有后端接口：

### P0：契约和迁移门禁

- 创建 `stage05-v1`、OpenAPI DTO、服务端/Core migration 和契约测试骨架；
- 修正所有旧密文、envelope 和解密表述；
- 确认阶段 04 回归测试继续通过。

### P1：列表与详情闭环

- 服务端完成活动私人媒体查询、详情 DTO 和资源清单；
- Core 完成列表分页/详情缓存和账号隔离；
- Android 完成真实加载、空、首错、续页错和不可预览占位。

### P2：查看授权与预览闭环

- 服务端完成用途校验、OSS 读取适配和访问/删除并发保护；
- Core 完成授权续签、受控下载和完整性校验；
- Android 完成照片/GIF/视频代表样本与地址过期回归。

### P3：原文件保存闭环

- Core 完成回执检查、空间、下载、校验、系统写入、回滚和恢复；
- Android MediaStore Port 完成单资源与多资源验证；
- 1 GiB 视频、重复保存、权限撤销和空间不足通过。

### P4：逻辑删除闭环

- 服务端完成幂等删除、分享关闭、审计和并发测试；
- Core/Android 完成确认、真实结果、缓存收敛和保存竞态；
- 证明设备本地媒体和 OSS 对象均未被删除。

### P5：环境验收与冻结

- 使用真实 PostgreSQL、鉴权、隔离私有 OSS 和 Android 真机完成验收矩阵；
- 管理端权限负测试和日志敏感数据扫描通过；
- 生成阶段 05 验收记录，将 `stage05-v1` 从 `BASELINED` 转为 `FROZEN`。

## 14. 测试与验收矩阵

### 14.1 服务端/PostgreSQL/OSS

- 列表/详情只返回本人、完成、未删除媒体；其他用户和管理员不能通过改 ID 获取信息；
- 游标签名、账号绑定、过滤绑定、排序稳定、删除后的继续分页正确；
- `VIEW`/`STREAM`/`DOWNLOAD` 资源集合严格，非法变体拒绝；
- URL 为精确 GET 且 5 分钟失效，不返回对象键或长期凭据；
- 删除与访问并发可线性化，删除后不再签发新地址；
- 重复删除不修改首次 `trashed_at`，幂等键复用冲突正确；
- 删除、分享关闭、access version 和审计同事务回滚/提交；
- OSS/数据库/日志无媒体正文、完整签名 URL；在线身份无已完成对象删除权限；
- migration 从空库和 00009 升级通过，sqlc/OpenAPI breaking check 通过。

### 14.2 C++ Core

- 首次/继续分页、刷新失败保留旧内容、重复项合并和账号切换隔离；
- 相同命令和 EffectResult 得到稳定事件、错误和 SQLite 状态；
- 授权过期续签、Range 恢复、注册资源长度/SHA-256 损坏失败关闭，以及 OSS 动态缩略图的签名参数、图片类型和 5 MiB 上限；
- 多资源未全部验证时不能进入 SystemAlbumWriter；
- 重复保存、失效回执、写入失败回滚和回执原子提交；
- 删除成功/幂等成功收敛，删除失败保留服务端最后确认状态；
- 退出、崩溃恢复和取消不泄漏 URL、临时文件或跨账号缓存；
- 生产 Kotlin 静态扫描不出现业务路径、领域响应解析、游标缓存或本地删除状态机。

### 14.3 Android UI/Port

- 三列网格的加载、空、内容、首错、续页错和缩略图占位；
- 照片、GIF、视频及不可预览格式详情；
- 下载百分比、取消、重试、成功、网络失败、空间不足和权限失效；
- MediaStore 写入成功后才显示“已成功保存到系统相册”；
- 重复保存不重复写，用户删除系统条目后允许再保存；
- 删除确认文案明确云端隐藏、不删除设备本地媒体、人工清理前可恢复；
- 删除成功退出详情并从列表移除，失败不模拟移除；
- 页面和元素 testTag 与 `stage05-v1` 完全一致。

### 14.4 性能和资源

- 10 万条服务端媒体使用 seek cursor，不执行 offset 深分页；
- 列表不一次性加载所有媒体或原文件，缩略图按视口加载并限制并发；
- 1 GiB 视频保存不把完整字节复制到 Kotlin/C++ 大数组，不产生第二份长期原文件；
- 进度事件节流，不因每个网络包触发 Compose 全页面重组；
- 临时空间不足在下载或写入前尽早失败，失败和取消后空间可回收。

## 15. 阶段完成定义

只有同时满足以下条件才能关闭阶段 05：

1. 本人可通过真实 Android、ECS、PostgreSQL 和私有 OSS 浏览、查看、保存并删除本人媒体；
2. 其他用户与管理员不能枚举、查看、保存、删除或取得任何私人对象授权；
3. 任何注册资源进入展示、播放器或系统相册前都完成长度和 SHA-256 校验；OSS 动态图片缩略图完成短期精确签名、`image/*` 和 5 MiB 上限校验；
4. 删除与分享关闭、审计、不可变 `trashed_at` 在同一事务收敛，OSS 和设备本地媒体未删除；
5. 所有业务 DTO、分页、授权、保存、回执和删除状态由 C++ Core 唯一拥有；
6. 正常、空、错误、重试、取消、权限失效、地址过期、账号切换和进程回收由真实状态驱动；
7. OpenAPI、migration、`stage05-v1`、自动化测试和验收记录同步；
8. `stage05-v1` 冻结，阶段 06 可直接复用 `trash_records`、分享关闭状态和私人缓存失效语义。
