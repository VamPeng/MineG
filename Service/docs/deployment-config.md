# Deployment 配置交接

部署系统必须注入以下配置，不得写入镜像或仓库：

| 变量 | 必需 | 说明 |
| --- | --- | --- |
| `MINEG_ENV` | 是 | 部署环境固定为 `deployment` |
| `MINEG_DATABASE_URL` | 是 | PostgreSQL 18 TLS 连接串，由密钥管理注入 |
| `MINEG_HTTP_ADDRESS` | 否 | 内网监听地址，默认 `:8080` |
| `MINEG_REQUEST_TIMEOUT` | 否 | API 请求上限，默认 `15s` |
| `MINEG_SHUTDOWN_TIMEOUT` | 否 | 在途请求排空上限，默认 `20s` |
| `MINEG_READ_HEADER_TIMEOUT` | 否 | 请求头读取上限，默认 `5s` |

发布顺序固定为：数据库备份 → 独立运行 `mineg-migrate up` → 滚动更新 `mineg-api`。入口代理负责 HTTPS、同源 API 路由和后续安全 Cookie；不得让容器端口直接暴露公网。

阶段 00 的 OSS/STS 只有内存假实现。后续接入 ECS RAM Role 时，在线身份不得包含 `DeleteObject` 或 `DeleteObjectVersion`。
