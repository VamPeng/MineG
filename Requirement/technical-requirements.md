# MineG 技术需求

## 1. 文档信息

- 项目名称：MineG
- 文档版本：v1.2
- 文档状态：设计对齐后的实施技术基线
- 核定日期：2026-07-26
- 最近修订：2026-07-30（补充移动端领域数据主权与平台 Effect 边界）
- 产品基线：[MineG 产品需求 v2.0](./product-requirements.md)
- 功能基线：[MineG 功能需求 v1.0](./functional-requirements.md)
- 移动端契约：[MineG 三端一致性契约 v1.1](../Mobile/three-platform-consistency-contract.md)
- 适用范围：Go 后端、Web 审核管理端、Android、iOS、HarmonyOS、C++ 共享核心和必要运维工具

本版结束分散选型状态。技术栈、职责边界、功能实现逻辑和交付顺序均作为首版方案确认；实施时只能在保持契约和产品行为不变的前提下调整补丁版本或内部实现。

## 2. 技术目标与约束

### 2.1 目标

- 支撑手机号注册、人工审核、登录、会话恢复和退出闭环。
- 在三端可靠扫描系统相册、持久化任务、端到端加密并直传 OSS。
- 服务端只保存密文、密钥封装和必要元数据，管理后台不能查看或解密媒体。
- 支撑私人浏览与原文件保存、家庭只读共享、逻辑回收站和人工永久清理。
- Android 优先完成第一套完整实现；实现每项功能时同步冻结三端一致性契约，iOS、HarmonyOS 后续按契约补齐。
- Android、iOS、HarmonyOS 使用同一业务名称、数据语义、桥接职责、UI 操作和协议状态，不复制三套核心规则。

### 2.2 硬约束

- 后端为模块化单体，不拆微服务。
- PostgreSQL 是服务端业务状态唯一真实来源；MVP 不使用 Redis。
- 移动端 C++17 数据核心是账号上下文、用户资料、业务列表、本地媒体索引、任务队列和可恢复状态的唯一客户端领域数据来源。
- 来自服务端、需要缓存、跨页面共享、跨进程恢复或参与业务判断的数据，必须由 C++ Core 统一建模、解析、校验、合并、持久化并提供查询；平台代码不得建立第二份业务真实来源。
- API/RPC 路径、请求与响应 DTO、错误映射、分页、幂等、Token 刷新及业务状态迁移必须由 C++ Core 编排；平台 `TransportPort` 只执行 Core 产生的传输 Effect 并回传原始结果。
- 原生 ViewModel 只把 Core 领域快照转换为 `UiState`；不得直接请求业务接口、解析业务响应、持久化领域缓存或用页面列表代替领域状态。
- Android 功能在关键方法、数据桥接、UI 操作和状态进入三端一致性契约前不得合入。
- 原文件、缩略图和预览在客户端加密后上传；后端不生成媒体预览。
- App 与 OSS 直接传输密文大文件，Go 后端不代理完整媒体流量。
- 在线后端身份没有 OSS 永久删除权限。
- API 使用 HTTPS REST/JSON、OpenAPI 3.1、RFC 9457 错误和版本化路径。
- 不通过降低媒体质量、关闭证书校验、绕过系统权限或把密钥写入普通日志来解决问题。

## 3. 运行目标

| 平台 | 最低版本 | 实施要求 |
| --- | --- | --- |
| Android | Android 10 / API 29 | Kotlin、Jetpack Compose、MediaStore、WorkManager |
| iOS | iOS 13 | SwiftUI 为主，UIKit/PhotoKit/AVFoundation 包装补齐能力 |
| HarmonyOS | HarmonyOS 6.0 | ArkTS、ArkUI、PhotoAccessHelper、系统后台任务能力 |
| 后端 | Linux x86_64 | 容器化 Go 服务 |
| 管理端 | 当前受支持桌面浏览器 | Vue 3 SPA |

- 编译 SDK、Target SDK、Xcode、HarmonyOS SDK 和商店目标版本使用实施时仍受支持的稳定版本并锁定。
- 最低系统版本不等于承诺所有平台具有同等后台运行时长；三端只保证在系统授予执行机会时可靠恢复。

## 4. 总体架构

```text
Android / iOS / HarmonyOS
  ├─ 原生 UI 与平台能力适配器
  ├─ C ABI 绑定层
  └─ C++17 共享数据核心
       ├─ 领域模型、API/RPC 编排、响应解析与错误映射
       ├─ SQLite 本地索引、缓存、账号上下文与任务队列
       ├─ 加密、去重、状态机、Repository
       └─ 产生 PlatformEffect，由平台 Port 执行
             │
             ├─ HTTPS JSON ──> Go 模块化单体 ──> PostgreSQL
             │                       │
             │                       └─ STS / 对象授权
             └─ 加密媒体直传/读取 ─────────────> 私有阿里云 OSS

Vue 3 管理端 ──HTTPS──> Go 管理 API ──> PostgreSQL
受限清理 CLI ──独立运维身份──> PostgreSQL + OSS 删除权限
```

### 4.1 职责划分

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| 原生 UI | 页面、导航、输入草稿、系统弹窗、无障碍、播放器生命周期、领域快照到 `UiState` 的展示转换 | 业务接口、业务 DTO、领域缓存、列表合并、上传状态机、权限门禁决策 |
| 平台适配器 | 相册和权限原语、后台执行机会、网络字节传输、安全存储、系统相册写入 | API/RPC 语义、响应解析、服务端授权、业务幂等、跨平台状态定义 |
| C++ 核心 | 领域模型、API/RPC 编排、响应解析、错误映射、SQLite、账号上下文、任务状态、加密、去重、上传编排、公共命令/查询/事件 | 持有平台对象、直接显示 UI、实现平台网络或系统安全存储 |
| Go 后端 | 认证、审核、授权、媒体元数据、上传会话、共享、回收站、审计 | 读取媒体明文、生成明文缩略图 |
| Web 管理端 | 管理员登录、待审核列表、通过申请 | 家庭媒体、成员资料编辑、永久清理 |
| 运维 CLI | 清理预览、二次确认、OSS 永久删除、审计 | 在线用户功能、日常审核 |

### 4.2 移动端数据主权与运行时读取

移动端以“谁决定数据语义和下一步状态”判定数据所有权，而不以“数据最终存在于哪种语言的内存”判定。原生 UI 可以短期持有 Core 输出的不可变快照，但不能成为领域数据的创建者、合并者或恢复来源。

以下数据必须由 C++ Core 拥有：

- 账号会话语义、当前用户 ID、审核状态、下一业务步骤和退出清理状态；
- 用户资料、家庭成员、私人/家庭媒体、回收站、备份任务及反馈提交结果；
- 服务端 API/RPC 请求模型、响应 DTO、错误模型、游标、排序、去重、幂等和重试判断；
- 需要离线回退、跨页面共享、跨进程恢复或账号隔离的业务缓存；
- 权限、网络、存储和后台执行机会转换后的业务门禁与任务状态。

以下数据可以只存在于原生层：

- 输入框未提交内容、焦点、滚动位置、动画、弹窗和导航栈；
- Android URI、PhotoKit 对象、HarmonyOS PhotoAsset、系统权限对象和播放器实例；
- 平台网络连接、KeyStore/Keychain/HUKS 句柄、WorkManager/BGTaskScheduler/系统后台任务对象；
- 可丢弃且能从 Core 快照重新生成的图片解码缓存和展示格式。

当平台数据参与业务判断时，平台必须先把它转换为公共 Port 结果交给 Core，由 Core 决定状态迁移。媒体正文使用文件描述符、受控路径、流或不透明句柄跨边界，不要求把大字节数组复制进 C++ 堆内存。

```text
Core Command/Query
  ├─ 直接返回领域结果
  └─ 返回 PlatformEffect
       ├─ TransportEffect
       ├─ SecureStoreEffect
       ├─ MediaSourceEffect
       ├─ BackgroundSchedulerEffect
       └─ File/SystemAlbum Effect
              │
              v
       平台 Port 执行原语
              │
              v
       PlatformEffectResult 回到 Core
              │
              v
       Core 校验、持久化、迁移状态并发布领域事件
```

禁止以下实现进入生产路径：

- 在 Kotlin、Swift 或 ArkTS 中硬编码业务 API/RPC 路径并解析领域响应；
- 在平台 ViewModel、Repository 或偏好设置中维护可作为恢复来源的用户、媒体、任务或审核状态；
- 平台收到 HTTP 或对象上传结果后自行决定业务成功、重试、去重或下一状态；
- 仅通过三端同名接口或复制测试用例宣称一致，而业务算法仍分别实现三次。

确需绕过 Core 的领域数据必须登记版本化例外，写明数据所有者、原因、生命周期、安全边界、受影响平台和移除条件；未登记例外按架构缺陷处理。

## 5. 已选技术栈

### 5.1 后端

- Go 1.26.x；基线采用 1.26.5，安全补丁升级后同步更新锁定版本。
- HTTP Router：`github.com/go-chi/chi/v5`。
- PostgreSQL Driver 与连接池：`github.com/jackc/pgx/v5`、`pgxpool`。
- SQL：参数化原生 SQL；使用 `sqlc` 生成类型安全查询代码。
- Migration：`pressly/goose/v3`，只使用顺序 SQL migration；共享环境历史 migration 不修改。
- 密码哈希：Go `x/crypto/argon2` 的 Argon2id。
- API 描述：仓库维护 OpenAPI 3.1 规范，CI 校验实现、示例和兼容性。
- 日志：标准库 `log/slog` 输出结构化 JSON；全链路携带 request ID。
- 追踪与指标：OpenTelemetry SDK；暴露受保护的健康检查和指标端点。
- OSS/STS：阿里云受支持的 Go SDK；运行时使用 ECS RAM Role，不保存长期 AccessKey。

### 5.2 Web 管理端

- Vue 3、TypeScript、Vite、Vue Router、Element Plus。
- 使用基于 OpenAPI 生成或校验的类型化 API Client。
- 不引入复杂全局状态框架；会话与待审核列表使用组合式状态和查询封装。
- 管理员认证使用服务端 Session Cookie，不把 Token 写入 `localStorage` 或 `sessionStorage`。

### 5.3 移动端

- Android：Kotlin、Jetpack Compose、Coroutines、WorkManager。
- iOS：Swift、SwiftUI；iOS 13 不足的导航、媒体和生命周期能力使用 UIKit/PhotoKit/AVFoundation 包装。
- HarmonyOS：ArkTS、ArkUI、PhotoAccessHelper、Media Kit 与系统后台任务接口。
- 公共核心：C++17、CMake、SQLite C API、nlohmann/json、libsodium。
- Android 通过 JNI、iOS 通过 C/Objective-C++、HarmonyOS 通过 Node-API 调用稳定 C ABI。
- 三端原生工程只实现 UI、Bridge 与 PlatformPort；不得各自建立账号、资料、媒体或上传业务 Client。
- Core 通过版本化 `PlatformEffect` 请求网络、安全存储、媒体源、后台调度、文件与系统相册能力；平台把原始结果回传 Core，不解释领域语义。
- 三端公共命名、桥接方法、页面/元素语义 ID 和变更规则遵守[三端一致性契约](../Mobile/three-platform-consistency-contract.md)。
- 不使用 Flutter、React Native、Kotlin Multiplatform 或共享 Web UI。

### 5.4 数据与基础设施

- PostgreSQL 18，部署时锁定具体小版本。
- 私有阿里云 OSS Bucket，阻止公共访问，启用 HTTPS 和 SSE-OSS AES-256。
- 不使用 Redis、Kafka、RabbitMQ、Elasticsearch、CDN、Kubernetes 或 API Gateway。
- 后端和管理端使用 Docker 镜像部署；数据库 migration 作为发布前独立步骤执行。

## 6. 服务端模块与数据模型

### 6.1 Go 模块边界

```text
internal/
  auth/          注册、登录、Token、协议接受记录
  approval/      待审核列表、审核状态转换、密钥就绪协调
  profile/       昵称、头像元数据
  keybundle/     公钥、加密密钥包、家庭密钥封装；不处理明文密钥
  upload/        上传会话、STS、分片与完成核对
  media/         媒体、资源、相册关系、私人查询
  sharing/       家庭共享、只读授权、过滤
  trash/         逻辑删除、恢复、清理候选
  feedback/      FAQ 版本和反馈记录
  audit/         管理与运维审计
  objectstore/   OSS/STS 适配
  platform/      配置、日志、数据库、HTTP 基础设施
```

模块通过显式 Service/Repository 接口协作；HTTP Handler 不直接写 SQL，对象存储适配器不决定业务权限。

### 6.2 核心表

| 表 | 关键内容 |
| --- | --- |
| `users` | 手机号规范值、密码哈希、状态、昵称、头像、时间戳 |
| `user_agreements` | 用户、协议版本、接受时间、设备安装标识 |
| `user_sessions` | Access/Refresh Token 哈希、设备、轮换、撤销状态 |
| `admin_users` / `admin_sessions` | 管理员身份与服务端会话 |
| `devices` | 用户设备、平台、安装标识、最后活动时间 |
| `user_key_bundles` | 用户公钥、加密私钥包/主密钥包、KDF 参数 |
| `family_key_envelopes` | 家庭密钥对每个成员公钥的密封封装 |
| `albums` | 所有者、设备相册稳定标识、名称 |
| `media` | 所有者、拍摄时间、类型、状态、账号键控指纹 |
| `media_resources` | 原图、视频、实况照片配对资源、密文对象键、密文大小、清单摘要 |
| `media_album_links` | 媒体与一个或多个相册关系 |
| `media_key_envelopes` | 媒体密钥对用户主密钥或家庭密钥的封装 |
| `upload_sessions` | 幂等键、对象范围、STS 范围、状态、有效期 |
| `upload_parts` | 分片号、密文大小、ETag/校验、确认状态 |
| `shares` | 媒体共享状态、共享者、时间、版本 |
| `trash_records` | `trashed_at`、恢复时间、`purged_at` |
| `feedback` | 分类、描述、可选联系方式、环境信息、状态 |
| `audit_events` | 操作者、动作、目标、结果、request ID、时间 |

### 6.3 数据约束

- `users.phone_e164` 唯一。
- 账号状态数据库约束只允许 `PENDING -> APPROVED`；内部密钥准备状态单独记录，不扩展产品账号状态。
- `media(owner_id, dedupe_fingerprint, content_revision)` 唯一。
- `shares.media_id` 唯一，重复共享为幂等更新。
- `trash_records.media_id` 唯一；首次 `trashed_at` 不可被重复删除覆盖。
- 对象键由后端生成，客户端只能在授权前缀内上传。
- 核心状态变更使用事务和条件更新；审计事件与敏感状态尽量在同一事务写入。

## 7. API 基线

### 7.1 通用规范

- 基础路径 `/api/v1`，HTTPS，JSON 字段使用 `snake_case`。
- 错误使用 `application/problem+json`，包含稳定业务错误码和 `request_id`。
- 时间为 UTC RFC 3339，对外 ID 为不透明字符串。
- 列表使用游标分页；游标绑定用户、过滤条件和排序，不能跨过滤条件复用。
- 写操作通过 `Idempotency-Key` 支持安全重试。
- OpenAPI 变更在合并前执行 breaking-change 检查。

### 7.2 接口分组

```text
POST   /auth/register
POST   /auth/login
POST   /auth/refresh
POST   /auth/logout
GET    /auth/approval-status

GET    /me
PATCH  /me/profile
POST   /me/avatar/uploads
GET    /me/key-bundle
PUT    /me/key-bundle
GET    /key-grants/pending
POST   /key-grants/{id}/complete

POST   /uploads
GET    /uploads/{id}
POST   /uploads/{id}/parts
POST   /uploads/{id}/complete

GET    /private/media
GET    /private/media/{id}
POST   /private/media/{id}/access
POST   /private/media/{id}/share
DELETE /private/media/{id}/share
POST   /private/media/{id}/trash

GET    /family/media?filter=all|mine
GET    /family/media/{id}
POST   /family/media/{id}/access

GET    /trash
POST   /trash/{id}/restore

GET    /help/faq
POST   /feedback

POST   /admin/login
POST   /admin/logout
GET    /admin/approvals
POST   /admin/approvals/{id}/approve
```

- 媒体访问接口返回短期密文对象地址、资源清单和客户端可解封的密钥封装，不返回明文密钥。
- 家庭访问接口永远不签发“保存原文件”用途的授权。
- 上传完成接口核对 OSS 对象、分片、密文长度和客户端签名的资源清单后才创建可见媒体。

## 8. 认证、审核与会话实现

### 8.1 注册与密码

- 服务端把手机号规范化为 `+86` E.164 并执行唯一约束。
- 密码使用 Argon2id，每个账号独立随机盐；参数在目标 ECS 做基准后写入版本化配置，目标服务端验证耗时 150～300 ms。
- 不保存明文密码、可逆密码或普通快速哈希。
- 注册接口同时接收客户端用户公钥和加密密钥包；私钥明文不离开客户端。

### 8.2 移动会话

- 使用密码学安全的随机 Access Token 和轮换 Refresh Token，不使用 JWT。
- PostgreSQL 只保存 Token 哈希、设备、有效期、轮换链和撤销状态。
- Access Token 默认 15 分钟；Refresh Token 默认 30 天且每次成功刷新后轮换。
- 原始 Token 只存 Android Keystore、iOS Keychain 或 HarmonyOS Asset Store。
- 退出撤销当前设备会话；检测 Refresh Token 重放时撤销整条轮换链。

### 8.3 审核与家庭密钥就绪

1. 管理员把申请从 `PENDING` 审核通过。
2. 后端创建内部 `KEY_GRANT_PENDING` 任务，但用户仍显示待审核页。
3. 任一已具备家庭密钥的成员设备同步到任务后，用新成员公钥密封家庭共享密钥并上传 envelope。
4. 后端验证 envelope 元数据完整后把账号对外状态置为 `APPROVED`。
5. 部署只创建固定家庭记录；首个获批成员设备通过一次性原子 bootstrap 创建家庭共享密钥并上传首个 envelope，服务端不接触密钥明文。

该流程保证 Web 管理员无需接触家庭明文密钥。若现有成员设备暂时离线，新成员继续停留在设计已有的待审核状态，不增加新页面。

### 8.4 管理端会话

- 使用服务端随机 Session ID 和 `Secure`、`HttpOnly`、`SameSite=Strict` Cookie。
- 登录后轮换 Session ID，30 分钟无操作失效，绝对有效期 8 小时。
- 所有状态变更使用 CSRF Token、Origin 校验和审计记录。

## 9. 端到端加密与密钥方案

### 9.1 威胁边界

方案保护以下情况：OSS、数据库备份、管理后台账号或普通后端运维查询被单独获取时，攻击者不能得到媒体明文。方案不承诺保护已经解锁的成员设备、被篡改的客户端安装包或成员主动截屏/复制的内容。

### 9.2 密钥层级

```text
登录密码
  └─ Argon2id + 独立盐/域分离 -> Password Wrapping Key
       └─ 加密用户私钥与 User Master Key

User Master Key
  └─ 封装私人 Media Key

Family Sharing Key
  └─ 通过成员 X25519 公钥分别密封
       └─ 封装已共享媒体的 Media Key

Media Key
  └─ 派生每个 Resource/Thumbnail/Preview 的加密密钥
```

- 使用 libsodium 随机数和密钥封装原语。
- 用户密钥对使用 X25519；家庭密钥 envelope 使用 sealed box。
- 密码包装密钥与服务端密码哈希使用不同盐和域标签，不能互换。
- 解锁后的主密钥和家庭密钥不写 SQLite，不写日志，只驻留受控内存。

### 9.3 媒体加密格式

- 每条逻辑媒体生成随机 256 位 Media Key。
- 每个底层资源通过 KDF 从 Media Key 派生独立 Resource Key。
- 每个资源按 4 MiB 逻辑块使用 XChaCha20-Poly1305 独立认证加密。
- Nonce 由随机 128 位前缀加 64 位块序号组成；同一 Resource Key 下不得重复。
- AAD 包含格式版本、媒体 ID、资源 ID、块序号、明文长度和资源类型。
- 加密清单记录资源图、块数、密文长度和摘要；清单本身认证加密。
- 一个加密块映射一个 OSS multipart part，支持任意块重试和断点续传。
- 解密必须验证每块认证标签、清单、顺序和最终资源集合，失败时不输出部分可用文件。

### 9.4 缩略图与预览

- 客户端从系统允许的本地资源生成缩略图、视频封面和必要预览。
- 派生资源使用独立 Resource Key 加密后上传。
- 后端 Worker 不读取原文件明文，也不做服务端转码。
- 如果某平台无法生成兼容预览，上传原文件密文并让其他端按功能降级规则展示占位。

### 9.5 分享与取消分享

- 共享不复制媒体对象，只增加 Media Key 的家庭密钥封装和共享业务记录。
- 取消共享删除服务端可发现的家庭 envelope 引用并停止签发访问授权；私人 envelope 保留。
- 因家庭成员已经持有 Family Sharing Key，取消共享不能撤销其已经缓存的明文，这一限制由产品明确承认。

## 10. 上传、续传与去重实现

### 10.1 单媒体备份流程

1. 平台适配器读取 `MediaDescriptor` 和资源句柄。
2. C++ 核心写入本地索引并计算账号键控内容指纹。
3. 核心向后端创建上传会话；后端先按用户和指纹查重。
4. 客户端生成缩略图/预览、Media Key、密钥 envelope 和加密资源清单。
5. 后端返回受限对象键和短期 STS 权限。
6. 平台 TransportPort 把密文块直接分片上传 OSS。
7. 每个完成分片把 ETag、密文大小和摘要写回本地核心并上报服务端。
8. 客户端提交完成；后端调用 OSS Head/ListParts 核对对象。
9. 后端事务性写入媒体、资源、相册关系和完成状态。
10. 客户端收到确认后把任务置为 `COMPLETED`，私人空间才显示媒体。

### 10.2 去重

- 客户端使用账号私有 `dedupe_key` 对规范化资源摘要做 HMAC；服务端只看到账号内不可跨用户比较的指纹。
- 本地先按平台 ID、修改版本和下载回执去重，服务端唯一约束作为最终保护。
- 同账号跨设备相同资源命中后，后端返回已有媒体引用；客户端只补齐必要相册关系，不重新上传对象。
- 不同账号指纹不可比较，始终保留独立媒体记录和密钥。

### 10.3 重试与并发

- 默认同时准备 2 条媒体、同时上传 3 个分片；根据内存、网络和平台后台约束动态下调。
- 重试采用指数退避加随机抖动：5 秒起步，最多 15 分钟；永久鉴权、权限和格式错误不自动无限重试。
- 上传会话默认 24 小时有效；过期后复用本地密文块和幂等键申请新会话。
- 自动备份关闭或退出登录时停止新调度；已完成分片和本地密文缓存保留到任务确认或安全清理。

## 11. 移动端共享核心

### 11.1 C++ 领域模型

```text
AccountSession
UserProfile
PermissionSnapshot
BackupSettings
LocalAlbum / LocalMedia / MediaResource
BackupTask / UploadPart / FailureReason
PrivateMedia / FamilyMedia / ShareState
TrashItem
KeyBundle / KeyEnvelope
```

- 公共模型不包含 Android URI、PhotoKit 对象或 HarmonyOS PhotoAsset 实例。
- 平台对象通过不透明句柄、文件描述符、受控路径或流接口短期传递。

### 11.2 SQLite

- 固定版本 SQLite 随核心构建，启用 WAL、外键和 busy timeout。
- 单数据库执行线程串行写入；所有状态转换使用事务。
- 只保存业务索引、任务、密文临时文件引用、分片和事件游标，不保存媒体明文或解封后的密钥。
- Schema 使用顺序整数 migration；已发布 migration 不修改。
- 不使用 SQLCipher；敏感密钥由平台安全存储与加密 key bundle 保护。

### 11.3 C ABI

- `mineg_core.h` 只暴露固定宽度整数、字节缓冲区、不透明句柄和显式释放函数。
- 不暴露 C++ 类、STL、异常或平台对象。
- 命令、查询、事件、错误码和资源生命周期全部版本化。
- 异常在 ABI 内转换为稳定错误；回调必须允许取消，并明确线程归属。
- Android JNI、Objective-C++ 和 Node-API 绑定只做类型转换与生命周期衔接。

### 11.4 Port 接口

| Port | 责任 |
| --- | --- |
| `MediaSourcePort` | 权限状态、相册分页、资源句柄、变化监听 |
| `SecureStorePort` | Token、设备密钥包装密钥、安装标识 |
| `TransportPort` | JSON API、STS 上传下载、进度和错误 |
| `BackgroundSchedulerPort` | 任务约束、恢复、停止和系统回调 |
| `ConnectivityPort` | 网络类型、计量状态和变化 |
| `FilePort` | 密文临时文件、磁盘空间和安全清理 |
| `MediaPlaybackPort` | 平台播放器与实况照片展示 |
| `SystemAlbumWriterPort` | 私人原文件写回系统相册 |

Port 只实现平台原语。`TransportPort` 不得拥有业务端点表、解析业务 DTO、刷新 Token 或决定重试状态；`SecureStorePort` 不得决定凭据生命周期；`BackgroundSchedulerPort` 不得把系统任务状态当成业务任务真相。上述决策统一由 C++ Core 产生 Effect 并消费 EffectResult。

## 12. 三端平台实现

### 12.1 Android

- Android 10～12 使用对应存储权限与 MediaStore；Android 13+ 使用图片/视频媒体权限；Android 14+ 检测 Selected Photos Access 并视为非完整授权。
- 全库自动备份使用 MediaStore，不用 Photo Picker 代替完整权限。
- WorkManager 使用唯一任务和账号标签；默认 `UNMETERED`，开启移动网络后改为 `CONNECTED`。
- 长时间上传只在系统要求和产品允许时使用 long-running Worker/前台服务，并展示系统强制通知。
- Compose 页面订阅 C++ 核心事件，不直接观察 WorkManager 作为业务真相。
- 下载使用 MediaStore 写回系统相册并保存下载回执。

### 12.2 iOS

- 使用 PhotoKit `.readWrite` 授权；`.limited` 视为未完整授权。
- 使用 `PHFetchResult` 分页/分批读取，`PHPhotoLibraryChangeObserver` 触发增量核对。
- 使用 `PHAssetResourceManager` 获取照片、视频和 Live Photo 配对资源。
- 前台准备加密密文文件；大文件上传使用 background `URLSessionUploadTask`，通过固定 session identifier 跨启动重连。
- 使用 BGTaskScheduler 争取扫描和准备机会，但不承诺系统未授予时间时持续运行。
- SwiftUI 构建页面；视频、Live Photo 和 iOS 13 不足能力通过 UIKit/AVFoundation 包装。
- 使用 PhotoKit change request 把解密后的完整资源写回系统相册。

### 12.3 HarmonyOS

- 使用 PhotoAccessHelper 查询媒体、相册和资源，并区分系统支持的授权状态。
- 使用 ArkUI 构建页面，Media Kit 展示视频和动态照片。
- 使用系统后台任务和网络约束恢复上传；平台回调通过 Node-API 写回 C++ 核心。
- Asset Store Kit 保存 Token 和设备包装密钥。
- 系统相册写回必须在系统确认完成后生成下载回执。

### 12.4 跨端一致性

- [三端一致性契约](../Mobile/three-platform-consistency-contract.md)是移动端公共命名和行为的唯一基线。
- 同一领域模型、关键方法、数据字段、Bridge/Port 职责、页面语义 ID、UI 操作、功能状态、错误码和排序规则由公共契约测试验证。
- 平台没有等价能力时，只能使用功能需求定义的降级方式，不得静默丢失资源或降低原文件质量。
- Android 是第一套完整参考实现；每个 Android 功能编码前先登记契约、通过后冻结契约，但 Android SDK 类型、Compose 类名和平台特有行为不得成为公共协议。
- iOS、HarmonyOS 可以采用符合各自语言习惯的异步与生命周期写法，但公共基础标识、参数语义、状态迁移和用户操作名称不得另起一套。

## 13. 私人浏览、家庭共享与回收站实现

### 13.1 私人浏览

- 后端查询强制 `owner_id = current_user`、`upload_status = completed`、`trashed_at IS NULL`。
- 客户端取得缩略图密文、验证解密后显示；原文件只在详情主动保存时读取。
- 对象短期地址默认 5 分钟；每次续签重新校验所有权和状态。

### 13.2 家庭相册

- 后端查询强制有效分享和未删除，再按 `all` 或 `mine` 过滤，最后生成游标。
- 家庭访问授权只标记 `view`/`stream` 用途，不提供私人保存用途。
- 取消共享或删除后，后端不再签发新地址和 envelope 引用。
- 客户端家庭详情不暴露写回系统相册、系统分享或原文件导出命令。

### 13.3 回收站

- 删除事务：条件更新媒体状态、写首次 `trashed_at`、关闭分享、写审计。
- 恢复事务：清除逻辑删除状态、保持分享关闭、写恢复时间和审计。
- 在线 API 没有 purge endpoint。
- 清理 CLI 使用独立数据库只读/受控写权限与 OSS 删除 RAM 角色，执行两阶段清单流程。

## 14. 帮助、反馈与审计

- FAQ 以版本化 JSON/本地资源随 App 发布，后端接口仅用于后续热更新兼容；离线使用本地版本。
- 反馈接口验证 1～1000 字、固定分类和幂等键。
- 反馈只记录允许的环境字段，不接收媒体附件、日志包、Token、对象地址或密钥。
- 管理后台首版不建设反馈处理页面；受限运维查询使用脱敏导出。
- 注册审核、管理员登录、分享、删除、恢复、上传完成和人工清理写结构化审计事件。

## 15. 功能实现责任矩阵

| 功能 | 后端实现 | 管理端实现 | 移动端/C++ 实现 | 首次落地阶段 |
| --- | --- | --- | --- | --- |
| F-01 注册登录会话 | 注册、登录、Token、协议记录、状态查询 | 无 | 表单、协议、错误状态、安全存储、会话恢复 | B1 / M1 |
| F-02 注册审核 | 申请列表、状态事务、审计、key grant 协调 | 登录、列表、详情、确认通过 | 待审核轮询和家庭 key grant | B1～B2 / A1～A3 / M1 |
| F-03 个人资料 | 本人资料、头像授权 | 无 | 展示、编辑、裁剪、入口导航 | B2 / M1、M6 |
| F-04 相册权限 | 不保存系统权限，只拒绝无效上传前提 | 无 | 统一说明页、系统请求、前台复查、任务门禁 | M2 |
| F-05 备份设置 | 不保存设备级开关 | 无 | SQLite 配置、调度约束、开始备份按钮 | M2 |
| F-06 扫描与本地相册 | 接收相册关系和媒体元数据 | 无 | 平台扫描、索引、增量、缩略图、三列网格 | M2～M4 |
| F-07 加密与密钥 | 只存公钥、密文 key bundle/envelope | 无权访问 | 密钥生成/解封、资源加密、内存清理 | B2 / M0、M3 |
| F-08 上传队列与去重 | 上传会话、STS、分片核对、唯一约束 | 无 | 状态机、分块、重试、账号指纹、恢复 | B3 / M3～M4 |
| F-09 私人空间 | 所有者查询和短期访问授权 | 无 | 网格、详情、解密预览和播放器 | B4 / M5 |
| F-10 原文件保存 | 所有者下载用途授权 | 无 | 下载、校验、解密、系统相册写入、回执 | B4 / M5 |
| F-11 共享/取消共享 | 分享事务、家庭 envelope 引用、撤销授权 | 无 | 操作、确认、成功反馈、状态更新 | B5 / M6 |
| F-12 家庭相册 | 共享过滤、分页、只读访问授权 | 无 | 时间线、过滤、详情、只读播放器 | B5 / M6 |
| F-13 回收站 | 删除/恢复事务、清理候选、审计 | 无 | 删除确认、列表、空状态、恢复 | B6 / M5～M6 |
| F-14 帮助反馈 | FAQ 兼容接口、反馈幂等写入 | 首版无反馈页面 | 离线 FAQ、反馈表单和环境字段 | B7 / M6 |

这张表是范围边界：某一列为“无”时，不得为了实现方便在该系统增加同类业务页面或权限。

## 16. 后端实现顺序

### B0：工程与契约基础

- Go 模块、chi、pgxpool、sqlc、goose、配置、日志、错误、OpenAPI、CI。
- 建立 PostgreSQL、测试数据库和 OSS/STS 假实现。
- 完成 request ID、超时、恢复、健康检查和优雅关闭。

完成条件：空服务可部署，migration 可重复执行，OpenAPI 与错误格式通过契约测试。

### B1：账号、会话与审核

- 注册、登录、刷新、退出、协议版本记录。
- 管理员 bootstrap、管理端 Session、待审核列表和通过操作。
- 用户、公钥和加密 key bundle 持久化。

完成条件：Android 测试客户端可完成注册—审核—登录—退出闭环。

### B2：家庭密钥授权与个人资料

- 家庭 key envelope 任务、首成员 bootstrap、新成员 key grant。
- 本人资料、昵称、头像上传与短期读取。

完成条件：管理员无法访问明文密钥，新成员只有 envelope 就绪后进入 App。

### B3：上传与对象存储

- 上传会话、服务端对象键、受限 STS、分片记录、完成核对和幂等。
- 媒体、资源、相册关系和账号内去重约束。

完成条件：一条客户端加密照片可直传 OSS，后端核对后进入私人查询。

### B4：私人媒体与访问授权

- 私人列表、详情、缩略图/原文件密文访问授权和游标。
- 所有权、状态和用途校验。

完成条件：本人可浏览和保存，其他账号无法取得访问授权。

### B5：分享与家庭相册

- 分享/取消分享事务、家庭列表过滤、家庭只读访问授权。

完成条件：“全部 / 我分享的”、取消共享和删除联动通过并发测试。

### B6：回收站与恢复

- 逻辑删除、不可变 `trashed_at`、列表、恢复和审计。
- 独立清理 CLI 的清单与执行协议。

完成条件：在线身份不能永久删除，恢复媒体保持未共享。

### B7：反馈、观测和加固

- FAQ/反馈、限流、审计导出、指标、告警和故障演练。

完成条件：容量、STS、数据库、OSS 和会话异常有可定位指标且不泄露敏感数据。

## 17. 管理后台实现顺序

### A1：基础壳与管理员登录

- Vue 3 工程、路由、登录页、Session 恢复、401 处理、CSRF。

### A2：待审核列表

- 游标列表、脱敏手机号、注册时间、空状态、加载失败和刷新。

### A3：审核通过

- 申请详情、二次确认、防重复点击、幂等结果和成功后移出列表。

管理后台在 A3 后即达到 MVP；不继续开发媒体、家庭成员管理、反馈工单、永久清理或数据统计页面。

## 18. 移动端实现顺序

### M0：一致性契约与 Android 基座

本阶段只要求 Android 可运行，不要求先创建 iOS、HarmonyOS 工程：

- 建立并版本化[三端一致性契约](../Mobile/three-platform-consistency-contract.md)，确定分层、公共名称、CoreClient、C ABI、PlatformPort、错误、页面/元素语义 ID 和变更规则。
- Android 加载 C++ 核心、SQLite 和 libsodium，打通 JNI 的命令、查询、事件、取消和释放。
- Android 适配 SecureStore、TransportPort、MediaSourcePort 和后台调度最小能力。
- 完成 SQLite migration/事务读写、一次 HTTPS JSON、读取一条媒体并流式加密一个资源。
- 建立契约清单与 Android 一致性测试；公共接口不暴露 Android URI、SDK 类型或 Compose 状态。

完成条件：Android 基座纵向验证通过，契约基础接口由 `BASELINED` 转为 `FROZEN`。iOS、HarmonyOS 后续先通过同一基座测试，再进入各自业务功能。

### M1：Android 账号准入

- 登录、注册错误、待审核、协议勾选、会话恢复、个人资料和退出。
- 对接 B1、B2 与 Web 审核闭环。
- 实现前登记账号模型、方法、错误、页面 ID、操作和状态；通过后冻结 F-01～F-03 契约。

### M2：Android 权限、设置和本地相册

- 统一权限页、完整授权检测、默认开启设置、相册分页和三列网格。
- 扫描状态先使用本地真实索引，不接假上传进度。
- 冻结 F-04～F-06 的 Port 方法、权限状态、相册模型和 UI 操作契约。

### M3：Android 单媒体加密备份

- Media Key、资源分块加密、账号指纹、STS 直传、服务端完成确认。
- 验证照片、视频、GIF、实况/动态资源中的代表样本。
- 冻结 F-07 与单媒体上传涉及的命令、事件、进度和错误契约。

### M3-D：Android 数据层主权迁移门禁

M1～M3 已验证的产品行为和历史验收保留，但 2026-07-30 复核发现账号、资料、媒体 API、上传编排和部分业务缓存仍位于 Kotlin。进入 M4 功能扩展前，必须按[Android 数据层迁移技术方案](../Mobile/docs/android-data-layer-migration.md)完成以下纠偏：

- 建立 Foundation v2 的 PlatformEffect/EffectResult 与可恢复 operation；
- 把账号、Session、审核、Profile、KeyGrant、私人媒体查询、扫描决策和单媒体上传状态机迁入 C++ Core；
- 清除 Android 专属领域缓存和 ViewModel 模拟领域成功；
- 建立平台生产代码数据主权扫描门禁；
- 保持 Android 真实后端闭环、进程恢复、安全与账号隔离回归通过。

M3-D 未完成时不得启动 iOS/HarmonyOS 业务数据层，也不得在 Android 新增业务 Client、DTO、领域缓存或平台状态机。传输实现差异必须收敛在 Core 协议适配与 PlatformPort，不得重新穿透到页面层。

### M4：Android 完整队列与备份状态

- 历史/增量扫描、恢复、Wi-Fi/移动网络、空间不足、服务异常、完成状态。
- 10 万条索引、进程回收、设备重启、网络切换和分片重试测试。
- 冻结 F-08 队列状态机、恢复行为和备份页交互契约。

### M5：Android 私人闭环

- 私人网格、媒体详情、照片/GIF/视频、原文件保存、下载回执和删除确认。
- 冻结 F-09～F-10 的查询、详情、保存、删除和页面状态契约。

### M6：Android 家庭与回收站闭环

- 分享/取消分享、家庭时间线与过滤、回收站列表/空状态/恢复。
- 个人中心全部入口和帮助反馈。
- 冻结 F-11～F-14 的方法、事件、确认规则和 UI 语义契约；至此 Android 首版功能完整。

### M7：iOS 完整实现

- 先完成 M0 同一契约清单、C ABI、Bridge 和 PlatformPort 基座测试，再按 M1～M6 顺序实现冻结契约。
- 重点验证 PhotoKit 多资源、iCloud 资源、background URLSession 和 iOS 13 降级。

### M8：HarmonyOS 完整实现

- 先完成 M0 同一契约清单、C ABI、Bridge 和 PlatformPort 基座测试，再按 M1～M6 顺序实现冻结契约。
- 重点验证 PhotoAccessHelper、动态照片、Node-API 生命周期和后台任务。

## 19. 跨系统交付顺序

| 阶段 | 后端 | 管理端 | 移动端 | 交付结果 |
| --- | --- | --- | --- | --- |
| 0 | B0 | 工程初始化 | M0 契约 + Android 基座 | 一致性契约冻结，Android 架构和加密可行性通过 |
| 1 | B1 | A1～A3 | M1 Android | 注册审核登录闭环 |
| 2 | B2 | 结束 MVP 范围 | M2 Android | 密钥、资料、权限和本地相册 |
| 3 | B3 | — | M3 Android | 单条加密备份纵向闭环 |
| 3D | B1～B3 回归 | — | M3-D Android/C++ 数据层迁移 | Core 数据主权与 PlatformEffect 门禁通过 |
| 4 | B3/B4 | — | M4 Android | 完整自动备份与状态 |
| 5 | B4 | — | M5 Android | 私人浏览、保存和删除 |
| 6 | B5/B6 | — | M6 Android | 家庭共享、回收站、帮助反馈 |
| 7 | B7 | — | M7 iOS | iOS 功能一致 |
| 8 | B7 | — | M8 HarmonyOS | 三端功能一致 |
| 9 | 加固 | 加固 | 三端加固 | 发布候选与故障演练 |

不得先把所有后端接口一次性写完再开始客户端。每个阶段必须形成可运行的纵向闭环，使用真实鉴权、数据库和密文对象验证。

## 20. 测试与质量门槛

### 20.1 自动化测试

- Go：领域单元测试、Repository 集成测试、HTTP 契约测试、并发与幂等测试。
- PostgreSQL：每个 migration 从空库和上一发布版本升级测试。
- C++：状态机、加密格式、损坏检测、去重、SQLite 和崩溃恢复测试。
- 三端绑定：读取同一契约清单，验证 C ABI、CoreClient、Port 名称、字段、错误、回调和生命周期。
- 数据主权：CI 扫描 Kotlin、Swift、ArkTS 生产代码中的业务 API/RPC 路径、领域响应 JSON 解析、业务 SharedPreferences/UserDefaults/Preferences 缓存和平台端状态迁移；未登记例外必须阻断。
- Effect 契约：同一组 Core 命令在三端测试替身下产生相同 PlatformEffect，并对相同 EffectResult 得到相同领域结果、持久化状态和事件。
- 三端 UI：Android 阶段先验证公共页面/元素语义 ID 与交互清单；iOS、HarmonyOS 实现后加入同一门禁。
- UI：关键流程截图/语义测试，不把 Stitch HTML 作为生产运行依赖。
- 端到端：注册审核、单条备份、断点续传、分享、取消分享、删除、恢复、退出。

### 20.2 安全测试

- 媒体密文篡改、块重排、截断和 envelope 错配必须失败关闭。
- 管理员、其他成员、过期会话和已取消分享链接的越权测试。
- Token 重放、审核重复提交、上传完成重复提交和清理清单竞态测试。
- 日志与反馈敏感数据扫描。

### 20.3 性能基线

- 10 万条本地媒体索引的分页扫描和启动恢复不一次性载入内存。
- 1 GiB 视频可分块加密、暂停和续传，不生成第二份明文长期副本。
- 列表 API 在合理索引下保持稳定游标分页；性能目标在首个真实数据集压测后固化。

## 21. 发布、迁移与运维

- 配置通过环境和密钥管理注入；仓库不保存生产密钥、AccessKey 或数据库密码。
- 服务启动不自动执行不可逆 migration；发布流水线先备份、再 migration、再滚动发布。
- OSS 生命周期只能清理过期未完成分片和明确临时密文，不得删除已完成媒体。
- 数据库备份与 OSS 对象分别验证恢复；恢复演练不能依赖媒体明文。
- 关键告警：登录失败率、审核积压、上传会话失败、OSS/STS 错误、数据库连接、密钥 envelope 积压、清理失败。
- 所有时钟使用 UTC；客户端显示按设备时区。

## 22. 技术决策记录

| 编号 | 决策 | 结论 |
| --- | --- | --- |
| T-001 | 移动 UI | Android Compose、iOS SwiftUI+UIKit 包装、Harmony ArkUI |
| T-002 | 共享核心 | C++17 + SQLite + 稳定 C ABI |
| T-003 | 后端 | Go 1.26 + chi 模块化单体 |
| T-004 | 数据库 | PostgreSQL 18 + pgxpool + sqlc + goose |
| T-005 | 缓存 | MVP 不使用 Redis |
| T-006 | 对象存储 | 私有阿里云 OSS，客户端密文直传 |
| T-007 | 媒体加密 | libsodium XChaCha20-Poly1305 分块认证加密 |
| T-008 | 密钥共享 | 用户 X25519、公私密钥包、家庭密钥 envelope |
| T-009 | 认证 | Argon2id + 随机 Access/Refresh Token；管理端 Session Cookie |
| T-010 | API | HTTPS REST、OpenAPI 3.1、RFC 9457、游标和幂等键 |
| T-011 | 后台任务 | 平台调度器执行，C++ 核心持久化业务真相 |
| T-012 | 缩略图 | 客户端生成并加密，后端不读取媒体明文 |
| T-013 | 去重 | 账号私有键控内容指纹，不跨成员比较 |
| T-014 | 管理后台 | 仅登录、待审核列表和通过申请 |
| T-015 | 永久清理 | 独立 CLI 两阶段清单，在线身份无删除权 |
| T-016 | 移动端数据主权 | 服务端/缓存/跨页面/可恢复领域数据由 C++ Core 唯一拥有，原生层只持有 UiState 与平台原语 |
| T-017 | 平台副作用 | C++ Core 产生版本化 PlatformEffect，三端 Port 执行并回传 EffectResult，不复制业务编排 |

以上决策均为“已确认”。后续只有实施验证发现不可满足的系统限制时，才新增带证据的变更记录。

## 23. 官方能力参考

- Android 媒体访问与权限：https://developer.android.com/training/data-storage/shared/media
- Android 持久后台任务：https://developer.android.com/develop/background-work/background-tasks/persistent
- Apple PhotoKit：https://developer.apple.com/documentation/photokit
- Apple URLSession：https://developer.apple.com/documentation/foundation/urlsession
- HarmonyOS PhotoAccessHelper：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/arkts-apis-photoaccesshelper-photoaccesshelper
- Go 发布策略：https://go.dev/doc/devel/release
- libsodium XChaCha20-Poly1305：https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305
