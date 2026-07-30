# 批次 E1 手动验证清单：账号直接准入

## 前置条件

- 本地 API `GET /health/ready` 返回 `{"status":"ready"}`，数据库 migration 为 `5`。
- Android Debug APK 已安装，USB 调试执行 `adb reverse tcp:8080 tcp:8080`。
- 使用专用测试手机号和测试密码；不得使用生产账号或生产数据库。
- 管理端可登录并看到待审核列表。

## 注册与待审核

1. 在 Android 注册新测试账号。
2. 确认页面进入“申请审核中”，说明文字为“管理员审核通过后”且不出现家庭密钥/key grant。
3. 在专用数据库核对该用户为 `PENDING`、`reviewed_at IS NULL`。
4. 核对 `user_key_bundles` 中该用户记录数为 `0`。
5. 核对 `key_grant_tasks` 中该用户记录数为 `0`。

## 审核与直接准入

1. 管理端批准该账号；重复点击或使用同一幂等键不得产生第二次状态迁移。
2. Android 在待审核页点“刷新状态”。
3. 确认无需其他成员设备在线、无需密码二次输入、无需 key grant，直接进入 Profile/权限/私人空间主链。
4. 数据库核对用户为 `APPROVED` 且 `reviewed_at`、`reviewed_by` 非空。
5. 再次核对 `user_key_bundles` 与 `key_grant_tasks` 仍均为 `0`。

## 会话回归

1. 退出登录后重新登录，确认直接返回 `APP_HOME`，不回到审核页。
2. 杀掉 App 进程再打开，确认 Session 恢复后仍进入获批主链。
3. 使用错误密码确认仍返回凭据错误；未审核的另一账号仍不能访问 `/me`。
4. 观察 API 请求，现行账号主链不得调用 `/me/key-bundle` 或 `/key-grants/*`。

## 兼容与非目标

- 旧 `account-v2` 请求携带完整 key bundle 时服务端仍可接收，但这些字段不得改变审核与准入结果。
- 旧 key bundle/key-grant 表、API 和 C ABI 本批次不删除，只保留兼容。
- 本清单不验收媒体上传。无媒体加密的真实 ECS + 私有 OSS 上传属于批次 E2；当前“开启自动备份”仍不能视为实际上传已接入。
