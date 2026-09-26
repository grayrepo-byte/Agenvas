# 画布外页面：Beautiful UI 黑色风格（2026-09-26）

## 行为与范围

- 登录与初始化使用共享 `AuthLayout`，黑色居中表单、字段提示、等待禁用、错误重试及已初始化入口。
- 项目与设置页使用共享 `PageShell`：可折叠侧栏、真实路由与当前页标记、用户信息、退出和跳至正文入口；窄屏切换顶部导航。折叠状态仅属于 Zustand 交互状态。
- `shared/ui/PagePrimitives` 与 `PageTheme.css` 统一面板、表单、按钮、状态标签、提示和空态。原画布加载组件迁到 `shared/ui/LoadingState`，所有画布调用改为引用共享实现；动画和业务状态逻辑不变，没有复制第二套加载器。
- 项目支持已加载范围搜索、真实游标分页及分页失败重试。重命名草稿固定开始编辑时的版本，失败保留输入，成功才关闭。创建与归档等待期间禁止重复提交。
- 媒体连接和能力分层展示；默认能力、启停直接可见，模型/画质/并发参数展开编辑。连接与能力草稿固定版本，后台刷新与冲突后保留输入、禁止过期提交，并提供显式载入最新入口。参数和并发分别保存时保留另一部分未保存输入。
- LLM 草稿固定版本；保存完成后清空密钥输入，冲突读取新快照但不覆盖草稿。费用确认与诊断成功提示绑定配置版本，换版后失效；诊断冲突可刷新恢复，每次调用仍须人工确认费用。
- 会话首次读取失败提供重试；已有会话的非 401 后台失败保留页面与草稿，401 转登录。退出成功后才清空查询缓存。系统诊断刷新失败保留上次快照并说明未更新，修改密码等待时禁用输入，响应后清空密码。

涉及 `frontend/src/features/auth`、`features/projects`、`features/settings`、`shared/ui`、App 入口与全局底色；画布只更换共享加载器的导入。没有修改业务 API、生成类型、后端、依赖或数据库迁移。规格、任务清单及 ADR 0005 的页面范围已同步。

## 来源与复用

实际查看 [Beautiful UI](https://www.beautifului.dev/) 的黑色 Sidebar 示例，并读取其 [SidebarNav](https://github.com/slev12397/beautiful-ui/blob/main/components/primitives/SidebarNav.tsx)、[ContextCards](https://github.com/slev12397/beautiful-ui/blob/main/components/primitives/ContextCards.tsx)、[RecordsTable](https://github.com/slev12397/beautiful-ui/blob/main/components/primitives/RecordsTable.tsx) 源码；LoadingState 沿用此前已改编实现。保留 `shared/ui/beautiful-ui-LICENSE.txt` 的 MIT 归属。采用真实应用路由、原生可访问控件、响应式布局及减少动态效果，不使用示例数据、延迟模拟或虚构完成进度。

## 已执行检查

- `corepack pnpm build`：`next typegen`、`tsc --noEmit` 与 Next.js 16.3.6 静态导出通过。
- `corepack pnpm lint`：前端 ESLint 通过，无警告。
- 定向 Vitest 覆盖 10 个文件、46 项：`App`、`PageShell`、`SetupPage`、`LoginPage`、`ProjectsPage`、`LlmSettingsPage`、`MediaSettingsPage`、`SystemDiagnosticsPage`、`PasswordChangeSection`、原 `CanvasLoadingState`。
- 最终合并检查中 9 个文件的 35 项通过；媒体页冲突测试补齐 MSW ProblemDetail 的 `application/problem+json` 响应头后，单独复跑该文件的 11 项全部通过。未以普通 JSON 绕过客户端错误解析，也未更改实际 API。
- `git diff --check`：通过。

未运行全量测试与后端测试；本轮为前端呈现和交互改动。

## 未验证限制

确认本地 5173 端口监听后，内置浏览器访问 `http://localhost:5173/login` 返回 `net::ERR_BLOCKED_BY_CLIENT`。没有绕过限制，未取得实现截图、实际视口布局、键盘/指针操作或控制台验收结果。待核对项见 [design-qa.md](../../design-qa.md)；类型检查、组件测试和静态构建不能替代视觉一致性验收。

没有真实 Provider 调用、付费诊断、实际改密、部署或生产数据操作；不会把界面中的“已配置”视为真实生成能力已经验证。
