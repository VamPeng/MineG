# 后端本地开发

## 前置条件

- Go 1.26.5（`go` 的自动工具链会按 `go.mod` 获取）。
- Docker/Compose 与 PostgreSQL 18 客户端工具。
- 真实配置只写入未纳入版本控制的 `Service/.env`。

## 启动

```bash
cd Service
docker compose up -d postgres
cp .env.example .env
set -a && source .env && set +a
go run ./cmd/migrate up
go run ./cmd/api
```

探针为 `GET /api/v1/platform/probe`。它仅验证 Android/C++ 到 HTTPS JSON API 的纵向链路，不是业务接口。生产环境必须由入口代理终止 HTTPS；服务进程只监听内网 HTTP。

## 质量门槛

```bash
make format
make lint
make test
make generate
git diff --exit-code
```

集成 migration 测试仅允许连接专用测试库：

```bash
MINEG_TEST_DATABASE_URL='postgres://mineg:...@127.0.0.1:5432/mineg_test?sslmode=disable' make test-integration
```

`/health/live` 只表示进程存活；`/health/ready` 会实际检查 PostgreSQL。服务收到 SIGTERM 后停止接单并在 `MINEG_SHUTDOWN_TIMEOUT` 内等待在途请求。
