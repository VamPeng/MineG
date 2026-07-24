# 私人相册部署记录

这个目录用于记录私人相册服务的服务器准备、OSS 权限验证和后续部署流程。内容按“可以上传到 Git 托管平台”的标准整理，不保存真实云资源标识或任何凭证。

## 当前进度

- [x] ECS 基础系统可登录，系统与架构已确认
- [x] Docker、Docker Compose、Git 已安装
- [x] 磁盘、内存、Swap 和监听端口已检查
- [x] ECS 实例 RAM 角色可签发临时凭证
- [x] OSS 目标 Bucket 的列举、上传和读取权限已验证
- [x] OSS 原文件删除保护已验证
- [ ] 确认权限测试对象已由 Bucket 所有者清理
- [ ] 确定应用代码仓库与部署方式
- [ ] 配置域名、TLS、应用环境变量和备份策略
- [ ] 部署并完成健康检查

## 安全约定

1. 不在 Git 中保存 AccessKey、STS Token、SSH 私钥、密码或 Cookie。
2. 实例 ID、Bucket 名称、RAM 角色名、账号 ID、域名和公网 IP 只写入本地 `.env`。
3. ECS 内的应用通过实例 RAM 角色获取临时凭证，不配置长期 AccessKey。
4. OSS 原文件默认禁止由应用角色永久删除；相册删除功能优先采用逻辑回收站。
5. 提交前运行 `./scripts/scan-secrets.sh`。

## 开始使用

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
