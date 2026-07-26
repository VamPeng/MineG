# 阶段 01 后端执行计划：账号、会话与审核

## 目标与范围

对应 B1，为移动端与管理端提供真实注册、审核、登录、刷新、退出闭环，并持久化用户公钥和加密 key bundle。

## 实施任务

- 新增 `users`、`user_agreements`、`user_sessions`、`admin_users`、`admin_sessions`、`devices`、`user_key_bundles` 与审计 migration、约束和 sqlc 查询。
- 实现中国大陆手机号规范化与唯一约束、8～64 位密码规则及 Argon2id；在目标 ECS 基准后锁定参数版本。
- 实现注册、登录、刷新、退出和审核状态接口；Access Token 15 分钟、Refresh Token 30 天并轮换，数据库只存哈希。
- 实现部署期管理员 bootstrap 命令、管理员 Session Cookie、CSRF/Origin 校验、待审核游标列表和幂等通过操作。
- 审核通过只创建后续 key grant 协调状态；在家庭 envelope 未就绪前，对外仍返回待审核。
- 对登录、审核、会话重放和重复请求写结构化审计，响应保持 RFC 9457 格式。

## 接口与数据交接

- 冻结 F-01/F-02 涉及的 OpenAPI DTO、错误码、轮询状态及管理端 CSRF 流程。
- 向阶段 02 交付可查询的 key grant 待办记录和首成员 bootstrap 状态。
- 管理员接口永远不返回私钥、加密包内容或可用于解密媒体的信息。

## 验证与完成门槛

- 覆盖重复手机号、错误密码、未审核登录、Token 轮换/重放、退出撤销、并发审核和 CSRF 失败。
- 管理端 Session 登录后轮换，30 分钟闲置/8 小时绝对过期均生效。
- Android 与管理端可在真实数据库上完成注册—审核动作—待 envelope—登录/退出的预期状态链。

## 不在本阶段

家庭密钥明文、个人资料修改、头像、媒体与上传接口。
