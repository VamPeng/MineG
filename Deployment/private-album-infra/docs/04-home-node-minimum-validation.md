# 家庭 Linux 节点 V0 最小验证部署手册

## 1. 文档状态

- 适用阶段：V0，家庭局域网服务可达性验证
- 执行主机：准备作为私人云节点的 Linux 笔记本
- 是否需要 ECS：不需要
- 是否验证 WebRTC：不验证
- 是否验证照片上传：不验证
- 是否允许真实照片和正式账号：不允许
- 最后更新：2026-07-29

本手册用于证明：指定 Git 提交可以在家庭 Linux 笔记本启动 PostgreSQL 和 MineG API，家庭局域网中的另一台设备能够访问该 API。

当前仓库尚未实现家庭本地 `ObjectStore`、WebRTC PeerConnection、DataChannel 和 ECS 协调服务。完成本手册不能宣称“公网 App 已可直连家庭节点”或“照片已经可以上传到家庭节点”。后续 V1 边界见本文第 11 节。

## 2. 当前能力与阻塞项

| 能力 | 当前状态 | V0 是否使用 |
| --- | --- | --- |
| Go REST API | 已实现 | 是 |
| PostgreSQL migration 与健康检查 | 已实现 | 是 |
| `/api/v1/platform/probe` | 已实现 | 是 |
| Android Debug 私网 HTTP 地址注入 | 已实现 | 可构建验证 |
| 当前 Android 主界面调用真实 API | 未接入，当前使用 Mock 状态 | 否 |
| 家庭本地对象存储 | 未实现 | 否 |
| WebRTC DataChannel | 未实现 | 否 |
| ECS WSS 信令与 STUN | 未实现 | 否 |
| 阿里云 OSS | 旧架构能力，本轮不配置 | 否 |

因此，V0 的终端条件是“手机浏览器或另一台局域网设备能得到探针 JSON”。App 内真实探针需要先补一个 Debug 验证入口，不能用 Mock 页面成功代替网络成功。

## 3. 部署输入

开始前由项目维护者提供：

- 仓库地址，通过可信渠道发送，不写入本文；
- 要验证的完整 Git commit SHA，不能只说“最新 main”；
- 家庭局域网网段，例如 `192.168.1.0/24`；
- 笔记本在家庭局域网中的 IPv4 地址；
- 一台与笔记本连接同一家庭网络的手机或电脑。

不要通过聊天或部署日志传递 Git Token、SSH 私钥、数据库密码、Cookie 或 `.env` 内容。私有仓库优先使用已经配置好的 SSH agent 或平台凭据助手。

## 4. 获取并确认项目

在笔记本执行：

```bash
git clone <REPOSITORY_URL> MineG
cd MineG
git switch --detach <COMMIT_SHA>
git status --short --branch
git rev-parse HEAD
```

验收要求：

- `git rev-parse HEAD` 与维护者提供的完整 commit SHA 一致；
- 工作区没有意外修改；
- 不从未核定的远程分支直接部署；
- 后续重新验证时记录新的 commit SHA，不覆盖本次记录。

如果文档或代码尚未提交并推送，笔记本通过 clone 无法获得这些内容。必须先完成发布，再开始本手册。

## 5. 只读环境检查

```bash
cd Deployment/private-album-infra
./scripts/check-server.sh
```

确认：

- Linux 架构为 `x86_64`；
- Docker、Docker Compose 和 Git 可用；
- 磁盘与内存满足测试需要；
- TCP 5432 和 8080 没有被未知服务占用。

若端口已占用，先识别现有进程，不停止、不删除未知服务，也不要直接改变端口继续掩盖冲突。

检查笔记本是否可能休眠：

```bash
systemctl status sleep.target suspend.target hibernate.target hybrid-sleep.target
```

V0 短时验证期间保持接通电源并禁止手工合盖。正式家庭节点部署前再核定自动休眠、合盖和断电恢复策略。

## 6. 构建 V0 服务镜像

从仓库根目录执行：

```bash
docker build --pull -t mineg-api:home-v0 Service
```

记录镜像 ID：

```bash
docker image inspect mineg-api:home-v0 --format '{{.Id}}'
```

V0 使用仓库当前 Dockerfile 构建 API、migration 和管理员初始化工具。它不是最终家庭节点镜像，因为尚未包含 WebRTC 和本地对象存储实现。

## 7. 启动 PostgreSQL

V0 数据库只绑定笔记本回环地址，不向家庭局域网暴露 5432：

```bash
docker run -d \
  --name mineg-postgres-v0 \
  --restart unless-stopped \
  -e POSTGRES_DB=mineg \
  -e POSTGRES_USER=mineg \
  -e POSTGRES_PASSWORD=local-only-change-me \
  -p 127.0.0.1:5432:5432 \
  -v mineg-postgres-v0:/var/lib/postgresql \
  postgres:18
```

等待数据库就绪：

```bash
docker exec mineg-postgres-v0 pg_isready -U mineg -d mineg
```

`local-only-change-me` 只允许用于本次无真实数据、数据库仅绑定回环地址的 V0。正式家庭部署必须使用独立随机密码并通过本地秘密文件注入。

如果同名容器已经存在，先执行以下只读命令确认来源，不直接删除：

```bash
docker ps -a --filter name=mineg-postgres-v0
docker inspect mineg-postgres-v0
```

## 8. 配置、迁移和启动 API

从仓库根目录创建未被 Git 跟踪的本地配置：

```bash
cp Service/.env.example Service/.env
chmod 600 Service/.env
git check-ignore Service/.env
```

`git check-ignore` 必须确认该文件由仓库根 `.gitignore` 忽略。V0 保持：

- `MINEG_ENV=local`；
- `MINEG_HTTP_ADDRESS=:8080`；
- `MINEG_DATABASE_URL` 指向 `127.0.0.1:5432`；
- 四个 `MINEG_OSS_*` 变量为空。

不要把环境改成 `deployment`：当前配置校验会要求旧架构的阿里云 OSS 参数，家庭本地对象存储尚未实现。

执行数据库 migration：

```bash
docker run --rm \
  --network host \
  --env-file Service/.env \
  --entrypoint /app/mineg-migrate \
  mineg-api:home-v0 up
```

启动 API：

```bash
docker run -d \
  --name mineg-api-v0 \
  --restart unless-stopped \
  --network host \
  --env-file Service/.env \
  mineg-api:home-v0
```

`--network host` 只适用于本手册指定的 Linux 笔记本。API 会监听 TCP 8080；PostgreSQL 仍只绑定回环地址。

## 9. 笔记本本机验收

```bash
curl --fail --silent --show-error http://127.0.0.1:8080/health/live
curl --fail --silent --show-error http://127.0.0.1:8080/health/ready
curl --fail --silent --show-error http://127.0.0.1:8080/api/v1/platform/probe
```

预期结果分别包含：

```json
{"status":"alive"}
{"status":"ready"}
{"status":"ok","api_version":"v1"}
```

查看容器状态和最近日志：

```bash
docker ps --filter name=mineg-api-v0 --filter name=mineg-postgres-v0
docker logs --tail 100 mineg-api-v0
```

日志中不得出现密码、Token 或 `.env` 全文。

## 10. 家庭局域网验收

查询笔记本局域网地址：

```bash
ip -br -4 address show scope global
```

如果 UFW 已启用，先查看状态：

```bash
sudo ufw status verbose
```

仅当 UFW 阻止局域网访问时，按真实家庭网段添加精确规则：

```bash
sudo ufw allow from <LAN_CIDR> to any port 8080 proto tcp comment 'MineG V0 LAN only'
```

该命令会修改防火墙，只允许指定家庭网段访问 8080。不要把 `<LAN_CIDR>` 写成 `0.0.0.0/0`，不要在路由器配置 8080 公网端口转发。

在另一台局域网电脑执行：

```bash
curl --fail --silent --show-error http://<LAPTOP_LAN_IP>:8080/api/v1/platform/probe
```

也可以在连接同一 Wi-Fi 的手机浏览器打开：

```text
http://<LAPTOP_LAN_IP>:8080/api/v1/platform/probe
```

看到包含 `"status":"ok"` 的 JSON，证明手机到家庭笔记本的局域网路径正常。它不证明 Android App 已接入真实 API。

### 10.1 Android Debug 构建准备

现有构建脚本允许向 Debug APK 注入局域网 API 地址：

```bash
./Mobile/MineG_Android/build-apk.sh \
  --debug \
  --api-base-url http://<LAPTOP_LAN_IP>:8080 \
  --install
```

但当前活动的 `MineGAppViewModel` 使用 Mock Repository，没有调用该地址。完成以下代码任务后，才能把 Android App 纳入 V0 验收：

- 在 Debug 界面增加“家庭节点探针”；
- 通过 `AndroidTransportPort` 调用 `/api/v1/platform/probe`；
- 页面展示目标节点地址、HTTP 状态、`request_id` 和成功/失败；
- Release 不允许明文 HTTP 或手工忽略证书错误；
- 自动化测试覆盖成功、超时、拒绝和错误 JSON。

## 11. V1 WebRTC 最小验证边界

V0 通过后，下一阶段才验证“不经过 ECS 的 WebRTC 直连”。V1 建议实现：

- 家庭节点一个独立的 WebRTC POC 进程；
- Android Debug 一个独立 POC 页面；
- 一个可靠、有序 DataChannel；
- App 发送 `probe` 并接收 `ok`；
- App 以不超过 16 KiB 的消息分块发送测试文件；
- 家庭节点写入临时目录并返回长度和 SHA-256；
- 双方先通过文件、粘贴或二维码手工交换完整 SDP offer/answer。

静态配置可以包含：

```json
{
  "config_version": 1,
  "signaling_mode": "manual",
  "home_id": "poc-home",
  "ice_servers": [],
  "node_dtls_fingerprint": "sha-256 <EXPECTED_FINGERPRINT>"
}
```

SDP、ICE username/password、candidate、映射公网 IP/端口和 `session_id` 必须在每次连接动态生成，不能写死为长期配置。V1 可在 ICE gathering 完成后把 candidate 一并放入 SDP，通过人工方式交换，因此不需要 ECS。

V1 验收顺序：

1. App 与笔记本连接同一家庭 Wi-Fi，无 STUN，手工交换信令并传输测试文件。
2. 校验 App 与笔记本计算的 SHA-256 一致。
3. App 切换移动网络，配置 STUN，再次手工交换信令。
4. 记录直连成功/失败、candidate pair 类型、建连耗时和吞吐。
5. V1 成功后才开发 ECS WSS，把相同信令 JSON 自动转交；照片数据仍不经过 ECS。

## 12. V0 验收记录

执行人员只回传以下非敏感结果：

| 项目 | 结果 |
| --- | --- |
| Git commit SHA |  |
| Linux 发行版与架构 |  |
| Docker/Compose 版本 |  |
| API 镜像 ID |  |
| PostgreSQL ready | 通过 / 失败 |
| API live | 通过 / 失败 |
| API ready | 通过 / 失败 |
| 本机 platform probe | 通过 / 失败 |
| 第二台局域网设备 probe | 通过 / 失败 |
| 手机浏览器 probe | 通过 / 失败 |
| 错误摘要 |  |

不要回传 `.env`、`docker inspect` 中的完整环境变量、数据库密码、管理员密码或任何 Token。

## 13. 停止与恢复

暂时停止服务但保留数据库：

```bash
docker stop mineg-api-v0 mineg-postgres-v0
```

恢复：

```bash
docker start mineg-postgres-v0
docker exec mineg-postgres-v0 pg_isready -U mineg -d mineg
docker start mineg-api-v0
```

本手册不提供删除数据库 volume 的命令。V0 完成后如需永久清理数据，必须先确认精确 volume 和恢复需求，再单独执行清理流程。

## 14. V0 完成定义

同时满足以下条件才算完成：

- 笔记本部署的是记录过完整 SHA 的核定提交；
- PostgreSQL 和 API 重启后可以自动恢复；
- 本机三个探针通过；
- 第二台家庭局域网设备可以访问 `platform/probe`；
- 8080 没有通过家庭路由器暴露公网；
- PostgreSQL 只绑定 `127.0.0.1:5432`；
- 没有配置阿里云 OSS，没有使用真实照片和正式账号；
- 验收记录没有包含秘密。

V0 完成后，下一项开发工作不是部署 ECS，而是实现 Android Debug 真实探针和 V1 手工信令 WebRTC POC。
