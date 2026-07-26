# 登录页交互增强版分析

## 页面信息

- 原型：`pages/01-auth/11-login-default-interactive/index.html`
- 参考图：`pages/01-auth/11-login-default-interactive/reference.png`
- 对应需求：F-01 注册、登录与会话
- 当前判断：可作为登录页主方案继续调试，但不能直接作为移动端实现代码。

## 已验证交互

| 检查项 | 结果 |
|---|---|
| 页面在 360 × 800 视口加载 | 通过 |
| 手机号输入框 | 可输入 |
| 密码输入框 | 可输入 |
| 密码显示/隐藏 | 通过 |
| 隐私协议勾选 | 可操作 |
| 未勾选协议时阻止登录 | 已实现提示逻辑 |
| 点击登录后的反馈 | 有加载状态 |
| 登录跳转 | 仅跳转到 `#screen_32` 占位地址 |
| 立即注册链接 | 仅跳转到 `#screen_48` 占位地址 |
| 服务协议与隐私政策 | 链接仍为 `#` |

## 与需求一致的部分

- 使用手机号和密码登录。
- 提供注册入口。
- 没有忘记密码、修改密码或账号注销入口，符合 MVP 范围。
- 页面清楚表达私人家庭相册和加密保护定位。
- 交互增强版比概念版具有更明确的元素 ID 和跳转占位逻辑，更适合作为后续主方案。

## 与需求不完整或冲突的部分

### 缺少登录字段校验

原型没有检查：

- 手机号是否为空；
- 手机号格式是否正确；
- 密码是否为空；
- 请求是否正在提交，能否防止重复点击。

### 缺少可区分的登录结果

需求要求区分：

- 密码错误或登录凭据错误；
- 网络不可用；
- 服务异常；
- 待审核账号；
- 审核通过并进入主功能；
- 会话已失效。

当前页面只包含成功占位跳转，错误状态散落在其他独立 HTML 中，尚未组成同一个登录状态机。

### 隐私协议策略已确认

产品需求 v2.0 已按当前有效设计确认：用户每次登录前都必须主动勾选服务协议和隐私政策，未勾选时不得发起登录请求；服务端记录本次接受的协议版本。

该行为不再作为待确认项。协议链接需要连接到真实内置文档页，返回登录页后保留当前输入和勾选状态。

### 远程资源依赖

- Logo 来自远程图片地址。
- 字体、图标和 Tailwind 样式来自外部 CDN。
- 断网时无法保证与参考图一致。

## 移动端实现映射

### 公共状态

建议将登录页状态整理为：

```text
Idle
Validating
Submitting
CredentialError
NetworkError
ServiceError
PendingApproval
Approved
```

### 三端组件

| 原型元素 | Android | iOS | HarmonyOS |
|---|---|---|---|
| 页面容器 | Compose Screen | SwiftUI View | ArkUI Page |
| 手机号输入 | TextField | TextField | TextInput |
| 密码输入 | TextField + PasswordVisualTransformation | SecureField | TextInput password |
| 登录按钮 | Button | Button | Button |
| 协议勾选 | Checkbox | Toggle/自定义 Checkbox | Checkbox |
| 错误提示 | Text/Snackbar | Text/Alert | Text/PromptAction |

## 下一步

1. 从现有登录错误页面中选定统一的错误提示样式。
2. 将登录、错误、待审核和成功跳转合并为一个页面状态机。
3. 以 Android Jetpack Compose 建立首个参考实现。
