# MineG 家庭节点私人云架构 vNext

## 1. 文档信息

- 文档版本：v0.1
- 文档状态：架构重设计草案
- 编制日期：2026-07-29
- 适用范围：家庭 Linux 节点、ECS 协调节点、移动 App、对象存储、必要安全措施
- 取代范围：现行技术基线中“完整业务后端部署在 ECS”“App 直传阿里云 OSS”“媒体端到端加密属于 MVP”的架构结论
- 暂不取代：账号、审核、私人空间、家庭共享、回收站等产品行为；这些行为是否因家庭节点配对流程而调整，需要后续同步核定

本文先冻结目标边界和 MVP 通信方案。现有代码和数据迁移在该草案通过验证后分阶段执行，不因本文直接删除。

## 2. 目标与结论

### 2.1 目标

- 一台使用家庭网络的 Linux 笔记本承载账号、业务 API、数据库和照片对象存储。
- 照片上传、下载和业务查询均在 App 与家庭节点之间直接进行。
- ECS 只承担节点在线登记、会话撮合、WebRTC 信令和 STUN，不转发账号请求或照片流量。
- 家庭公网地址变化、普通 NAT 和移动网络切换由 WebRTC ICE 处理，不要求 App 保存固定家庭公网 IP。
- MVP 不实现媒体端到端加密，但保留传输加密、密码保护、节点身份校验和文件完整性校验。

### 2.2 架构结论

| 项目 | MVP 决策 |
| --- | --- |
| 业务真实来源 | 家庭节点上的 PostgreSQL |
| 照片真实来源 | 家庭节点本地数据盘，由 `ObjectStore` 模块管理 |
| ECS 职责 | 协调服务 + STUN；不部署业务 API、业务数据库或照片存储 |
| App 与家庭节点连接 | WebRTC PeerConnection + DataChannel |
| ECS 与两端连接 | HTTPS/WSS 信令；家庭节点主动发起并保持出站连接 |
| NAT 穿透 | ICE host/server-reflexive candidate + 自建 STUN |
| TURN | MVP 不启用；无法直连时明确失败，不由 ECS 中继业务流量 |
| 对象存储实现 | MVP 使用本地文件系统适配器，不在公网暴露 S3/OSS 接口 |
| 媒体加密 | MVP 不做客户端媒体加密；WebRTC 的 DTLS 传输加密仍为强制能力 |
| 节点身份 | App 固定家庭节点的长期 DTLS 证书 SHA-256 指纹 |
| 高可用 | MVP 单家庭节点、单 ECS 协调节点，不承诺高可用 |

## 3. 系统拓扑与边界

```text
                    仅协调流量
            HTTPS/WSS + ICE signaling
     ┌────────────── ECS ──────────────┐
     │  Coordinator                    │
     │  - 节点在线登记与心跳           │
     │  - 会话创建、SDP/ICE 转交       │
     │  - 速率限制、短期会话状态       │
     │  STUN                           │
     │  - 返回 NAT 映射地址            │
     └───────────┬───────────┬─────────┘
                 │           │
          WSS 出站连接   HTTPS/WSS
                 │           │
     家庭网络    │           │    互联网/移动网络
┌────────────────▼──┐     ┌──▼─────────────────────┐
│ 家庭 Linux 节点    │◀═══▶│ Android/iOS/HarmonyOS │
│ mineg-home         │ WebRTC DataChannel          │
│ PostgreSQL         │ 业务请求 + 照片上传/下载    │
│ Local ObjectStore  │     │                        │
└────────────────────┘     └────────────────────────┘

═══ 业务数据直连；不经过 ECS
─── 在线登记、SDP、ICE candidate、心跳；不包含照片正文
```

### 3.1 家庭节点负责

- 手机号注册、登录、审核、会话和账号资料。
- 私人媒体、家庭共享、回收站、审计等全部业务规则。
- PostgreSQL 业务数据和本地对象文件的一致性管理。
- 照片上传接收、断点续传、校验、落盘、读取和下载发送。
- WebRTC PeerConnection 的 answer、ICE candidate 和 DataChannel 协议处理。
- 以节点身份主动连接 ECS；家庭路由器无需固定公网 IP。

家庭节点不把数据库、管理端口、对象目录或 S3/OSS 端口直接暴露到公网。

### 3.2 ECS 协调节点负责

- 校验并登记一个家庭节点的 `home_id`、节点凭据、版本和在线状态。
- 接收 App 的短期连接请求，把 SDP offer/answer 和 trickle ICE candidate 转交给正确家庭节点。
- 提供 STUN，使双方获得 server-reflexive candidate。
- 对 `home_id`、来源 IP、设备和会话创建执行限流与过期清理。
- 返回 `offline`、`busy`、`signaling_timeout`、`ice_failed` 等协调结果。
- 记录不含业务内容的可观测数据：会话 ID、状态、耗时、候选类型和错误分类。

ECS 明确不负责：

- 不处理注册、登录、媒体列表、上传、下载、分享和回收站请求。
- 不保存手机号、密码哈希、Access Token、照片元数据或照片正文。
- 不提供反向代理、TCP 隧道、TURN relay 或任何业务数据中继。
- 不向 App 返回一个被当作固定 API 地址的“家庭公网 IP”。WebRTC 必须交换并验证 ICE candidate pair；单独返回 IP 不能解决端口映射、CGNAT 和网络切换。

### 3.3 App 负责

- 保存家庭连接配置：协调服务地址、不可猜测的 `home_id`、家庭节点 DTLS 指纹。
- 通过 ECS 建立信令会话，作为 SDP offerer 和 ICE controlling 端发起连接。
- WebRTC 建立后，在控制 DataChannel 上完成账号认证和业务 RPC。
- 在批量 DataChannel 上进行分块上传、下载、背压和断点续传。
- 保存家庭节点签发的 Access/Refresh Token；Token 不交给 ECS。

## 4. 家庭定位、配对与节点身份

### 4.1 首次初始化

1. 运维人员在家庭局域网或笔记本本机初始化 `mineg-home`。
2. 家庭节点生成随机 128 位以上的 `home_id`、256 位节点注册密钥和长期 WebRTC DTLS 证书。
3. ECS 只保存 `home_id`、节点注册密钥哈希、节点状态和必要版本信息。
4. 家庭节点显示一次性配对二维码，内容至少包含：
   - 协调服务 HTTPS 地址；
   - `home_id`；
   - 家庭节点长期 DTLS 证书的 SHA-256 指纹；
   - 配置格式版本。
5. App 扫码后把连接配置保存到平台安全存储。

`home_id` 是路由定位符，不是账号凭据。它必须不可枚举，但家庭节点仍必须对每个业务请求执行账号认证和授权。

### 4.2 为什么必须固定 DTLS 指纹

WebRTC DataChannel 强制使用 SCTP over DTLS，传输天然具备机密性、来源认证和完整性。但是 SDP 和证书指纹通过信令服务交换；如果 App 无条件信任 ECS 提供的指纹，错误或被入侵的协调节点可能把 App 引向伪造节点。

MVP 使用配对二维码获得节点指纹，并在接受 SDP answer 前核对 `a=fingerprint`。节点证书持久化保存，轮换证书时所有 App 需要在可信局域网重新确认。这样不增加媒体应用层加密，也能建立最小的家庭节点身份边界。

### 4.3 账号与协调权限分离

- App 在 WebRTC 建立后直接向家庭节点注册或登录。
- 密码和家庭节点签发的 Token 只在 DTLS DataChannel 中传输。
- ECS 不验证业务 Access Token，也不读取业务请求。
- MVP 可允许持有有效 `home_id` 和指纹的 App 请求一次信令会话；ECS 和家庭节点都必须限流。
- 后续如需撤销单设备的协调权限，可增加家庭节点签发、ECS 仅验签的短期 route ticket，不把业务会话复制到 ECS。

## 5. WebRTC 建连协议

### 5.1 固定角色

- App：offerer、ICE controlling。
- 家庭节点：answerer、ICE controlled。
- ECS：只转交信令消息，不修改 SDP 或 candidate。
- 双方使用 trickle ICE，候选产生后立即发送，减少首连时间。

### 5.2 信令通道

家庭节点长期保持一个到 ECS 的 WSS 出站连接。App 在需要访问时建立短期 WSS 或 HTTPS + WSS 会话。

建议的信令消息：

| 消息 | 方向 | 作用 |
| --- | --- | --- |
| `node.register` | 家庭节点 → ECS | 使用节点凭据登记 `home_id`、版本和能力 |
| `node.heartbeat` | 家庭节点 → ECS | 维持在线状态，不携带业务数据 |
| `session.create` | App → ECS | 为指定 `home_id` 创建 60 秒撮合会话 |
| `session.offer` | App → 家庭节点 | 转交 SDP offer |
| `session.answer` | 家庭节点 → App | 转交 SDP answer |
| `ice.candidate` | 双向 | 转交 trickle ICE candidate |
| `session.close` | 任一端 → ECS | 结束信令状态，不能关闭已经建立的 P2P 数据通道 |

所有消息包含协议版本、`session_id`、单调递增序号和过期时间。ECS 不持久保存 SDP 与 candidate；短期内存状态在成功或 60 秒超时后清理。

### 5.3 建连顺序

1. 家庭节点通过 WSS 注册并持续心跳。
2. App 请求 `session.create`；ECS 先判断节点是否在线和是否超过并发限制。
3. App 创建 PeerConnection，配置 ECS 上的 STUN 地址并生成 offer。
4. ECS 原样转发 offer；家庭节点核对协议版本并生成 answer。
5. App 在设置远端描述前核对已固定的 DTLS 指纹。
6. 双方通过 ECS 交换 trickle ICE candidate，并直接执行 ICE connectivity checks。
7. candidate pair 成功后建立 DTLS、SCTP 和 DataChannel。
8. DataChannel 打开后，App 直接向家庭节点执行认证与业务请求。
9. ECS 立即删除 SDP/candidate 会话内容，只保留有限状态指标。

### 5.4 直连能力边界

STUN 只帮助发现 NAT 映射并进行连通性检查，不是中继。以下情况可能无法直连：

- 家庭和 App 两端均处于不兼容的对称 NAT/CGNAT 后；
- 任一网络阻断 UDP，且所选 WebRTC 栈没有可用的 ICE-TCP 直连候选；
- 企业网络只允许经 HTTP 代理访问公网；
- 家庭节点休眠、断网、合盖停机或 WSS 心跳中断。

TURN 正是用于“无法直接通信时经中间节点中继”的协议，因此与“MVP 的 ECS 不转发业务流量”约束冲突。本阶段不部署 TURN，也不把 TURN 伪装成协调流量。直连失败时 App 显示明确错误，建议切换网络、使用家庭 Wi-Fi 或稍后重试。上线前必须用实际家庭宽带和三家运营商移动网络测量直连成功率，再决定后续是否允许一个可配置、按流量计费的 TURN 兜底。

## 6. DataChannel 业务协议

### 6.1 通道划分

每个 PeerConnection 建立两个可靠、有序 DataChannel：

| Label | 用途 | 优先级 |
| --- | --- | --- |
| `mineg.control.v1` | 登录、查询、上传控制、进度、错误、心跳 | 高 |
| `mineg.bulk.v1` | 照片上传和下载二进制帧 | 普通 |

业务请求采用“逻辑 HTTP/RPC”语义，继续复用现有请求模型、错误码、幂等键和授权中间件，但传输层从公网 HTTPS Handler 改为 DataChannel Adapter。控制消息使用版本化 JSON；照片正文使用二进制帧，不做 Base64。

### 6.2 消息大小与背压

WebRTC DataChannel 保留消息边界，但不同实现可接受的最大消息不同；没有消息交错能力时，大消息还会阻塞同一 SCTP association 中的其他通道。MVP 采用保守约束：

- 单个 DataChannel wire message 最大 16 KiB。
- 一个可恢复文件块默认 1 MiB，由多个 wire message 组成。
- 发送方根据 `bufferedAmount` 实施高低水位背压；建议初值为 8 MiB/2 MiB，实测后调整。
- 控制消息不得携带照片正文，单条上限 16 KiB。
- 双方在 `hello` 中交换 `max_message_size`、块大小和协议版本，只能向下协商。

### 6.3 上传状态机

```text
CREATED -> RECEIVING -> VERIFYING -> COMMITTED
                    \-> FAILED
CREATED/RECEIVING -> EXPIRED
```

1. App 发送 `upload.create`，包含幂等键、媒体元数据、资源数量、每个资源长度和 SHA-256。
2. 家庭节点检查账号、配额和去重，返回 `upload_id`、对象 ID、块大小和已持久化的连续 offset。
3. App 在 bulk 通道发送二进制帧：`upload_id + resource_id + chunk_index + frame_index + payload`。
4. 家庭节点只允许写入服务生成的临时路径，按顺序组装并周期性持久化进度。
5. 每个 1 MiB 块完整落盘后返回累计确认；连接中断后 App 重新查询 offset，不重传已确认块。
6. App 发送 `upload.complete`；家庭节点核对总长度和 SHA-256。
7. 校验通过后，临时文件原子移动为正式对象，数据库事务把媒体置为可见。

SCTP 的可靠传输不能替代业务断点续传：进程退出、笔记本重启或 PeerConnection 重新建立后，仍需要数据库中的上传会话和已持久化 offset。

### 6.4 下载状态机

1. App 发送 `download.open(media_id, resource_id, offset)`。
2. 家庭节点重新校验所有权、分享状态和回收站状态。
3. 家庭节点返回长度、SHA-256、MIME 和允许的 offset。
4. 家庭节点通过 bulk 通道发送二进制帧，App 按背压写入临时文件。
5. App 校验总长度和 SHA-256 后才展示或写入系统相册。
6. 连接中断后，从已验证 offset 重新打开下载；不得把未校验临时文件当作完整原文件。

## 7. 家庭对象存储方案

### 7.1 MVP 选择

MVP 不部署公网阿里云 OSS，也不要求单机再运行一个 S3 兼容服务。Go 服务内部保留 `ObjectStore` 接口，第一实现为本地文件系统适配器：

```text
/srv/mineg-data/
  objects/
    avatars/<owner-id>/<object-id>.blob
    media/<owner-id>/<media-id>/<resource-id>.blob
  staging/
    uploads/<upload-id>/<resource-id>.part
  quarantine/
  backups/
```

- 对象 ID 全部由服务生成，不接受用户提供的路径片段。
- 数据目录不由 Nginx、静态文件服务或容器端口直接暴露。
- 正式对象只读写，不原地覆盖；编辑产生新对象版本。
- 临时文件完成长度和 SHA-256 校验后，通过同一文件系统内的原子 rename 提交。
- PostgreSQL 保存对象 ID、相对键、长度、摘要、状态和引用关系，不保存绝对宿主机路径。
- 在线逻辑删除只更新数据库状态；物理清理由独立任务按清单执行。

该方案提供“对象存储语义”，但单笔记本、单磁盘不提供 OSS 的多副本耐久性。接口保持可替换，后续可迁移到本地 S3 兼容服务、NAS 或云对象存储；App 不感知实现变化。

### 7.2 数据库与文件一致性

文件系统操作和 PostgreSQL 事务不能组成一个原子事务，使用显式状态协调：

1. 数据库创建 `upload_session` 和 `object` 的 `STAGING` 记录。
2. 服务只在 staging 目录写入。
3. 完整性校验通过后 rename 到正式路径。
4. 数据库事务把对象和媒体改为 `COMMITTED`。
5. 对账任务处理以下异常：
   - 有 staging 记录但超时：标记过期并回收临时文件；
   - 有正式文件但数据库仍为 `STAGING`：按 upload journal 完成或隔离；
   - 数据库为 `COMMITTED` 但文件缺失：标记存储损坏并告警，不静默返回空文件；
   - 无数据库引用的正式文件：移入 quarantine，人工确认后清理。

### 7.3 磁盘与备份

- 系统盘与照片数据盘应分离；数据盘使用 ext4 或 XFS。
- 家庭节点必须监控可用空间、inode、SMART、数据库和对象对账错误。
- 空间达到 80% 警告、90% 停止新上传，但仍允许查询和下载。
- 单盘不是备份。MVP 上线前至少配置一个独立目标的定时备份，并演练数据库与照片联合恢复。
- 可选使用 LUKS 全盘加密降低笔记本失窃风险；它属于部署层保护，不等同于客户端端到端加密。

## 8. MVP 必要安全范围

“暂不做加密功能”只表示不实现客户端媒体端到端加密和复杂家庭密钥体系，不能关闭协议强制加密或保存明文密码。

### 8.1 必须实现

- App/ECS、家庭节点/ECS 使用受信任证书的 HTTPS/WSS。
- App/家庭节点业务数据使用 WebRTC 强制的 DTLS DataChannel。
- App 在配对时固定家庭节点 DTLS 证书 SHA-256 指纹。
- 密码使用 Argon2id 和独立随机盐；不得保存可逆密码。
- Access/Refresh Token 使用密码学安全随机值，服务端只保存 Token 哈希，App 保存到平台安全存储。
- 节点注册密钥为随机 256 位值，ECS 只保存哈希；支持轮换和撤销。
- 文件落盘前校验声明长度、配额、对象归属和 SHA-256；防止路径穿越和任意文件写入。
- 日志不记录密码、Token、SDP 全文、ICE candidate 全文、照片正文或完整个人信息。
- ECS 对节点注册、会话创建和信令消息做大小限制、并发限制、速率限制和 60 秒过期。
- 家庭节点容器以非 root 用户运行；数据库和数据目录只对服务账号开放。

### 8.2 本期不实现

- 客户端媒体端到端加密、Media Key、Family Sharing Key 和 key envelope。
- 对象级应用加密、跨成员密钥轮换和密码派生的媒体包装密钥。
- ECS 无法解密信令内容的端到端信令加密。
- TURN 业务流量中继。
- 多家庭租户隔离、硬件安全模块和远程证明。

### 8.3 MVP 信任边界

- 家庭节点能够读取照片明文，家庭节点管理员也可能通过主机权限读取数据盘。
- ECS 能看到 `home_id`、连接时间、源 IP、SDP/ICE 中的候选地址和协议元数据，但正常情况下看不到 DataChannel 业务正文。
- 被解锁或被入侵的家庭节点、App 设备不在 MVP 保护范围内。
- 固定 DTLS 指纹后，ECS 不能在不触发指纹不匹配的情况下把 App 静默指向另一家庭节点。

## 9. 部署设计

### 9.1 家庭 Linux 笔记本

建议进程边界：

```text
Docker Compose / systemd
  mineg-home       Go 模块化单体 + WebRTC + ObjectStore
  postgres         业务数据库，仅容器网络监听
  mineg-reconcile  可由 mineg-home 内部定时任务承担
```

基础要求：

- Linux x86_64，固定受支持发行版和内核版本。
- 接通电源，禁用自动休眠和合盖休眠，配置断电恢复开机。
- 使用有线网络优先；Wi-Fi 作为次选。
- 时间同步正常，系统和容器日志有轮转上限。
- 只需要主动访问 ECS 的 TCP 443 和 STUN UDP 3478；不直接开放 PostgreSQL、对象目录或业务 HTTP 端口到公网。
- 容器设置自动重启和健康检查；节点离线、磁盘不足和对账失败必须告警。

### 9.2 ECS 协调节点

建议进程边界：

```text
reverse-proxy      TLS 终止，公网 TCP 443
mineg-coordinator  WSS 信令、节点目录、限流和短期会话
stun               公网 UDP 3478，只启用 STUN，不分配 TURN relay
```

- MVP 单节点可使用 SQLite WAL 保存节点登记、凭据哈希和限流状态；SDP/ICE 只在内存短期保存。
- 公网只开放 TCP 443、UDP 3478，以及严格受限的运维入口。
- 不开放 TURN relay 端口范围，不配置业务反向代理到家庭节点。
- 监控在线节点数、心跳延迟、撮合成功率、ICE candidate 类型、直连成功率和信令出入流量。

## 10. 管理后台边界

账号数据迁到家庭节点后，现有 ECS Web 管理端不能继续直接调用原 ECS 业务 API。MVP 推荐以下边界：

- 管理前端静态资源可随家庭节点提供，只在家庭局域网访问。
- 管理 API 只在家庭节点本机或局域网 HTTPS 入口开放，不经 ECS 公网代理。
- 远程审核暂不作为本轮基础设施 MVP；如必须远程管理，应让 Web 管理端同样通过 WebRTC DataChannel 连接家庭节点，而不是恢复 ECS 业务反向代理。
- 管理员仍不能绕过业务授权浏览成员照片；但由于媒体不做端到端加密，拥有家庭节点 root/磁盘权限的运维人员在技术上能够读取照片，这一点必须在产品隐私说明中如实表达。

## 11. 故障行为

| 故障 | App 行为 | 系统行为 |
| --- | --- | --- |
| 家庭节点离线 | 显示“家庭存储节点离线”，可重试 | ECS 不缓存业务请求 |
| ECS 不可用 | 无法创建新的远程会话 | 已建立的 P2P 会话继续到连接自然结束 |
| ICE 直连失败 | 建议切换网络/家庭 Wi-Fi/稍后重试 | 不回退到 ECS 中继 |
| WebRTC 中途断开 | 保留已确认 offset，重连后续传 | staging 文件和会话按 TTL 保留 |
| 家庭节点磁盘达到 90% | 停止新上传，允许下载 | 告警并返回稳定空间不足错误 |
| 文件摘要不符 | 不提交媒体，要求重传失败资源 | 隔离临时文件并记录审计 |
| PostgreSQL 不可用 | 停止业务写入和对象提交 | 不产生无元数据的正式对象 |
| 指纹不匹配 | 阻止连接并要求可信重新配对 | 不允许用户点击忽略后继续 |

## 12. 现有实现迁移影响

| 现有能力 | vNext 处理 |
| --- | --- |
| Go 模块化单体、chi、pgx、sqlc、goose | 保留，部署目标从 ECS 移到家庭节点 |
| 账号、管理员、媒体元数据表 | 保留并迁到家庭 PostgreSQL |
| ECS RAM Role、STS、签名 PUT/GET | 停止扩展；由本地 `ObjectStore` 和 DataChannel 上传下载替代 |
| 阿里云 OSS multipart | 替换为家庭节点 staging 文件、业务块确认和断点续传 |
| HTTPS REST/JSON Handler | 业务 Service/Repository 保留；新增 WebRTC RPC Transport Adapter |
| OpenAPI | 保留为业务模型和兼容测试来源；增加 DataChannel framing 契约 |
| key bundle、family envelope、客户端密文格式 | 移出 vNext MVP 验收；已实现结构暂不删除，待数据迁移方案确认 |
| ECS 上的 PostgreSQL 业务库 | 迁移到家庭节点；ECS 只保留协调服务最小状态 |
| Web 管理端 | MVP 改为家庭局域网访问；远程 WebRTC 接入另立阶段 |

建议实施顺序：

1. P0-V0：按[家庭 Linux 节点 V0 最小验证部署手册](../Deployment/private-album-infra/docs/04-home-node-minimum-validation.md)部署当前 REST API，验证笔记本本机和家庭局域网可达性；该结果不代表 WebRTC 已完成。
2. P0-V1：用 App Debug POC 与家庭节点 POC 手工交换 SDP/ICE，验证不经过 ECS 的 WebRTC DataChannel 直连，并测真实家庭宽带和移动网络。
3. P1：实现 `mineg-coordinator`、节点长期 WSS、心跳、限流和短期会话。
4. P2：在现有 Go 后端增加 WebRTC Transport Adapter，让 `platform/probe` 和登录首先跑通。
5. P3：实现本地 `ObjectStore`、上传状态机、16 KiB 帧、1 MiB 可恢复块和断点续传。
6. P4：迁移 PostgreSQL 到家庭节点，完成账号、照片、回收站和对象联合备份/恢复演练。
7. P5：接入 Android，再按三端一致性契约补齐 iOS 与 HarmonyOS。
8. P6：根据实网直连率决定是否接受 TURN、IPv6 直连或其他兜底方案。

## 13. MVP 验收条件

- 家庭节点无固定公网 IP、家庭路由器未配置端口转发时，App 可通过 ECS 完成信令并在可穿透网络上建立直连。
- ECS 下线前建立的 DataChannel 不依赖 ECS 传输照片；新的会话明确失败。
- 在 ECS 网络观测中，上传 1 GiB 文件不会产生接近 1 GiB 的协调节点出站流量。
- ECS 数据库和日志中不存在手机号、密码、业务 Token、照片元数据和照片正文。
- App 拒绝与配对指纹不一致的家庭节点建立业务会话。
- 100 MiB 和 2 GiB 文件可上传、下载并通过 SHA-256 校验。
- 上传中断、App 进程退出、家庭节点服务重启后，可从已确认 offset 继续。
- 磁盘空间不足、家庭节点离线、ICE 失败和指纹错误具有不同且可操作的错误提示。
- PostgreSQL 与对象目录联合备份可以在空白家庭节点恢复，抽样照片摘要一致。
- 实网测试报告记录家庭宽带、三家移动网络、家庭 Wi-Fi、双 CGNAT 和 UDP 受限网络的成功率；不把 TURN 缺失造成的失败误判为普通代码缺陷。

## 14. 标准依据

- [RFC 8445: Interactive Connectivity Establishment (ICE)](https://www.rfc-editor.org/rfc/rfc8445.html)：candidate 交换和连通性检查。
- [RFC 8831: WebRTC Data Channels](https://www.rfc-editor.org/rfc/rfc8831.html)：SCTP over DTLS、可靠/有序通道和大消息限制。
- [RFC 8838: Trickle ICE](https://www.rfc-editor.org/rfc/rfc8838.html)：增量交换 ICE candidate。
- [RFC 8656: TURN](https://www.rfc-editor.org/rfc/rfc8656.html)：直连不可用时的中继能力；本 MVP 明确不启用。
- [W3C WebRTC 1.0](https://www.w3.org/TR/webrtc/)：PeerConnection 和 RTCDataChannel API 行为。

## 15. 待实测后冻结的参数

以下不是开放的架构方向，而是必须通过 P0/P3 实验确定的实施参数：

- 实际采用的 Go 和三端 WebRTC 实现及其长期维护策略。
- 家庭宽带与各移动网络的 ICE 直连成功率和平均建连时间。
- 16 KiB wire message、1 MiB 恢复块和 8/2 MiB 背压水位的吞吐表现。
- 同时上传文件数、家庭节点 CPU/内存上限和磁盘写入策略。
- 上传 staging TTL、单账号配额和磁盘告警阈值的最终值。
- 是否需要 IPv6 优先、ICE-TCP 或独立 TURN 兜底阶段。
