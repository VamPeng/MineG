# MineG

## 本地一键验收

连接 Android 真机后，在仓库根目录运行一条命令即可准备本地 HTTPS、启动或复用后端、启动管理前端，并构建安装 Debug APK：

```bash
./local-mineg.sh
```

首次运行可能通过 Homebrew 安装 `mkcert`，并要求确认一次 macOS 系统信任。服务就绪后脚本会显示本地管理后台账号、打开 `https://localhost:5173` 并启动手机上的 MineG；保持当前终端开启。完成验收后按一次 `Ctrl+C`，脚本会停止本次使用的后端和前端、移除 ADB 映射与运行日志，但保留手机上的 App。日常快速重启可使用 `./local-mineg.sh --fast`，其他选项见 `./local-mineg.sh --help`。

## 验收后清理

正常情况下只需在一键验收终端按 `Ctrl+C`。若脚本异常退出或需要单独清理，执行下面的命令；它会停止 MineG 后端和前端、删除 ADB 映射与本地日志，同时保留 App、数据库、构建产物和证书：

```bash
./cleanup-mineg.sh
```

需要彻底删除本地测试账号、数据库、App 数据和可重新生成的构建产物时，显式执行；App 数据会清空，但 APK 仍保留在手机上：

```bash
./cleanup-mineg.sh --full-reset
```

`--full-reset` 不会卸载 App，并会保留 PostgreSQL、Node 依赖、Gradle 缓存、`mkcert` 与 localhost 证书，便于下次验证。其他细分选项见 `./cleanup-mineg.sh --help`。

MineG 是面向本人和家人的私人家庭相册项目。

当前仓库按职责划分为以下目录：

```text
MineG/
├── Mobile/       # 移动端 App
├── Requirement/  # 产品需求与范围
├── Deployment/   # 部署配置与运维说明
│   └── private-album-infra/
├── Service/      # 后端服务
└── Frontend/     # Web 前端与内部管理页面
```

项目当前进入 MVP 阶段 03：阶段 02 契约已经冻结，Android 单媒体端到端加密、可恢复直传和后端密文确认处于基线验收阶段。
