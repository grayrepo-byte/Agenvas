# T28：页面按路由加载与构建体积阶段性证据

2026-09-24。此前前端构建将画布、React Flow 与认证/设置页面打入一个约 568.64 kB 的主 JS chunk（gzip 174.92 kB），Vite 提示超过 500 kB。`App` 现以 React `lazy` + `Suspense` 按路由加载初始化、登录、项目列表、Provider 设置、系统诊断及项目画布，并提供可见加载状态。初始化/登录页同时移除硬编码“Mock 媒体模式 · 不会调用外部模型”断言；未登录时无法读取管理员配置状态，改用中性自托管提示，避免真实 Provider 部署被误标成 Mock。

最终 `corepack pnpm build` 产物：主入口 JS 286.81 kB（gzip 90.34 kB），画布路由 JS 243.90 kB（gzip 75.40 kB），画布 CSS 15.41 kB 另包。`corepack pnpm test` 48 项、`typecheck`、`lint`、`build` 均通过；新增浏览器路由组件测试验证登录与项目列表可从懒加载路径呈现。无后端、OpenAPI 或数据库迁移变更。

这些是 Vite 产物文件大小，不是实际浏览器网络传输、首屏时间、画布 FPS 或内存测量。尚未使用 300 个真实缩略图/媒体卡片与 600 条关系压测，T28 性能门禁仍未勾选。
