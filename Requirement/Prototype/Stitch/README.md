# MineG Stitch 移动端原型

本目录由 Stitch 导出的三个原始压缩包整理而成。当前原型保留 32 个有效页面或页面方案；原始压缩包中的 47 个导出页面保持不变。

## 目录说明

- `DESIGN.md`：Stitch 导出的统一设计规范。
- `THEME.md`：以 MineG Logo 渐变为来源的 App 主题色、状态色和使用规则；作为颜色 Token 的唯一基线。
- `UI-CONTENT-RULES.md`：双人自用 MVP 的页面内容保留规则，以及全部 HTML 原型的清理范围。
- `pages/`：按功能域整理后的原型页面。
- `source-archives/`：保留的原始导出压缩包，未修改内部内容。
- 原 `-dep` 历史 HTML 已删除；历史设计只在 `source-archives/` 原始压缩包中追溯。
- 每个页面目录使用 `index.html` 作为可直接在浏览器中打开、调试和审查的唯一页面内容基线。
- 原有 47 张页面参考图已于 2026-07-31 删除；它们与持续更新后的 HTML 不再一致，不参与后续设计或需求判断。

## UI 内容基线

- App 只服务本人和对象两名固定用户，不做推广、增长或面向陌生用户的信任建设。
- 页面只展示完成 MVP 交互所需的操作、输入、真实状态、阻塞原因和直接后果。
- 私人云、加密、存储、安全、隐私等没有交互价值的宣传或背书内容全部移除；会影响当前操作的权限用途、空间不足、流量费用、失败原因和操作后果仍须保留。
- 全量清理覆盖 `pages/` 下 32 个有效 HTML 原型；原始导出压缩包保持不变。具体判断和清理清单以 [UI-CONTENT-RULES.md](./UI-CONTENT-RULES.md) 为准。

## 功能域

| 编号 | 功能域 | 页面数 |
|---|---|---:|
| 01 | 登录与注册 | 10 |
| 02 | 相册权限（已废弃） | 0 |
| 03 | 私人空间 | 1 |
| 04 | 媒体详情 | 6 |
| 05 | 家庭相册 | 2 |
| 06 | 备份中心 | 8 |
| 07 | 回收站 | 3 |
| 08 | 个人中心 | 2 |
|  | 合计 | 32 |

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
| `09-signup-review-syncing` | 注册申请待审核及刷新状态 |
| `11-login-default-interactive` | 默认登录页交互增强版 |

### 02 相册权限（已废弃）

原 `02-permissions` 功能域的历史 HTML 已删除。未获得完整相册权限时统一使用 `06-backup/04-backup-permission-required`。

权限流程统一为：

- 未决定、部分授权、访问受限、拒绝或系统限制时，均展示 `06-backup/04-backup-permission-required`。
- 获得完整授权后直接进入 `03-private-space/01-private-space-overview`，不展示“授权完成”中间页。

### 03 私人空间

| 页面 | 用途 |
|---|---|
| `01-private-space-overview` | 私人媒体默认网格方案 |

### 04 媒体详情

| 页面 | 用途 |
|---|---|
| `01-private-media-encrypted` | 私人媒体详情视觉基线；目录名保留历史命名，页面不展示加密说明 |
| `02-private-media-downloading` | 原文件下载中 |
| `03-media-shared-success` | 共享家庭相册成功 |
| `04-media-save-success` | 保存到系统相册成功 |
| `05-media-delete-confirmation` | 移入回收站确认 |
| `06-media-save-failed` | 保存媒体失败 |

### 05 家庭相册

| 页面 | 用途 |
|---|---|
| `01-family-album-timeline` | 首页“共享”Tab 的家庭相册时间线；不再显示内部过滤 Tab |
| `01-family-album-timeline?view=mine` | 从个人中心进入的“我分享的”同页独立列表状态 |
| `02-family-media-detail` | 家庭相册媒体详情 |

首页底部仅保留私人、备份、我的三个 Icon；原家庭 Icon 移除。原家庭页顶部筛选改为首页“私人 / 共享”双 Tab，“我分享的”改由个人中心进入独立列表。

### 06 备份中心

备份主页面统一展示设备本地媒体。页面自上而下由“本地相册”标题栏、同步状态和本地相册列表组成；标题栏右侧设置按钮进入备份设置。各相册文件夹按名称分区，分区内媒体使用三列网格排列。

| 页面 | 用途 |
|---|---|
| `01-auto-backup-default-on-decision-a` | 备份设置视觉基线；目录名保留历史命名，当前自动备份默认关闭 |
| `03-backup-uploading` | 本地相册主页面；展示当前同步图片和上传进度 |
| `04-backup-permission-required` | 权限未获取；沿用原统一相册授权说明页 |
| `05-backup-waiting-for-wifi` | 本地相册；同步等待 Wi-Fi |
| `06-backup-network-offline` | 本地相册；网络离线 |
| `09-backup-service-unavailable` | 本地相册；服务暂时不可用 |
| `10-backup-scanning` | 本地相册；正在扫描媒体库 |
| `11-backup-complete` | 本地相册；展示同步完成文案 |

当自动备份关闭时，本地相册列表底部中间展示“开始备份”悬浮按钮。点击后重新开启备份并恢复同步状态。

登录状态异常不在备份中心展示中间状态页。App 自动清理失效会话、停止当前账号的未完成任务并跳转登录页。

### 07 回收站

回收站不属于底部主导航。用户从个人中心的“回收站”操作入口进入，回收站页面顶部使用返回按钮回到个人中心。

| 页面 | 用途 |
|---|---|
| `01-recycle-bin-populated` | 回收站存在内容 |
| `02-recycle-bin-empty` | 空回收站 |
| `03-restore-private-only-decision-a` | 已采用决策 A：恢复后保持私有 |

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
