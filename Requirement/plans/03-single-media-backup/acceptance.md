# 阶段 03 验收记录：单媒体原始内容备份

- 更新日期：2026-07-31
- 当前结论：`stage03-v2` 代码纵向闭环已实现，本地自动化与真实 PostgreSQL 通过；真实 ECS + 私有 OSS 和 Android 真机验收尚未执行，因此状态为 `IMPLEMENTED_PENDING_REAL_OSS`。
- 旧 `stage03-v1` 加密实现仅作为兼容证据，不代表当前产品行为。

## 当前交付

| 交付物 | 版本/状态 |
| --- | --- |
| PostgreSQL migration | `00006_original_media_upload.sql`，schema version 6 |
| OpenAPI | `0.5.0`，`stage03-v2 / MEDIA_ORIGINAL` |
| Core 命令 | `BackupSingleMedia`，Core 编排全部业务步骤 |
| Android | MediaSource/Transport Effect 执行器与本地相册单条上传入口 |
| 旧兼容 | `MEDIA_CIPHERTEXT`、加密表字段、Stage03 v1 C ABI 保留但不进入生产主链 |

## 已验证

- Go 单元/API 契约测试通过。
- 本机 PostgreSQL 真实执行 migration 1～6，并跑通账号审核、旧密文兼容和 `MEDIA_ORIGINAL` 创建、分片、完成、本人列表集成测试。
- 新媒体对象使用 `.original`，资源只写 `content_size/content_sha256`，且 `media_key_envelopes` 计数为 0。
- C++ Core 和 Android 均可编译；Android 单元测试通过。Core 读取 MediaStore 文件描述符计算摘要，Android 仅执行 Effect。
- 上传完成后重新查询私人媒体；去重命中不重复上传对象。

## 手动验收剩余项

1. 在目标 ECS 配置私有 OSS 与 RAM Role，确认 App 获得的仅为单对象、单 part、短期 PUT 授权。
2. Android 真机选择一张照片，确认页面显示“不加密”上传状态，OSS 出现 `.original` 对象，App 私人空间出现新媒体。
3. 核对数据库：上传用途为 `MEDIA_ORIGINAL`，加密列为 `NULL`，`media_key_envelopes` 无新增记录。
4. 验证对象键越权、授权过期、对象缺失、长度/SHA-256 不符均失败关闭。
5. 验证日志、审计和错误响应不包含 Bearer、OSS 签名 URL 或媒体正文。

## 下一阶段输入

阶段 04 在同一 Core 状态机上补齐 SQLite 任务持久化、进程恢复、Wi-Fi/蜂窝门禁、后台调度、多媒体队列、并发和真实进度事件，不另造第二套上传协议。
