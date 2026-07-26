# 阶段 00 运行交接

## 构建物

- 后端：从仓库根目录执行 `docker build -f Service/Dockerfile -t mineg-api:0.0.1 Service`。
- 管理端：从仓库根目录执行 `docker build -f Frontend/Dockerfile -t mineg-admin:0.0.1 .`。
- Android：`Mobile/MineG_Android/app/build/outputs/apk/debug/app-debug.apk`，仅供 M0 基座验证。

## 路由与 HTTPS

公网入口必须终止 HTTPS，并把同一站点下的 `/api/*`、`/health/*` 转发到 API；SPA 其余路径回退到 `index.html`。容器端口只开放在内网。管理端不得跨站保存 Token，后续 Session Cookie 固定使用 `Secure`、`HttpOnly` 和合适的 `SameSite`。

Android Release 的 `minegReleaseApiBaseUrl` 必须在构建时注入无凭据 HTTPS 地址。Debug 开发包可通过 `minegDebugApiBaseUrl` 访问 RFC1918 局域网 HTTP 地址；该例外只存在于 Debug 清单和传输策略，Release 仍拒绝明文流量。默认 `https://api.invalid` 会明确失败，避免未配置环境被误认为成功。

## 发布顺序与探针

1. 备份 PostgreSQL 18 测试或部署实例。
2. 独立运行 `mineg-migrate up`。
3. 部署 API 并等待 `/health/ready` 返回 200。
4. 发布管理端静态资源。
5. 使用 `GET /api/v1/platform/probe` 验证 request ID、JSON 与 UTC 时间。

阶段 00 没有账号、管理员会话、媒体或真实 OSS/STS 业务配置。在线运行身份不得包含 OSS 永久删除权限。
