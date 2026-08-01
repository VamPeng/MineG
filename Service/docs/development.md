# 后端本地开发

## 前置条件

- Go 1.26.5（`go` 的自动工具链会按 `go.mod` 获取）。
- Docker/Compose 与 PostgreSQL 18 客户端工具。
- 真实配置只写入未纳入版本控制的 `Service/.env`。

## 启动

不使用 Docker 时，推荐在 macOS 上通过根目录脚本一次完成编译、Homebrew PostgreSQL 检查、建库、migration、首次管理员初始化和 API 启动：

```bash
cd Service
./local-backend.sh
```

需要从干净的阶段 01 本地数据库重跑时使用：

```bash
./local-backend.sh --reset-db
```

`--reset-db` 只允许删除并重建 `mineg_stage01_local`。脚本不读取 `.env`，避免旧 Docker/部署连接串被误用；可通过 `MINEG_LOCAL_DB_*`、`MINEG_ADMIN_ORIGIN` 和 `MINEG_BOOTSTRAP_ADMIN_*` 环境变量覆盖本地默认值。只编译三个可执行文件而不启动依赖时运行 `./local-backend.sh --build-only`，产物位于 `Service/bin/`。API readiness 通过后，脚本会输出后端/API 地址、前端代理变量、管理端 Origin、Android 构建参数和 `adb reverse` 命令。

Docker/Compose 启动方式：

```bash
cd Service
docker compose up -d postgres
cp .env.example .env
set -a && source .env && set +a
go run ./cmd/migrate up
MINEG_BOOTSTRAP_ADMIN_USERNAME=reviewer \
MINEG_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-strong-secret' \
go run ./cmd/admin-bootstrap
go run ./cmd/api
```

`admin-bootstrap` 只允许在空的 `admin_users` 表上成功一次；密码不会写入日志。移动端注册、登录、单媒体上传和管理员接口均位于 `/api/v1`。管理端状态变更要求与 `MINEG_ADMIN_ORIGIN` 完全一致的 `Origin`、有效 Session Cookie 和 `X-CSRF-Token`。

未设置 OSS 变量时，`local-backend.sh` 继续使用 `DisabledMediaObjects`，头像和媒体对象接口明确返回 `OBJECT_STORAGE_UNAVAILABLE`，不会回退为后端正文代理。需要连接隔离开发 Bucket 时，先设置四个非 Secret 参数，再使用安全包装脚本：

```bash
export MINEG_OSS_REGION=cn-hangzhou
export MINEG_OSS_BUCKET='<development-bucket>'
export MINEG_OSS_PUBLIC_ORIGIN='https://oss-cn-hangzhou.aliyuncs.com'
export MINEG_LOCAL_OSS_ROLE_ARN='acs:ram::<account-id>:role/<local-oss-role>'
./local-oss-backend.sh
```

也可以把上述四个非 Secret 参数写入被 Git 忽略的 `.env.local-oss`；脚本会安全解析白名单字段，显式环境变量优先。不得在该文件中加入 AccessKey 或 STS 凭据。

脚本通过阿里云 CLI 验证调用者、执行一次 3600 秒 `AssumeRole`、解析 `AccessKeyId`、`AccessKeySecret`、`SecurityToken` 与 `Expiration`，随后清除长期调用者凭据，只把临时 STS 注入 Service。调用者 AccessKey 未预先导出时脚本会无回显提示输入；不得把 Secret 写在命令行、`.env`、CLI Profile、日志或截图中。临时凭据到期后需停止并重新运行脚本。完整权限边界和验收项见[《OSS 身份、ECS RAM Role 与 App 上传授权》](../../Deployment/private-album-infra/docs/02-oss-ram-role.md)。

首次联调先执行 `./local-oss-backend.sh --check-only`。它会创建一个不会完成为对象的 128 KiB 临时 multipart、验证 part 上传与列出，然后主动中止；同时确认列桶、跨前缀读取和对象删除均返回 `AccessDenied`。即使进程异常中断，未完成分片也会被 7 天生命周期规则兜底清理。检查通过后再执行 `./local-oss-backend.sh` 启动服务。

需要在当前电脑持久保存本地调用者 AccessKey 时，执行 `./save-local-aliyun-secret.sh` 并按提示输入。脚本把两个长期凭据写入项目根目录下 Git 忽略的 `Secret/aliyun-local.env`，目录权限为 `0700`、文件权限为 `0600`。`local-oss-backend.sh` 只解析这两个白名单字段且显式环境变量优先；不会 `source` 或执行 Secret 文件内容。该文件是本机明文凭据，只能作为开发机本地存储，不得提交、复制到聊天或用于生产。

探针为 `GET /api/v1/platform/probe`。它仅验证 Android/C++ 到 HTTPS JSON API 的纵向链路，不是业务接口。生产环境必须由入口代理终止 HTTPS；服务进程只监听内网 HTTP。

## 质量门槛

```bash
make format
make lint
make test
make generate
git diff --exit-code
```

集成测试仅允许连接专用测试库；它覆盖 migration、无 key bundle 注册、注册幂等、错误凭据、Token 轮换/重放、退出撤销、管理员会话、并发审核直接 `APPROVED`、不创建 key-grant 任务、资料、头像元数据、旧单媒体会话/分片/完成/同账号去重，以及管理端/移动端权限隔离：

```bash
MINEG_TEST_DATABASE_URL='postgres://mineg:...@127.0.0.1:5432/mineg_test?sslmode=disable' make test-integration
```

`/health/live` 只表示进程存活；`/health/ready` 会实际检查 PostgreSQL。服务收到 SIGTERM 后停止接单并在 `MINEG_SHUTDOWN_TIMEOUT` 内等待在途请求。
