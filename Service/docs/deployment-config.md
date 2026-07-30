# Deployment 配置交接

OSS 临时云凭据、ECS RAM Role、App 签名 URL 与本地 STS 联调模式的完整说明见[《OSS 身份、ECS RAM Role 与 App 上传授权》](../../Deployment/private-album-infra/docs/02-oss-ram-role.md)。

部署系统必须注入以下配置，不得写入镜像或仓库：

| 变量 | 必需 | 说明 |
| --- | --- | --- |
| `MINEG_ENV` | 是 | 部署环境固定为 `deployment` |
| `MINEG_DATABASE_URL` | 是 | PostgreSQL 18 TLS 连接串，由密钥管理注入 |
| `MINEG_ADMIN_ORIGIN` | 是 | 管理端 HTTPS Origin，必须精确到 scheme、host 和 port，不能含路径 |
| `MINEG_CURSOR_HMAC_KEY` | 是 | 至少 32 字符的游标签名密钥，由密钥管理注入且跨实例一致 |
| `MINEG_OSS_REGION` | 是 | 私有 OSS Bucket 所在地域，例如 `cn-hangzhou` |
| `MINEG_OSS_BUCKET` | 是 | 私有 Bucket 名称，不得包含路径或 URL |
| `MINEG_OSS_INTERNAL_ORIGIN` | 是 | ECS 到 OSS 的内网 HTTPS Origin，例如 `https://oss-cn-hangzhou-internal.aliyuncs.com` |
| `MINEG_OSS_ECS_RAM_ROLE` | 是 | 绑定到 ECS 实例的 RAM 角色名；服务仅通过 IMDSv2 获取临时凭据 |
| `MINEG_HTTP_ADDRESS` | 否 | 内网监听地址，默认 `:8080` |
| `MINEG_REQUEST_TIMEOUT` | 否 | API 请求上限，默认 `15s` |
| `MINEG_SHUTDOWN_TIMEOUT` | 否 | 在途请求排空上限，默认 `20s` |
| `MINEG_READ_HEADER_TIMEOUT` | 否 | 请求头读取上限，默认 `5s` |

发布顺序固定为：数据库备份 → 独立运行 `/app/mineg-migrate up` → 首次部署时以一次性 Secret 执行 `/app/mineg-admin-bootstrap` → 滚动更新 `mineg-api`。bootstrap 成功后立即销毁一次性管理员密码 Secret；重复执行会失败。

入口代理负责 HTTPS 和同源 `/api` 路由；`mineg_admin_session` 始终设置 `Secure`、`HttpOnly`、`SameSite=Strict`，因此本地或部署环境都不得通过明文 HTTP 验收管理端登录。管理端 Session 30 分钟闲置失效、8 小时绝对失效。不得让容器端口直接暴露公网。

头像使用精确对象键、精确长度/类型/摘要的短期签名 PUT，客户端直传私有 OSS；完成接口会通过内网 `HeadObject` 复核元数据，读取时再签发短期 GET。服务不持有长期 AccessKey，也不代理头像或媒体正文。ECS 在线身份不得包含 `DeleteObject`、`DeleteObjectVersion`、列举 Bucket 或修改 Bucket 配置的权限。

媒体使用独立 `media/<owner>/<upload-session>/` 前缀和 OSS multipart。API 返回的是逐对象、逐 part、带过期时间的签名 PUT 授权，而不是可被客户端复用的 RAM 凭据；这是阶段 03“受限 STS”用途的更窄授权实现。客户端响应不得包含 AccessKey、SecurityToken、列桶、读取或删除能力。服务端完成前使用 `ListParts` 与 `HeadObject` 复核密文元数据；部署回归必须覆盖过期签名、越权对象键和管理员 Cookie 拒绝。
