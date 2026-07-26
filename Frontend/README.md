# Frontend

Web 前端和内部管理页面相关代码与文档。

MVP 管理端范围固定为：

- 管理员登录和会话恢复。
- 查看待审核的注册账号。
- 查看申请详情并二次确认通过注册审核。

管理端使用 Vue 3、TypeScript、Vite、Vue Router 和 Element Plus 构建 SPA。MVP 不提供成员管理、媒体浏览、反馈工单、数据统计或永久清理页面；永久清理由独立受限 CLI 完成。

## 阶段 01 MVP

阶段 01 已完成管理端 MVP：真实管理员登录/恢复/退出、401 过期处理、CSRF 轮换、待审核游标列表、脱敏详情和二次确认通过。通过操作使用幂等键并正确呈现并发“已处理”结果。后续阶段只做兼容、安全与发布加固，不扩展管理端业务范围。

开发和浏览器验收约束见 [`docs/development.md`](./docs/development.md)。

## 阶段 02 范围冻结

OpenAPI 已同步家庭 key grant、本人资料和头像接口，但管理端 API client、路由和生产入口仍只包含登录、会话与审核能力。负向回归会拒绝把 profile、avatar、key/grant、media 或 member 能力加入管理端；批准结果继续只表示审核动作成功，不显示或操作家庭 envelope。

## 阶段 03 上传隔离回归

OpenAPI 0.3.0 已生成上传、分片完成和本人媒体类型，但管理端 API client 不导入这些类型，路由与菜单没有新增业务入口。测试固定管理员方法集合并验证管理员 Cookie 调用 `/api/v1/uploads` 或 `/api/v1/media` 返回 401；生产 bundle 扫描不得出现这些端点、OSS 主机、AccessKey 或 SecurityToken。
