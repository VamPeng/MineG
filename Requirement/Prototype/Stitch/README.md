# MineG Stitch 移动端原型

本目录由 Stitch 导出的三个原始压缩包整理而成，共包含 47 个页面或页面方案。

## 目录说明

- `DESIGN.md`：Stitch 导出的统一设计规范。
- `pages/`：按功能域整理后的原型页面。
- `source-archives/`：保留的原始导出压缩包，未修改内部内容。
- 每个页面目录包含：
  - `index.html`：可直接在浏览器中打开和调试的 Stitch 原型。
  - `reference.png`：Stitch 生成的页面视觉参考图。

## 功能域

| 编号 | 功能域 | 页面数 |
|---|---|---:|
| 01 | 登录与注册 | 11 |
| 02 | 相册权限 | 5 |
| 03 | 私人空间 | 6 |
| 04 | 媒体详情 | 6 |
| 05 | 家庭相册 | 2 |
| 06 | 备份中心 | 11 |
| 07 | 回收站 | 4 |
| 08 | 个人中心 | 2 |
|  | 合计 | 47 |

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

### 02 相册权限

| 页面 | 用途 |
|---|---|
| `01-album-permission-explainer` | 相册授权前说明 |
| `02-album-permission-granted` | 已获得完整权限 |
| `03-album-permission-limited` | 仅允许访问部分照片 |
| `04-album-permission-restricted` | 系统或管理员限制访问 |
| `05-album-permission-denied` | 用户拒绝相册权限 |

### 03 私人空间

| 页面 | 用途 |
|---|---|
| `01-private-space-overview` | 私人空间默认方案 |
| `02-private-space-syncing` | 云端同步状态 |
| `03-private-space-storage-summary` | 存储空间与分类入口 |
| `04-private-space-refined` | 默认方案的细化版本 |
| `05-private-space-storytelling` | 故事化首页方案 |
| `06-private-space-collections` | 回忆合辑方案 |

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
| `01-family-album-timeline` | 家庭相册时间线 |
| `02-family-media-detail` | 家庭相册媒体详情 |

### 06 备份中心

| 页面 | 用途 |
|---|---|
| `01-auto-backup-default-on-decision-a` | 决策 A：自动备份默认开启 |
| `02-auto-backup-manual-opt-in-decision-b` | 决策 B：用户手动开启备份 |
| `03-backup-uploading` | 正在上传 |
| `04-backup-permission-required` | 缺少相册权限 |
| `05-backup-waiting-for-wifi` | 等待 Wi-Fi |
| `06-backup-network-offline` | 网络离线 |
| `07-backup-storage-full` | 存储空间不足 |
| `08-backup-session-expired` | 登录状态过期 |
| `09-backup-service-unavailable` | 服务暂时不可用 |
| `10-backup-scanning` | 正在扫描媒体库 |
| `11-backup-complete` | 备份全部完成 |

### 07 回收站

| 页面 | 用途 |
|---|---|
| `01-recycle-bin-populated` | 回收站存在内容 |
| `02-recycle-bin-empty` | 空回收站 |
| `03-restore-private-only-decision-a` | 决策 A：恢复后保持私有 |
| `04-restore-original-sharing-decision-b` | 决策 B：恢复原共享状态 |

### 08 个人中心

| 页面 | 用途 |
|---|---|
| `01-profile-overview` | 个人中心 |
| `02-logout-confirmation` | 退出登录确认 |

## 原始导出包

| 文件 | 内容范围 |
|---|---|
| `01-auth-and-permissions.zip` | 登录、注册、审核、相册权限 |
| `02-private-family-and-media.zip` | 自动备份设置、私人空间、媒体详情、家庭相册 |
| `03-backup-recovery-and-profile.zip` | 备份状态、回收站、恢复、个人中心及私人空间方案 |

## 调试约定

Stitch 页面是 HTML/CSS/JavaScript Web 原型，不是移动端生产代码。调试时主要验证视觉、状态、按钮反馈和页面流程；验证通过后再映射为 `Mobile/` 中的原生页面与组件。
