# 阶段 03 移动端执行计划：Android 单媒体原始内容备份

## 目标与范围

对应 M3。用一条本地媒体打通 MediaStore → C++ Core → 公网 ECS → 私有 OSS → ECS 完成确认 → 私人列表。媒体上传过程不创建密钥、不生成密文副本，也不生成加密清单。

## 实施任务

- `BackupSingleMedia` 使用 `stage03-v2`；C++ Core 查询活动本地索引并负责完整上传状态机。
- Core 通过 `MediaSourceEffect` 打开和释放媒体句柄，以 4 MiB 流式计算整资源与分片 SHA-256。
- Core 创建会话、解析对象授权、逐分片产生 `TransportEffect`、消费 ETag、上报分片并提交完成。
- Android 仅从受控文件描述符或兼容临时路径执行 HTTPS PUT，不解析上传业务 DTO，也不决定下一状态。
- 本地相册点击单条媒体触发代表性上传，并显示“原始媒体（不加密）”、完成、去重或失败状态。
- 旧 Stage03 v1 加密 C ABI、SQLite 与 Kotlin Client 只保留迁移兼容，不接入 `MineGAppRuntime`。

## 测试与完成门槛

- C++ 构建/契约测试、Android 单元测试、lint 和 APK 构建通过；契约明确禁止新主链中的加密字段。
- 至少一张真实照片在目标 Android 真机经真实 ECS + 私有 OSS 完成直传并进入私人列表。
- 真实 ECS + 私有 OSS 的 Android 单媒体上传已由项目负责人于 2026-08-02 确认并冻结 `stage03-v2`；断网、授权过期、分片失败、重复点击、摘要不符和完成响应丢失的故障演练转入阶段 09 发布加固。
