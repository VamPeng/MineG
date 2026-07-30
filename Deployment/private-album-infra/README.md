# 私人相册部署记录

这个目录记录私人相册公网 ECS 与私有阿里云 OSS 的部署和验证流程。内容按“可以上传到 Git 托管平台”的标准整理，不保存真实云资源标识或任何凭证。

## 当前执行入口

目标 ECS 从以下文档开始：

1. [服务器只读检查](./docs/01-server-preflight.md)
2. [OSS 与 RAM Role 验证](./docs/02-oss-ram-role.md)
3. [应用部署清单](./docs/03-deployment-checklist.md)

现行拓扑由 ECS 承载 Go 业务服务和 PostgreSQL，移动端与管理端通过公网 HTTPS 访问；媒体保存在私有 OSS。上传和加载过程不做客户端应用层媒体加密，必须使用 HTTPS/TLS、短期授权、对象级权限和 SHA-256 完整性校验。

## ECS + 阿里云 OSS 进度

- [x] ECS 基础系统可登录，系统与架构已确认
- [x] Docker、Docker Compose、Git 已安装
- [x] 磁盘、内存、Swap 和监听端口已检查
- [x] ECS 实例 RAM 角色可签发临时凭证
- [x] OSS 目标 Bucket 的早期列举、上传和读取权限已验证
- [x] OSS 原文件删除保护已验证
- [ ] 阶段 03 在线角色已移除 Bucket/全部 multipart 列举，并完成定向对象与媒体 multipart 回归
- [ ] 确认权限测试对象已由 Bucket 所有者清理
- [ ] 确定应用代码仓库与部署方式
- [ ] 配置域名、TLS、应用环境变量和备份策略
- [ ] 部署并完成健康检查

## 安全约定

1. 不在 Git 中保存 AccessKey、STS Token、SSH 私钥、密码或 Cookie。
2. 主机地址、账号标识、域名和真实运行参数只写入被 Git 忽略的本地配置。
3. PostgreSQL 和容器内部端口不得公网开放；对外 API 只通过受信任证书的 HTTPS 暴露。
4. OSS Bucket 不公开；ECS 使用 RAM Role 临时凭据，在线角色不得永久删除原文件。
5. 媒体对象不做客户端加密，测试和日志中不得使用或输出真实个人媒体。
6. 提交前运行 `./scripts/scan-secrets.sh`。

## OSS 检查入口

从当前目录执行：

```bash
cp .env.example .env
```

在本地或目标服务器填写 `.env` 中的真实值。`.env` 已被 Git 忽略。

服务器基础检查：

```bash
./scripts/check-server.sh
```

OSS 定向只读检查（仅在绑定了实例 RAM 角色的 ECS 内运行）：

```bash
./scripts/check-oss-readonly.sh
```

提交前敏感信息检查：

```bash
./scripts/scan-secrets.sh
```

## Git 提交钩子

从 MineG 仓库根目录启用：

```bash
git config core.hooksPath Deployment/private-album-infra/.githooks
```

## 目录

```text
.
├── .env.example
├── .githooks/
│   └── pre-commit
├── AGENTS.md
├── docs/
│   ├── 01-server-preflight.md
│   ├── 02-oss-ram-role.md
│   └── 03-deployment-checklist.md
├── scripts/
│   ├── check-oss-readonly.sh
│   ├── check-server.sh
│   └── scan-secrets.sh
├── .gitignore
├── README.md
└── SECURITY.md
```
