# 安全说明

## 不应进入仓库的内容

- AccessKey ID、AccessKey Secret、STS Token
- SSH 私钥、TLS 私钥、数据库密码、应用密钥
- `.env`、阿里云 CLI 配置、ossutil 凭证文件
- 真实实例 ID、账号 ID、Bucket 名、RAM 角色名
- 公网 IP、内部拓扑、未公开域名
- 带有签名参数的临时下载或上传 URL
- 完整请求日志、云平台 Request ID 和错误追踪包

真实运行参数只保存在部署主机的 `.env` 或后续选定的密钥管理服务中。应用应通过 ECS 实例 RAM 角色自动获取并轮换临时凭证。

## 提交前检查

```bash
./scripts/scan-secrets.sh
git diff --cached
```

仓库已经包含可选的提交前钩子。从 MineG 仓库根目录启用：

```bash
git config core.hooksPath Deployment/private-album-infra/.githooks
```

## 如果敏感信息已经提交

1. 立即停止继续推送和分享仓库。
2. 在对应平台撤销或轮换泄露的凭证。
3. 清理 Git 历史，而不只是删除最新版本中的文件。
4. 检查云审计日志，确认是否存在异常调用。
5. 确认旧凭证失效后再恢复部署。

仅把仓库改为私有不能使已经泄露的凭证重新安全。
