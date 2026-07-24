# 私人相册基础设施目录约定

## 工作范围

- 默认只修改当前目录中完成私人相册部署所必需的文件。
- 不修改目录外的代码、云资源或其他项目，除非用户明确扩大范围。
- 不顺便重构、清理或删除与当前任务无关的内容。

## 敏感信息

- 不把 AccessKey、STS Token、密码、SSH 私钥、Cookie 或签名 URL 写入仓库。
- 不提交真实实例 ID、账号 ID、Bucket 名、RAM 角色名、公网 IP 或未公开域名。
- 真实运行参数只保存在被 Git 忽略的 `.env` 或后续确定的密钥管理服务中。
- 任何提交前都运行 `./scripts/scan-secrets.sh`，并检查暂存差异。

## 阿里云与 OSS

- ECS 内的应用使用实例 RAM 角色和临时凭证，不配置长期 AccessKey。
- OSS Bucket 保持阻止公共访问，并优先使用同地域内网 Endpoint。
- 应用角色对 `DeleteObject` 和 `DeleteObjectVersion` 的显式拒绝属于原文件保护设计，不得擅自移除。
- 需要物理删除时，先说明影响并取得用户明确确认，再使用独立运维流程。

## 服务器操作

- 当前环境不能直接连接目标服务器时，提供可复制的最小步骤，由用户执行并回传结果。
- 用户回传结果后先确认，再给下一步；不要要求用户粘贴密钥、令牌或私钥。
- 对可能修改云资源、开放端口或删除对象的操作，先说明精确目标和影响。

## 验证

- 服务器只读检查：`./scripts/check-server.sh`
- OSS 定向只读检查：`./scripts/check-oss-readonly.sh`
- 敏感信息检查：`./scripts/scan-secrets.sh`
- Shell 语法检查：`bash -n scripts/*.sh .githooks/pre-commit`
