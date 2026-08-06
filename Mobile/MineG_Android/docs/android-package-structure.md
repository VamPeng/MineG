# Android 包结构与职责说明

## 目标

Android 代码按“业务职责 + 技术边界”组织，不再按交付阶段 `StageXX` 聚合。`stage02-v2`、
`stage06-v1` 等名称只保留在 Core 的冻结线协议中，不作为 Kotlin 类名或包名。

依赖方向为：

```text
feature/ui -> presentation -> runtime -> bridge -> core
                                  |         |
                                  v         v
                               platform <- core/effect
```

- `presentation` 不直接依赖 JNI、Android 存储或网络实现。
- `bridge` 按业务域构造 Core 命令并映射结果，不持有 Compose 状态。
- `core` 只管理 JNI 生命周期和 effect 协议，不解释业务页面。
- `platform` 实现 Core 所需的窄能力端口，不决定业务成功、重试或权限策略。

## 当前目录

```text
com.mineg.mobile
├── bridge
│   ├── account       # 登录、注册、会话、文字资料
│   ├── backup        # 备份设置、单项备份、持久队列
│   ├── feedback      # 反馈提交
│   ├── internal      # 多个 Gateway 共用的 Core 操作执行细节
│   ├── library       # 本地媒体索引、相册和媒体分页
│   │   └── model
│   ├── media         # 私密媒体、本人媒体摘要和资源生命周期
│   │   └── model
│   ├── profile       # 头像等资料变更
│   ├── shared        # 共享空间与分享状态
│   │   └── model
│   └── trash         # 回收站查询和恢复
├── core
│   ├── effect        # Core effect 到平台 Port 的分发
│   └── protocol      # foundation-v2 操作模型和通用 CoreProblem
├── feature
│   ├── auth          # 登录/注册 UI 与输入校验
│   ├── debug         # 开发调试面板
│   ├── library       # 媒体库、详情和备份页面
│   ├── private_media # 私密媒体保存用例
│   └── profile       # 资料、回收站和反馈页面
├── platform
│   ├── logging       # 脱敏媒体日志
│   ├── port          # Android 无关的平台能力接口
│   └── work          # WorkManager 备份调度
├── presentation      # 路由、UI 状态、ViewModel 和根 Compose
├── runtime           # 应用用例 Facade 与生产依赖装配
└── ui
    ├── component     # 跨 feature 的通用 Compose 组件
    ├── preview       # 原型/预览素材
    └── theme         # 色彩、形状和主题 Token
```

## 关键类职责

| 类 | 单一职责 |
| --- | --- |
| `AccountCoreGateway` | 登录、注册、会话恢复、审批状态和文字资料命令 |
| `ProfileCoreGateway` | 发送已经由 Android 预处理的头像并映射确认资料 |
| `LocalLibraryCoreGateway` | 启动本地库扫描，读取相册和媒体快照 |
| `BackupSettingsCoreGateway` | 读取、更新当前安装的备份开关 |
| `SingleMediaBackupCoreGateway` | 保留的 `stage03-v2` 单项上传命令 |
| `BackupQueueCoreGateway` | Core 持久备份队列的协调、周期执行和进度快照 |
| `OwnerMediaSummaryCoreGateway` | 保留的本人媒体摘要读取接口 |
| `PrivateMediaCoreGateway` | 私密媒体分页、详情、预览、关闭、删除和保存回执 |
| `SharedMediaCoreGateway` | 分享状态、共享媒体分页、详情和预览 |
| `TrashCoreGateway` | 回收站分页和恢复 |
| `FeedbackCoreGateway` | 幂等反馈提交 |
| `CoreContractOperationExecutor` | 为多个业务 Gateway 统一 operation id、终态和错误映射 |
| `CoreClient` | 线程安全地持有和释放 native Core handle |
| `CoreOperationRunner` | 循环执行 Core 请求的 effect，直到终态 |
| `PlatformEffectDispatcher` | 将 effect 路由到 Transport、SecureStore、MediaSource 等 Port |
| `MineGAppRuntime` | 向 presentation 暴露用例，不泄漏 JNI 或 Android 实现 |
| `AndroidMineGAppRuntime` | 生产环境依赖装配、缓存生命周期和后台调度协调 |
| `MineGAppViewModel` | 将 UI 事件和 Runtime 结果归并为单一 `MineGAppState` |
| `AccountInputValidator` | UI 即时校验和手机号规范化；Core 仍是最终权威 |
| `PrivateMediaLocalSaver` | 协调完整性校验后的原图、系统相册写入和 Core 回执 |

## 重命名与拆分对照

| 原名称 | 新名称/拆分 | 原因 |
| --- | --- | --- |
| `CoreAccountClient` | `AccountCoreGateway` | 明确其是 Core 业务边界，不是通用网络 Client |
| `CoreStage02Client` | `ProfileCoreGateway`、`LocalLibraryCoreGateway`、`BackupSettingsCoreGateway`、`OwnerMediaSummaryCoreGateway` | Stage 02 同时包含四个独立业务责任 |
| `CoreStage03Client` | `SingleMediaBackupCoreGateway` | 用业务含义替代阶段编号 |
| `CoreStage04Client` | `BackupQueueCoreGateway` | 类的真实职责是持久备份队列 |
| `CoreStage05Client` | `PrivateMediaCoreGateway` | 类的真实职责是私密媒体访问和资源生命周期 |
| `CoreStage06Client` | `SharedMediaCoreGateway`、`TrashCoreGateway`、`FeedbackCoreGateway` | 分享、回收站、反馈不是同一业务责任 |
| `AccountValidation` | `AccountInputValidator` | 指明输入校验层级，避免误认为账户权威规则 |
| `AccountProblem` | `CoreProblem` | 错误信封被所有业务域共用，不属于 account |
| `FoundationV2Contracts` | `CoreOperationModels` | 内容是 Core/effect 协议模型，而不是业务契约合集 |
| `FoundationContracts` | `PlatformPorts` | 内容实际是 Android 平台能力接口 |
| `PlatformEffectDispatcher` 内部 Runner | 独立 `CoreOperationRunner` | effect 路由与操作状态机是两个变化原因 |
| `AppModels` | `MineGUiModels` | 明确模型只服务 presentation/UI |
| `LibraryPages` | `MediaLibraryPages` | 文件覆盖本地、私密、共享媒体，不只是本地 library |
| library 内的 `DetailTopBar` | `ui.component.DetailTopBar` | profile 与 media 都使用，属于跨 feature 组件 |

## 方法与注释规范

- 文件头说明该文件的职责、权威边界或数据归属。
- 公开业务方法使用 KDoc 描述业务结果、副作用和重要约束，不重复 Kotlin 类型信息。
- Core 终态判断、分页游标推进、密钥/媒体缓冲清零、资源句柄生命周期、缓存回退顺序等核心逻辑在对应代码旁说明原因。
- `override` 方法以 Port 或 Runtime 接口上的 KDoc 为权威，避免实现类重复一套可能漂移的文档。
- 简单不可变 `data class` 由文件级模型说明和字段名表达，不为构造参数增加同义注释。

## 新增代码放置规则

1. 新的 Core 业务命令先判断所属业务域，放入对应 `bridge/<domain>`；不要创建新的
   `CoreStageXXClient`。
2. 跨业务域复用的只是协议执行机制时，放入 `bridge/internal`；业务规则不得放入
   `internal` 形成新的大杂烩。
3. Android API 实现放入 `platform`，其接口放入 `platform/port`。
4. UI 状态转换放入 `presentation`；可复用视觉组件放入 `ui/component`；仅一个功能使用的
   Compose 函数留在其 `feature`。
5. Wire contract 版本继续随命令发送，但不得反向决定 Kotlin 包结构。
