# 管理端本地开发

```bash
cd Frontend
npm ci
npm run api:generate
npm run check
npm run dev
```

API 类型只从 `Service/api/openapi.yaml` 生成。`api:check` 会拒绝未同步的生成结果。生产环境使用同源 `/api` 路由和 Secure/HttpOnly/SameSite Cookie；前端既不读取 Session Cookie，也不在 Web Storage 保存 Token。

## 阶段 00 浏览器验收

- Chrome、Firefox、Safari 当前稳定版桌面宽度下，登录壳可见且无横向滚动。
- Tab 可聚焦跳转链接和所有交互控件；焦点环清晰可见，Escape 可取消确认框。
- 未认证访问 `/` 会跳转 `/login?redirect=/`，不存在模拟登录入口。
- 错误状态只展示稳定标题、错误码和 request ID，不显示响应 `detail`、堆栈或内部地址。
- 加载状态使用 `role=status`，错误使用 `role=alert`，通知由 Element Plus 的无障碍语义输出。
