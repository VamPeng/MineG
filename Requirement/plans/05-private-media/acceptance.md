# 阶段 05 验收记录：私人媒体闭环

> 当前状态：`COMPLETED_OPTIMIZING`
> 记录日期：2026-08-03
> 契约：`stage05-v1@1.1.0`（`FROZEN`）

## 已完成的自动化证据

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| Core 私人分页、查看授权下载校验、保存与回执、逻辑删除 | `cmake --build build && ctest --test-dir build --output-on-failure`（`Mobile/core`） | 通过，1/1 |
| Android Compose/Core 接入、分页、详情刷新、受控预览、保存和删除 | `./gradlew testDebugUnitTest`（`Mobile/MineG_Android`） | 通过 |
| Android 数据主权静态检查 | `./gradlew testDebugUnitTest` 内的 `checkAndroidDataSovereignty` | 通过（5 个已登记的过渡命中） |
| OpenAPI | `make openapi-check`（`Service`） | 通过 |
| 服务端私有媒体、授权、回收站与对象存储单元测试 | `go test ./...`（`Service`） | 通过 |

## 已验证行为

- 本人私人列表使用 Core 的稳定分页快照，加载更多不会合并重复项目；详情会从 Core 刷新。
- 已登记缩略图只经 `VIEW` 短期授权、受控下载和长度/SHA-256 校验后才能交给界面；仅有原图的受支持照片/GIF 使用签名 OSS 动态缩放、JPEG/PNG/GIF/WebP/BMP 响应和 5 MiB 上限校验。签名 URL 不进入最终结果或 SQLite 快照。
- 原始资源保存使用 `DOWNLOAD` 授权、受控临时文件、完整性校验和 MediaStore 写入；保存完成后才写下载回执。
- 本人图片详情依次使用实际可读的 MediaStore 映射、以 `mediaId` 命名且重新完成长度/SHA-256 校验的 App 私有原图、或经 `VIEW + DETAIL` 下载校验后原子保存的云端原图；损坏私有文件失败关闭并重新获取。
- 删除成功后才从 Compose 快照移除项目，并关闭对应预览句柄；服务端逻辑删除不删除 OSS 或设备本地媒体。

## 项目负责人验收决定

- 2026-08-03 确认 Android MVP 已完成，阶段 05 转入优化并允许阶段 06 开工。
- 现有私人列表、详情、预览、保存和逻辑删除公共语义冻结；后续优化不得改变所有权、短期授权、完整性校验和删除边界。
- 缩略图缓存、列表分页、播放器兼容和性能调整属于阶段 05 优化，不作为阶段 06 门禁。

此前未留存的真机媒体矩阵、权限撤销、授权续签、进程回收和大文件约束不再作为阶段退出条件；若后续执行，结果归入优化或阶段 09 发布加固记录，不回退本阶段完成状态。
