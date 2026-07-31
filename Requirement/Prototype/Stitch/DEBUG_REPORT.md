# MineG Stitch 原型调试报告

> 本报告记录的是设计收敛前的 47 页原始调试基线，不代表当前页面清单。当前有效原型为 32 页；原 `-dep` 页面、重复方案和无有效恢复操作的存储提示页均已删除，完整历史只在 `source-archives/` 中追溯。下文出现的已删除路径仅用于说明旧基线。

## 当前结论

- 已在 `360 × 800` 移动端视口下逐页加载 47 个 HTML 原型。
- 47 个页面均可打开，没有页面导航加载失败。
- 当前联网环境下，所有远程图片均加载成功。
- 发现 1 个确定的 JavaScript 运行错误。
- 发现 1 个确定的横向布局溢出。
- 发现 4 个需要人工确认的固定高度截断风险。
- 13 个页面缺少 HTML `<title>`，不影响页面显示，但不利于调试识别。

## 全局调试限制

### 依赖网络

47 个页面全部从 Tailwind CDN 加载样式，32 个页面还引用了远程图片。因此当前 HTML 适合作为联网原型调试，不是可离线运行的生产资源。

移动端正式实现时需要：

1. 将颜色、字体、圆角和间距转换为三端设计 Token。
2. 将需要保留的图片下载为项目本地资源。
3. 用 Android、iOS 和 HarmonyOS 原生组件重建页面。
4. 不在生产应用中加载 Tailwind CDN 或 Google Fonts。

### 页面之间尚未真正连通

多数按钮只实现视觉反馈、弹窗或 `#screen_xx` 哈希占位跳转，没有连接到其他整理后的 HTML 页面，也没有真实 API。

这些 HTML 可以调试：

- 页面视觉和滚动；
- 表单输入和基础校验反馈；
- 弹窗、Toast、按钮状态；
- 页面状态方案；
- 移动端组件拆分。

这些 HTML 暂时不能调试：

- 真实注册与登录；
- 后端审核状态；
- 系统相册权限；
- 实际备份任务；
- 文件下载、分享、删除与恢复；
- 页面间的完整导航链路。

## 已确认问题

### P1：媒体保存成功页存在 JavaScript 运行错误

页面：

`pages/04-media-detail/04-media-save-success/index.html`

问题：

脚本使用 `.aspect-[4/5]` 作为原生 `querySelectorAll` 选择器。方括号和斜杠没有转义，浏览器会抛出 `SyntaxError`，导致后续触摸交互脚本停止执行。

建议：

改用稳定的 `data-*` 属性，或者对 Tailwind 类名进行 CSS 选择器转义。

### P1：审核同步页装饰层没有正确居中

页面：

`pages/01-auth/09-signup-review-syncing/index.html`

问题：

圆形装饰层声明了 `left-1/2 -translate-x-1/2`，实际渲染时负向位移没有生效，元素右边界达到 469px，超过 360px 视口。

建议：

使用明确样式 `left: 50%; transform: translateX(-50%)`，并保持页面 `overflow-x: hidden`。

## 固定高度截断风险

以下页面的内容高度超过 800px，同时页面禁止纵向滚动，需要逐页确认底部内容是否被遮挡：

| 页面 | 内容高度 |
|---|---:|
| `01-auth/03-login-network-unavailable` | 884px |
| `04-media-detail/05-media-delete-confirmation` | 832px |
| `05-family-album/02-family-media-detail` | 884px |
| `07-recycle-bin/04-restore-original-sharing-decision-b-dep` | 884px |

## 缺少页面标题

以下页面缺少 `<title>`：

- `01-auth/01-login-default-concept`
- `01-auth/02-login-credentials-error`
- `01-auth/07-signup-password-mismatch`
- `01-auth/11-login-default-interactive`
- `02-permissions-dep/03-album-permission-limited-dep`
- `02-permissions-dep/04-album-permission-restricted-dep`
- `03-private-space/02-private-space-syncing-dep`
- `04-media-detail/01-private-media-encrypted`
- `04-media-detail/04-media-save-success`
- `04-media-detail/06-media-save-failed`
- `06-backup/03-backup-uploading`
- `06-backup/11-backup-complete`
- `07-recycle-bin/01-recycle-bin-populated`

## 逐页分析状态

| 功能域 | 总数 | 已详细分析 | 待分析 |
|---|---:|---:|---:|
| 登录与注册 | 11 | 1 | 10 |
| 相册权限 | 5 | 0 | 5 |
| 私人空间 | 6 | 0 | 6 |
| 媒体详情 | 6 | 0 | 6 |
| 家庭相册 | 2 | 0 | 2 |
| 备份中心 | 11 | 0 | 11 |
| 回收站 | 4 | 0 | 4 |
| 个人中心 | 2 | 0 | 2 |
| 合计 | 47 | 1 | 46 |

详细分析从 `analysis/01-auth/11-login-default-interactive.md` 开始。
