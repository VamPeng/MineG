# OSS 与 ECS 实例 RAM 角色

## 设计

应用运行在 ECS 内，通过实例元数据服务获取 RAM 角色的 STS 临时凭证。服务器和仓库中都不保存长期 AccessKey。

推荐使用元数据加固模式（IMDSv2）。验证元数据服务时只输出状态和有效期，不输出临时 AccessKey、Secret 或 Token。

## 当前权限模型

以下是脱敏后的等价策略：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:GetObject",
        "oss:PutObject",
        "oss:AbortMultipartUpload",
        "oss:ListParts"
      ],
      "Resource": [
        "acs:oss:*:*:<bucket-name>/avatars/*",
        "acs:oss:*:*:<bucket-name>/media/*"
      ]
    },
    {
      "Effect": "Deny",
      "Action": [
        "oss:DeleteObject",
        "oss:DeleteObjectVersion"
      ],
      "Resource": "acs:oss:*:*:<bucket-name>/*"
    }
  ]
}
```

显式拒绝优先于允许。因此，应用只能在 `avatars/` 和 `media/` 前缀下上传、定向读取/HEAD 和处理已知 multipart，会被拒绝列举 Bucket、列举全部 multipart 以及永久删除原文件或历史版本。`oss:PutObject` 覆盖创建、上传 part 和完成 multipart；`oss:ListParts` 只允许读取已知对象键与 upload ID 的 part 状态。

## ossutil

验证使用 ossutil 2.3.0。该版本的命令行帮助将实例角色模式命名为 `EcsRamRole`，二进制包含 IMDSv2 的令牌端点与请求头逻辑。

在与 Bucket 相同地域的 ECS 内，应使用 OSS 内网 Endpoint：

```text
https://oss-<region-id>-internal.aliyuncs.com
```

只读验证：

```bash
./scripts/check-oss-readonly.sh
```

预期行为：

- 对 `.env` 中指定的非敏感探针对象执行定向 `stat` 成功。
- 指定 Bucket 的对象列举被拒绝；全账号 `ListBuckets` 同样不属于在线应用权限。
- `avatars/` 和 `media/` 前缀的受控 `PutObject`/`GetObject` 以及媒体 multipart 操作成功。
- `DeleteObject` 返回拒绝，符合原文件保护策略。

## 删除与回收站

相册界面中的删除操作：

1. 在应用数据库中标记为已删除。
2. 从私人空间和家庭相册的正常视图隐藏。
3. 原文件保留在 OSS 原位置，不移动到其他前缀。
4. 需要物理清理时，使用独立的高权限运维流程，不给在线应用长期删除权限。

## 官方参考

- [阿里云 ECS 实例 RAM 角色](https://help.aliyun.com/zh/ecs/user-guide/attach-an-instance-ram-role-to-an-ecs-instance)
- [阿里云 ossutil 2.0](https://help.aliyun.com/zh/oss/developer-reference/ossutil-overview/)
- [阿里云 OSS RAM 权限策略](https://help.aliyun.com/zh/oss/user-guide/ram-policy/)
