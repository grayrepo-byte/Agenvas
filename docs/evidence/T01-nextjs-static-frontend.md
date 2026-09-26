# T01 Next.js 静态前端迁移证据

2026-09-26。前端从 Vite 应用入口迁为 Next.js 16.3.6 App Router 静态导出。`src/app/[[...slug]]` 只导出一个 client-only SPA 壳，生产 `out/` 继续由既有 Nginx 提供；页面仍通过同源 `/api/v1` 访问 Spring Boot。未新增 Route Handler、Server Action、SSR 数据获取、Next.js API 或 Node 生产进程。React Router 暂时保留，以支持构建时未知的 `/projects/:projectId` UUID 深链。

测试配置从 `vite.config.ts` 拆到 `vitest.config.ts`，Tailwind 从 Vite 插件改为 PostCSS 插件。Docker 与本地性能运行器从旧 `dist/` 改读 Next.js `out/`。开发阶段不启用 `output: "export"`，因此任意深链由 Next dev 正常解析；仅开发期 rewrite `/api` 到 `http://localhost:8080`，实测 `/login` 返回 200，代理的 `/api/v1/auth/setup-status` 返回现有 Spring Boot 响应 200。生产构建阶段启用静态导出，不包含该 rewrite。

实际检查：OpenAPI 类型重新生成无额外差异；`tsc --noEmit` 与 `eslint . --max-warnings=0` 返回 0；Vitest 20 个文件、84 项测试全部通过；Next.js 16.3.6/Turbopack 生产构建返回 0 并生成静态 catch-all 页面。静态预览对 `/setup` 和未知项目 UUID 路径均返回同一页面壳，引用的 `/_next/static` JavaScript 可读取。

生产形态另用 `deploy/docker/frontend.Dockerfile` 在锁定的 Node 24.21.0 Alpine 镜像中执行冻结安装、OpenAPI 生成和 Next 构建，最终复制 `out/` 到锁定的 Nginx 1.28.0 镜像；构建返回 0。临时容器中 `nginx -t` 通过，`/login`、未知项目 UUID 深链和静态 JavaScript 均返回 200。Chrome 153 无头访问该 Nginx `/login`，完成客户端水合并出现登录按钮及“自托管模式”文案。该检查未运行完整 Compose 业务路径或后端全量测试；本轮没有修改后端、OpenAPI 合约或数据库迁移。
