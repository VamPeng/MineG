# OSS 身份、ECS RAM Role 与 App 上传授权

本文是 MineG 当前 OSS 身份与上传授权的权威说明。业务服务部署目标固定为公网 ECS，媒体保存在私有阿里云 OSS；不存在家庭电脑或家庭节点部署模式。

## 1. 三种不同的“凭据”

不要把 Service 的阿里云身份和 App 的上传授权混为一谈。

| 层级 | 持有者 | 内容 | 用途 |
| --- | --- | --- | --- |
| RAM 权限策略 | 阿里云 RAM | 允许访问的 Bucket、前缀和操作 | 定义一个身份最多能做什么 |
| 临时云凭据 | MineG Service | `AccessKeyId`、`AccessKeySecret`、`SecurityToken`、`Expiration` | Service 调用 OSS、完成 multipart、核对对象并生成签名 URL |
| 对象签名授权 | App | URL、HTTP 方法、必要 Header、有效期 | 只上传指定对象或指定 part |

四个临时云凭据字段的含义：

- `AccessKeyId`：临时身份标识，类似账号名。
- `AccessKeySecret`：计算请求签名的临时秘密，只能留在 Service/SDK 内存中，绝不能发给 App。
- `SecurityToken`：把这组 AccessKey 绑定到一次 STS/RAM Role 临时会话及其权限范围。
- `Expiration`：整组临时云凭据的失效时间；到期后必须刷新。

临时云凭据有效期与 App 签名 URL 有效期是两层时间。比如 Service 凭据还有 1 小时有效，Service 可以只给 App 签发 10 分钟有效的 PUT URL。App URL 到期后可以重新申请；Service 凭据到期后则必须先刷新身份。

## 2. 统一上传流程

本地调试和 ECS 正式环境只在“Service 如何取得临时云凭据”这一步不同，App 流程完全一致：

```text
App 使用 Bearer 向 MineG Service 创建上传会话
  → Service 校验用户、媒体计划和幂等键
  → Service 使用自己的临时云凭据创建 OSS multipart
  → Service 为每个对象/part 生成短期签名 PUT URL
  → App 只使用 URL、方法和 Header 直传私有 OSS
  → App 向 Service 上报 ETag、长度和 SHA-256
  → Service 使用自身凭据执行 ListParts、CompleteMultipartUpload 和 HeadObject
  → 核对成功后媒体才进入本人私人列表
```

App 不得收到 `AccessKeySecret`、原始 `SecurityToken`、RAM Role 通用权限或长期云凭据。签名 URL 本身也是敏感的短期能力，不写日志、不进入分析事件，也不允许跨对象复用。

## 3. ECS 正式环境：RAM Role + IMDSv2

### 获取方式

1. 在 RAM 创建 `mineg-api-role` 一类的实例角色。
2. 给角色配置只覆盖目标私有 Bucket、`avatars/` 与 `media/` 前缀、以及必要对象/multipart 操作的权限策略。
3. 把角色绑定到运行 MineG Service 的 ECS 实例。
4. Service 的阿里云 Credentials SDK 使用 `ecs_ram_role` 类型访问 ECS IMDSv2。
5. IMDSv2 返回 `AccessKeyId`、`AccessKeySecret`、`SecurityToken` 和 `Expiration`；SDK 在过期前自动刷新。

`ecs_ram_role` 表示凭据来源固定为 ECS 实例角色，并不是把某个 AccessKey 写死在代码中。这个来源只能在 ECS 环境正常工作。

### Service 配置

```dotenv
MINEG_ENV=deployment
MINEG_OSS_REGION=cn-hangzhou
MINEG_OSS_BUCKET=<production-bucket>
MINEG_OSS_INTERNAL_ORIGIN=https://oss-cn-hangzhou-internal.aliyuncs.com
MINEG_OSS_ECS_RAM_ROLE=<ecs-ram-role-name>
```

正式环境不配置 AccessKey ID、Secret 或 SecurityToken。Service 使用公网 OSS 地址生成手机可访问的签名 URL，使用同地域内网 Endpoint 执行 `ListParts`、完成 multipart 和 `HeadObject` 核对。

### 实例信任边界

RAM Role 是整台 ECS 的机器身份，不是某一个进程的专属身份。同一 ECS 上凡是能访问实例元数据服务的进程或容器，原则上都可能取得相同角色的临时凭据。因此：

- MineG ECS 不混跑低可信或无关服务。
- 不同权限等级的服务使用不同 ECS/计算身份和不同 RAM Role。
- 固定使用 IMDSv2 并关闭 IMDSv1。
- RAM 策略遵循最小权限，不授予 Bucket 管理、跨 Bucket 或永久删除能力。

## 4. 本地开发环境：临时 STS 凭据 + 公网 Endpoint

### 目标方式

开发电脑没有 ECS 实例角色。本地联调应由开发者通过受控身份执行 AssumeRole，取得一组有过期时间、仅可访问开发 Bucket 的 STS 临时凭据，再注入本地 MineG Service：

```dotenv
MINEG_ENV=local
MINEG_OSS_REGION=cn-hangzhou
MINEG_OSS_BUCKET=<development-bucket>
MINEG_OSS_PUBLIC_ORIGIN=https://oss-cn-hangzhou.aliyuncs.com
MINEG_OSS_ACCESS_KEY_ID=仅示意，不填写真实值
MINEG_OSS_ACCESS_KEY_SECRET=仅示意，不填写真实值
MINEG_OSS_SECURITY_TOKEN=仅示意，不填写真实值
```

真实值只能写入被 Git 忽略的本地 Secret/环境变量，不能写入本文、`.env.example`、命令历史、日志或截图。优先使用短期 STS；不得把生产 RAM 用户的长期 AccessKey 当成本地默认方案。

本地 Service 使用公网 Endpoint 完成签名、multipart 和对象核对。Android 通过 `adb reverse` 访问本地 Service，拿到的 App 上传授权格式与 ECS 环境完全一致。

### 当前项目状态（2026-07-31）

- ECS `ecs_ram_role + IMDSv2` 凭据提供方已经实现。
- 本地 STS 凭据提供方和 `MINEG_OSS_PUBLIC_ORIGIN` 尚未实现。
- 当前 `Service/local-backend.sh` 不注入 OSS 配置，运行时使用 `DisabledMediaObjects`。
- 因此当前本地 App 创建媒体上传会话会得到 `503 OBJECT_STORAGE_UNAVAILABLE`，不能完成真实 OSS 直传。
- 在本地模式实现并验收之前，上述本地环境变量只是目标接口，不代表当前程序已经支持。

## 5. App 获得的授权

Service 返回的是对象/part 级别的短期授权，例如：

```json
{
  "part_number": 1,
  "grant": {
    "url": "https://<private-bucket>.oss-cn-hangzhou.aliyuncs.com/media/<owner>/<session>/<resource>.original?...",
    "method": "PUT",
    "headers": {
      "Content-Type": "application/octet-stream",
      "Content-Length": "4194304"
    },
    "expires_at": "2026-07-31T01:10:00Z"
  }
}
```

它必须满足：

- 只允许一个服务端生成的对象键和一个 part。
- 只允许指定 HTTP 方法、长度和必要 Header。
- 默认短期有效，过期后必须重新向 MineG Service 申请。
- 不能列举 Bucket、改传其他对象、读取其他用户媒体或删除对象。
- URL、签名、Token 和 Bearer 不进入日志、审计 metadata 或客户端持久缓存。

## 6. 推荐 RAM 权限边界

角色只允许：

- 对 `avatars/` 与 `media/` 中已知对象执行必要的定向读取/HEAD。
- 创建、上传 part、列出已知 upload ID 的 parts、完成和中止 multipart。
- 对头像和媒体生成精确对象授权所需的操作。

角色不允许：

- 列举 Bucket、列举全部 multipart 或访问其他 Bucket。
- 修改 Bucket ACL、公共访问、生命周期、跨域或版本配置。
- 永久删除原文件或历史版本。
- 访问与 MineG 无关的前缀。

## 7. 尚未执行的阿里云操作

- [ ] 创建开发用私有 OSS Bucket。
- [ ] 创建开发用 AssumeRole/RAM 策略并限制到测试 Bucket 与必要前缀。
- [ ] 取得一组开发用临时 STS 凭据，确认包含四个字段且有明确过期时间。
- [ ] 为生产 ECS 创建并绑定 MineG RAM Role。
- [ ] 配置生产 Bucket、地域、内网 Endpoint 和角色名。
- [ ] 验证 Bucket 阻止公共访问，App 只能使用短期签名 URL。
- [ ] 验证越权对象键、过期 URL、错误长度/SHA-256、列桶与删除均被拒绝。
- [ ] 核对服务/API/客户端日志不包含 Secret、Token、签名 URL 或媒体正文。

## 8. 项目后续实现任务

1. 在配置层增加仅 `local/test` 可用的临时 STS provider 和公网 OSS Endpoint。
2. `deployment` 继续强制 `ecs_ram_role + IMDSv2`，并拒绝通过环境变量注入 AccessKey。
3. 本地启动脚本只读取显式的开发 Secret，不提供长期 AccessKey 默认值。
4. 增加配置负测试，确保本地/部署凭据来源不能混用。
5. 使用真实开发 Bucket 完成本地 Service → App → OSS → Service 核对闭环。

## 官方参考

- [阿里云 ECS 实例 RAM 角色](https://help.aliyun.com/zh/ecs/user-guide/attach-an-instance-ram-role-to-an-ecs-instance)
- [阿里云 OSS RAM 权限策略](https://help.aliyun.com/zh/oss/user-guide/ram-policy/)
- [使用 STS 临时访问凭证授权上传文件至 OSS](https://help.aliyun.com/zh/oss/developer-reference/use-temporary-access-credentials-provided-by-sts-to-access-oss)
- [OSS 分片上传及其 RAM 权限](https://help.aliyun.com/zh/oss/user-guide/multipart-upload)
