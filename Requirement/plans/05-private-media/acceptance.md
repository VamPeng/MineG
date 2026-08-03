# 阶段 05 验收记录：私人媒体闭环

> 当前状态：`PRE_ACCEPTANCE`
> 记录日期：2026-08-03
> 契约：`stage05-v1@1.0.0`（仍为 `BASELINED`，不得提前冻结）

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
- 删除成功后才从 Compose 快照移除项目，并关闭对应预览句柄；服务端逻辑删除不删除 OSS 或设备本地媒体。

## 未执行的阶段退出证据

- 真实 PostgreSQL、真实鉴权和隔离私有 OSS 的 Android 纵向链路尚未运行。
- 真机/模拟器上的照片、GIF、视频封面/预览、MediaStore 写入、权限撤销和进程回收尚未留存验收证据。
- 其他用户与管理员的真实环境隔离、授权过期续签、删除并发和 1 GiB 临时文件约束尚未跑完。

本机检查时未发现 Docker，也没有 `MINEG_TEST_DATABASE_URL`；因此不能把单元测试替代为真实 PostgreSQL/OSS 验收。待提供隔离测试数据库、可用私有 OSS 联调身份和 Android 设备后，按技术方案第 13 节补齐该矩阵，再将本记录和 `stage05-v1` 一并更新为冻结状态。
