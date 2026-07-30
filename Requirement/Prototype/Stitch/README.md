# MineG Stitch 移动端原型

本目录由 Stitch 导出的三个原始压缩包整理而成。当前原型保留 35 个有效页面或页面方案；原始压缩包中的 47 个导出页面保持不变。

## 目录说明

- `DESIGN.md`：Stitch 导出的统一设计规范。
- `THEME.md`：以 MineG Logo 渐变为来源的 App 主题色、状态色和使用规则；作为颜色 Token 的唯一基线。
- `pages/`：按功能域整理后的原型页面。
- `source-archives/`：保留的原始导出压缩包，未修改内部内容。
- 目录名以 `-dep` 结尾的页面或功能域仅用于历史设计追溯，不计入有效页面数，也不会被需求流程引用。
- 每个页面目录包含：
  - `index.html`：可直接在浏览器中打开和调试的 Stitch 原型。
  - `reference.png`：Stitch 生成的页面视觉参考图。

## 功能域

| 编号 | 功能域 | 页面数 |
|---|---|---:|
| 01 | 登录与注册 | 11 |
| 02 | 相册权限（已废弃） | 0 |
| 03 | 私人空间 | 2 |
| 04 | 媒体详情 | 6 |
| 05 | 家庭相册 | 2 |
| 06 | 备份中心 | 9 |
| 07 | 回收站 | 3 |
| 08 | 个人中心 | 2 |
|  | 合计 | 35 |

## 页面索引

### 01 登录与注册

| 页面 | 用途 |
|---|---|
| `01-login-default-concept` | 默认登录页概念版 |
| `02-login-credentials-error` | 账号或密码错误 |
| `03-login-network-unavailable` | 登录时网络不可用 |
| `04-signup-admin-review-notice` | 注册前提示管理员审核 |
| `05-signup-invalid-phone` | 手机号格式错误 |
| `06-signup-duplicate-phone` | 手机号已注册 |
| `07-signup-password-mismatch` | 两次密码不一致 |
| `08-signup-submit-failed` | 注册提交失败 |
| `09-signup-review-syncing` | 注册申请审核同步中 |
| `10-signup-review-pending` | 注册申请待审核 |
| `11-login-default-interactive` | 默认登录页交互增强版 |

### 02 相册权限（已废弃）

原 `02-permissions` 功能域已整体标记为 `02-permissions-dep`，仅用于历史设计追溯。未获得完整相册权限时使用的统一授权说明页，已完整复制到 `06-backup/04-backup-permission-required`，并作为备份中心的“权限未获取”状态继续参与需求流程。

权限流程统一为：

- 未决定、部分授权、访问受限、拒绝或系统限制时，均展示 `06-backup/04-backup-permission-required`。
- 获得完整授权后直接进入 `03-private-space/01-private-space-overview`，不展示“授权完成”中间页。

废弃参考页：

| 页面 | 原用途 |
|---|---|
| `01-album-permission-explainer` | 未获得完整相册权限时的统一授权说明 |
| `03-album-permission-limited-dep` | 仅允许访问部分照片 |
| `04-album-permission-restricted-dep` | 系统或管理员限制访问 |
| `05-album-permission-denied-dep` | 用户拒绝相册权限 |

以上页面均位于 `02-permissions-dep`，不属于当前需求逻辑，仅保留设计参考。

### 03 私人空间

| 页面 | 用途 |
|---|---|
| `01-private-space-overview` | 私人媒体默认网格方案 |
| `03-private-space-storage-summary` | 私人媒体宽松网格方案 |

废弃参考页：

| 页面 | 原用途 |
|---|---|
| `02-private-space-syncing-dep` | 云端同步状态，后续归入备份页面 |
| `04-private-space-refined-dep` | 默认方案的细化版本 |
| `05-private-space-storytelling-dep` | 故事化首页方案 |
| `06-private-space-collections-dep` | 回忆合辑方案 |

以上 `-dep` 页面不属于当前需求逻辑，仅保留设计参考。

### 04 媒体详情

| 页面 | 用途 |
|---|---|
| `01-private-media-encrypted` | 私人媒体及加密说明 |
| `02-private-media-downloading` | 原文件下载中 |
| `03-media-shared-success` | 共享家庭相册成功 |
| `04-media-save-success` | 保存到系统相册成功 |
| `05-media-delete-confirmation` | 移入回收站确认 |
| `06-media-save-failed` | 保存媒体失败 |

### 05 家庭相册

| 页面 | 用途 |
|---|---|
| `01-family-album-timeline` | 首页“共享”Tab 的家庭相册时间线；不再显示内部过滤 Tab |
| `01-family-album-timeline?view=mine` | 从个人中心进入的“我分享的”独立列表；参考图为 `my-shared-reference.png` |
| `02-family-media-detail` | 家庭相册媒体详情 |

首页底部仅保留私人、备份、我的三个 Icon；原家庭 Icon 移除。原家庭页顶部筛选改为首页“私人 / 共享”双 Tab，“我分享的”改由个人中心进入独立列表。

### 06 备份中心

备份主页面统一展示设备本地媒体。页面自上而下由“本地相册”标题栏、同步状态和本地相册列表组成；标题栏右侧设置按钮进入备份设置。各相册文件夹按名称分区，分区内媒体使用三列网格排列。

| 页面 | 用途 |
|---|---|
| `01-auto-backup-default-on-decision-a` | 备份设置；自动备份默认开启，并可设置是否允许移动网络备份 |
| `03-backup-uploading` | 本地相册主页面；展示当前同步图片和上传进度 |
| `04-backup-permission-required` | 权限未获取；沿用原统一相册授权说明页 |
| `05-backup-waiting-for-wifi` | 本地相册；同步等待 Wi-Fi |
| `06-backup-network-offline` | 本地相册；网络离线 |
| `07-backup-storage-full` | 本地相册；存储空间不足 |
| `09-backup-service-unavailable` | 本地相册；服务暂时不可用 |
| `10-backup-scanning` | 本地相册；正在扫描媒体库 |
| `11-backup-complete` | 本地相册；展示同步完成文案 |

当自动备份关闭时，本地相册列表底部中间展示“开始备份”悬浮按钮。点击后重新开启备份并恢复同步状态。

登录状态异常不在备份中心展示中间状态页。App 自动清理失效会话、停止当前账号的未完成任务并跳转登录页。

废弃参考页：

| 页面 | 原用途 |
|---|---|
| `02-auto-backup-manual-opt-in-decision-b-dep` | 决策 B：用户手动开启自动备份 |
| `08-backup-session-expired-dep` | 原登录状态过期提示页；现已改为自动退出至登录页 |

以上页面仅用于历史设计追溯，不属于当前需求流程。

### 07 回收站

回收站不属于底部主导航。用户从个人中心的“回收站”操作入口进入，回收站页面顶部使用返回按钮回到个人中心。

| 页面 | 用途 |
|---|---|
| `01-recycle-bin-populated` | 回收站存在内容 |
| `02-recycle-bin-empty` | 空回收站 |
| `03-restore-private-only-decision-a` | 已采用决策 A：恢复后保持私有 |

废弃参考页：

| 页面 | 原用途 |
|---|---|
| `04-restore-original-sharing-decision-b-dep` | 决策 B：恢复原共享状态 |

决策 B 页面仅用于历史设计追溯，不属于当前需求流程。

### 08 个人中心

| 页面 | 用途 |
|---|---|
| `01-profile-overview` | 个人中心；包含回收站操作入口 |
| `02-logout-confirmation` | 退出登录确认 |

## 原始导出包

| 文件 | 内容范围 |
|---|---|
| `01-auth-and-permissions.zip` | 登录、注册、审核、相册权限 |
| `02-private-family-and-media.zip` | 自动备份设置、私人空间、媒体详情、家庭相册 |
| `03-backup-recovery-and-profile.zip` | 备份状态、回收站、恢复、个人中心及私人空间方案 |

## 调试约定

Stitch 页面是 HTML/CSS/JavaScript Web 原型，不是移动端生产代码。调试时主要验证视觉、状态、按钮反馈和页面流程；验证通过后再映射为 `Mobile/` 中的原生页面与组件。
