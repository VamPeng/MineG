# 私人相册部署记录

> 迁移提示：2026-07-29 起，目标架构调整为“家庭 Linux 节点承载业务与本地对象存储，ECS 只负责 WebRTC 协调和 STUN”。新方案见[家庭节点私人云架构 vNext](../../Requirement/private-cloud-architecture-vnext.md)。本目录已有 ECS RAM Role 与阿里云 OSS 内容暂作为历史验证记录，不代表新的最终部署拓扑。

这个目录用于记录私人相册家庭节点、协调节点和历史 OSS 验证流程。内容按“可以上传到 Git 托管平台”的标准整理，不保存真实云资源标识或任何凭证。

## 当前执行入口

支持服务部署的家庭 Linux 笔记本从以下文档开始：

1. [家庭 Linux 节点 V0 最小验证部署手册](./docs/04-home-node-minimum-validation.md)
2. [家庭节点私人云架构 vNext](../../Requirement/private-cloud-architecture-vnext.md)
3. [服务器只读检查](./docs/01-server-preflight.md)

V0 不需要 ECS，只验证 PostgreSQL、当前 REST API 和家庭局域网可达性。当前仓库尚未实现 WebRTC、本地对象存储和 ECS 协调服务；不得把 V0 探针成功解释为远程照片直连成功。

## vNext 当前进度

- [x] 家庭节点、ECS 协调和本地对象存储的职责边界已形成草案
- [x] V0 家庭 Linux 节点最小验证手册已整理
- [ ] 文档与代码已提交、推送，并向笔记本提供核定 commit SHA
- [ ] 笔记本完成 V0 本机与局域网探针
- [ ] Android Debug 主界面接入真实家庭节点探针
- [ ] 完成无需 ECS 的手工信令 WebRTC V1 POC
- [ ] 完成家庭本地 `ObjectStore`
- [ ] 完成 ECS WSS 协调服务与 STUN

## 历史 ECS + 阿里云 OSS 进度

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
3. V0 的 PostgreSQL 只绑定回环地址；API 8080 只允许指定家庭网段访问，不配置家庭路由器公网端口转发。
4. V0 不使用真实账号、真实照片或正式密码，不配置旧阿里云 OSS 参数。
5. 历史 ECS/OSS 验证仍遵守 RAM Role 临时凭据、Bucket 非公开、禁止在线角色永久删除原文件等原有边界。
6. 提交前运行 `./scripts/scan-secrets.sh`。

## 历史 OSS 检查入口

以下内容只用于旧 ECS + 阿里云 OSS 验证或迁移参考，不是家庭节点 V0 的执行步骤。

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
│   ├── 03-deployment-checklist.md
│   └── 04-home-node-minimum-validation.md
├── scripts/
│   ├── check-oss-readonly.sh
│   ├── check-server.sh
│   └── scan-secrets.sh
├── .gitignore
├── README.md
└── SECURITY.md
```
