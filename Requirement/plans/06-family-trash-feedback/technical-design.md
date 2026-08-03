# 阶段 06 技术方案：固定家庭、回收站恢复与反馈

> 方案状态：`BASELINED`
> 启动日期：2026-08-03
> 移动契约：`stage06-v1@0.1.0`

## 1. 阶段输入

- 阶段 04 已关闭，原保留异常矩阵不属于本次项目计划。
- 阶段 05 MVP 已完成并进入优化；`stage05-v1` 公共行为已冻结。
- 媒体对象不执行客户端应用层加密。家庭共享只改变服务端可见性并签发短期私有 OSS 读取授权，不创建 Media Key、envelope 或 key grant。

## 2. 固定家庭边界

MineG 只服务本人和对象两名固定用户。服务端仍显式保存 `family_memberships`，并以唯一的 `member_slot=1|2` 约束家庭上限，避免“已审核”被错误等同于“可读取家庭媒体”。本阶段不提供创建、加入、切换或管理多个家庭的 API。

管理员审核通过用户时在同一事务和 advisory lock 内分配空闲成员槽；两个槽位都占用时审批事务失败。迁移会为不超过两名的已有 `APPROVED` 用户补齐成员关系，若历史数据超过两名则拒绝迁移并要求人工核对。家庭列表和授权必须同时满足：调用者是家庭成员、所有者属于同一家庭、分享为 `ACTIVE`、媒体上传完成且未进入回收站。

## 3. API 与事务

- `POST /api/v1/private/media/{media_id}/share`：所有者幂等设置 `shared=true|false`；状态变化、访问版本和审计同事务提交。
- `GET /api/v1/family/media?filter=all|mine`：先应用家庭和过滤条件，再按拍摄时间游标分页。
- `GET /api/v1/family/media/{media_id}` 与 `/access`：只读详情及 `VIEW`/`STREAM` 短期授权，拒绝 `DOWNLOAD`。
- `GET /api/v1/trash`：本人活动回收站记录按首次 `trashed_at` 倒序分页。
- `POST /api/v1/trash/{media_id}/restore`：恢复幂等；恢复后分享保持 `INACTIVE`，并刷新访问版本和审计。
- `GET /api/v1/help/faq`：版本化兼容内容；Android 同时保留离线 FAQ。
- `POST /api/v1/feedback`：固定分类、1～1000 字、可选联系方式和允许的环境字段；拒绝附件、对象地址和凭据。

所有写操作使用 `Idempotency-Key` 和请求摘要；相同键不同输入失败关闭。列表游标使用 HMAC，并绑定当前账号、过滤器、作用域和末项。

## 4. 数据与隐私

`00011_family_trash_feedback.sql` 新增固定家庭成员、分享请求、恢复请求、反馈与反馈幂等记录。现有 `shares`、`trash_records`、`audit_events` 继续作为业务状态与审计来源。

家庭响应不返回对象键、完整手机号或长期凭据。反馈不接收媒体、日志包、Token、签名地址或云凭据。管理员 Web 会话不能调用家庭、回收站、反馈或清理接口。

## 5. Core 与 Android

C++ Core 唯一拥有家庭分页、分享状态、回收站分页、恢复结果和反馈提交结果。Android 只发起 `stage06-v1` 命令、执行 Transport/MediaPlayback Effect 并渲染快照；现有 ViewModel 本地切换分享、本地恢复和模拟反馈成功必须删除。

家庭播放器可以复用阶段 05 的受控临时文件原语，但使用独立家庭授权命令且不暴露 SystemAlbum Effect。退出、账号切换、取消分享和删除后清理家庭快照与临时句柄。

## 6. 本轮实现切片

1. 建立 migration、OpenAPI、服务端分享/家庭查询与家庭只读授权。
2. 完成回收站列表/恢复及反馈幂等写入。
3. 登记并接入 Core `stage06-v1`，替换 Android 模拟行为。
4. 增加管理端范围负回归和跨模块自动化测试。

受限永久清理 CLI 保持阶段 06 后续切片；在线 API 永远不提供 purge endpoint，当前服务身份继续没有 OSS 永久删除权限。
