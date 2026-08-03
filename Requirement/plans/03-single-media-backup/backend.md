# 阶段 03 后端执行计划：单媒体原始内容上传

## 目标与范围

对应 B3。支持一条未做客户端应用层加密的媒体，经公网 ECS 获取短期授权并分片直传私有阿里云 OSS；ECS 核对对象长度、SHA-256、分片与 ETag 后，事务性创建本人可查询媒体。

## 实施任务

- `stage03-v2` 创建请求只接收原始内容摘要、MIME、资源长度和分片计划，不接收 Media Key、加密清单或密文字段。
- 上传用途为 `MEDIA_ORIGINAL`，对象键由服务端生成在 `media/<owner>/<session>/` 下，并以 `.original` 区分新对象。
- OSS multipart 授权限定对象键、PUT、Content-Length 和短有效期；客户端不持有长期云凭据。
- 完成前使用 `ListParts`、ETag、总长度和 `HeadObject` 的 `mineg-content-sha256` 元数据核对。
- 新媒体不写 `media_key_envelopes`；旧 `MEDIA_CIPHERTEXT` 表字段与接口仅保留迁移兼容。
- 同账号按 `(owner_id, content_sha256, content_revision)` 幂等收敛，不做跨账号去重查询。

## 完成门槛

- PostgreSQL migration 6、OpenAPI 0.5.0、内存对象存储与真实 PostgreSQL 集成测试通过。
- 原始媒体完成前不可见，确认后进入 `/api/v1/media`；重复创建、分片上报和完成可幂等收敛。
- 项目负责人已于 2026-08-02 确认真实 ECS + 私有 OSS 上传并冻结 `stage03-v2`；授权、越权、过期和摘要错配的故障演练转入阶段 09 发布加固。

## 不在本阶段

批量队列、后台恢复调度、派生预览矩阵、私人详情读取授权、分享与回收站。
