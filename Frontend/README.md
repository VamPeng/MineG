# Frontend

Web 前端和内部管理页面相关代码与文档。

MVP 管理端范围固定为：

- 管理员登录和会话恢复。
- 查看待审核的注册账号。
- 查看申请详情并二次确认通过注册审核。

管理端使用 Vue 3、TypeScript、Vite、Vue Router 和 Element Plus 构建 SPA。MVP 不提供成员管理、媒体浏览、反馈工单、数据统计或永久清理页面；永久清理由独立受限 CLI 完成。

## 阶段 00 基座

阶段 00 已建立登录壳、受保护路由、OpenAPI 类型生成、Cookie/CSRF/401 请求基座以及加载、空、错误重试、确认和通知组件。当前没有模拟登录或业务页面。开发和浏览器验收命令见 [`docs/development.md`](./docs/development.md)。
