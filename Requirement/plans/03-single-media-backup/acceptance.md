# 阶段 03 验收记录：单媒体原始内容备份

- 更新日期：2026-08-02
- 当前结论：项目负责人已确认真实 ECS + 私有 OSS 的 Android 单媒体上传完成，`stage03-v2` 转为 `FROZEN`，阶段 03 关闭。
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
- 项目负责人确认 Android 真机已完成真实 ECS + 私有 OSS 的单媒体上传验收。

## 验收决定与后续风险覆盖

- 本阶段按项目负责人决定关闭；真实上传正常路径已接受。
- 授权过期、对象缺失、摘要错配和完成响应丢失的故障演练未作为本次关闭的独立证据，转入阶段 09 发布加固记录，不得反向改变已冻结的 `stage03-v2` 契约。

## 下一阶段输入

阶段 04 在同一 Core 状态机上补齐 SQLite 任务持久化、进程恢复、Wi-Fi/蜂窝门禁、后台调度、多媒体队列、并发和真实进度事件，不另造第二套上传协议。
