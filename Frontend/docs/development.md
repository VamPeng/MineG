# 管理端本地开发

推荐使用本地脚本完成依赖安装、OpenAPI 类型生成、Lint、单元测试、生产构建和 HTTPS 开发服务启动：

```bash
cd Frontend
./local-frontend.sh
```

没有配置自定义证书时，脚本会自动通过 Homebrew 安装 `mkcert`，在 `Frontend/.local/tls/` 生成并信任 localhost 证书；首次运行可能要求输入 macOS 密码。证书和私钥均被 Git 忽略，不需要创建 `.env.local`。如需使用已有证书，仍可通过 `MINEG_DEV_TLS_KEY` 与 `MINEG_DEV_TLS_CERT` 覆盖。开发服务器启动成功时会同时显示与后端 bootstrap 配置一致的本地管理员用户名和密码。

只生成可部署到静态服务器的 `dist/` 产物：

```bash
./local-frontend.sh --build-only
```

已确认 `node_modules` 与质量检查结果未变化时，可使用 `--skip-deps`、`--skip-check` 缩短本地启动时间。脚本默认把同源 `/api` 代理到 `http://127.0.0.1:8080`；使用其他后端时传入 `--api-target URL`。

管理员 Cookie 永远带 `Secure`，因此真实登录联调必须使用 HTTPS；本地脚本会处理证书并强制使用同源 `/api`。后端的 `MINEG_ADMIN_ORIGIN` 必须与浏览器地址完全一致，例如 `https://localhost:5173`。

API 类型只从 `Service/api/openapi.yaml` 生成。`api:check` 会拒绝未同步的生成结果。生产环境使用同源 `/api` 路由和 Secure/HttpOnly/SameSite Cookie；前端既不读取 Session Cookie，也不在 Web Storage 保存 Token。

阶段 02 及阶段 03 只允许重新生成类型和做兼容/隔离回归，不新增资料、头像、密钥、上传、媒体或成员页面。Vitest 会固定管理端 API client 方法集合和路由集合；服务端测试另行确认管理员 Cookie 不能访问移动端上传/媒体接口，移动 Bearer 也不能访问管理员接口。生产构建后还应扫描 `dist/`，拒绝 `/api/v1/uploads`、`/api/v1/media`、OSS 主机、AccessKey 或 SecurityToken。

## 阶段 01 浏览器验收

- Chrome、Firefox、Safari 当前稳定版桌面宽度下，登录壳可见且无横向滚动。
- Tab 可聚焦跳转链接和所有交互控件；焦点环清晰可见，Escape 可取消确认框。
- 未认证访问 `/` 会跳转 `/login?redirect=/`；登录恢复成功后回到待审核列表，不存在模拟登录入口。
- 登录、空列表、游标加载、详情、二次确认、并发已处理、CSRF 失效和 Session 过期均由真实 API 结果驱动。
- 页面只展示脱敏手机号、注册时间和申请状态；不提供媒体、密钥、成员管理、反馈或清理入口。
- 错误状态只展示稳定标题、错误码和 request ID，不显示响应 `detail`、堆栈或内部地址。
- 加载状态使用 `role=status`，错误使用 `role=alert`，通知由 Element Plus 的无障碍语义输出。
