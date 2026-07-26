# 阶段 00 后端执行计划：工程与契约基础

## 目标与范围

对应 B0。建立可部署但尚不承载业务的 Go 模块化单体，为后续所有阶段提供统一配置、数据库、错误、观测和 API 契约基础。

## 实施任务

- 初始化 Go 1.26 模块及 `cmd/api`、`internal/platform`、模块目录和测试目录；引入 chi、pgxpool、sqlc、goose。
- 建立配置加载与启动校验，区分本地、测试和部署环境；敏感配置只从环境或密钥管理注入。
- 建立 PostgreSQL 18 连接池、事务辅助、migration 执行命令和空库/升级测试框架。
- 定义 `/api/v1`、OpenAPI 3.1、`application/problem+json`、稳定错误码、request ID、UTC 时间和游标/幂等公共结构。
- 接入结构化 JSON 日志、panic 恢复、请求超时、健康检查、优雅关闭和 OpenTelemetry 基座。
- 建立 OSS/STS 端口及内存/测试假实现；本阶段不授予在线身份永久删除权限。
- CI 执行格式化、静态检查、单元测试、migration 测试、OpenAPI 校验和 breaking-change 检查。

## 契约与交付物

- 可启动的 API 镜像、数据库 migration 基线、OpenAPI 根文档和统一错误示例。
- 本地开发说明、测试数据库启动说明及 Deployment 所需配置清单。
- 供移动端验证的一次受鉴权前探针 HTTPS JSON 接口；不得伪装成正式业务接口。

## 验证与完成门槛

- 从空库和上一测试快照均可重复执行 migration，失败不会留下半完成状态。
- 健康检查能区分进程存活和数据库就绪；关闭时停止接单并等待在途请求。
- 契约测试验证 request ID、RFC 9457 Content-Type、错误结构和未知路由。
- 空服务可在隔离环境部署，日志无密钥或连接密码，OSS 假实现不会接收明文媒体。

## 不在本阶段

账号表、管理员登录、媒体表、真实 STS 授权和任何业务页面接口。
