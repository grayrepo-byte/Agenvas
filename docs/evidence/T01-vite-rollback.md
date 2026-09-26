# T01 前端构建回退到 Vite 证据

2026-09-26。按 [ADR 0011](../adr/0011-revert-to-vite.md) 将前端页面构建从 Next.js 16 静态导出回退到 Vite 8.3.0。产品形态不变：仍是 client-only SPA，生产产物由既有 Nginx 提供，页面通过同源 `/api/v1` 访问 Spring Boot。React Router 继续负责构建时未知的 `/projects/:projectId` UUID 深链。

## 改动

恢复迁移前的 Vite 入口与配置：`frontend/index.html`、`frontend/src/main.tsx`、`frontend/vite.config.ts`（含 dev 端口 5173 与 `/api` → `http://localhost:8080` 代理）、`frontend/tsconfig.json`、`frontend/package.json`、`frontend/eslint.config.js`、`frontend/pnpm-lock.yaml`。

删除 Next 专属文件：`frontend/next.config.ts`、`frontend/next-env.d.ts`、`frontend/postcss.config.mjs`、`frontend/vitest.config.ts`、`frontend/src/app/layout.tsx`、`frontend/src/app/[[...slug]]/`。`frontend/src/app/` 保留既有的 `App.tsx`、`App.test.tsx`、`queryClient.ts`。测试配置并回 `vite.config.ts`，不再单列 `vitest.config.ts`。

依赖变化：移除 `next`、`serve`、`@tailwindcss/postcss`；加入 `vite` 8.3.0、`@vitejs/plugin-react` 6.1.1、`@tailwindcss/vite` 4.3.3。`@phosphor-icons/react` 2.1.10 是 Beautiful UI 适配期新增的运行依赖，不在迁移前的 lock 中，恢复时必须保留。

`@vitejs/plugin-react` 重新加入的依据：`e32dc40` 移除它的前提是「Fast Refresh 能力未被使用」，该前提仅在 Next.js 托管页面、Vite dev server 未运行时成立。回退后 dev server 重新启用，实测页面注入了 `/@react-refresh`（见下）。

构建产物目录由 `out` 回到 `dist`，同步 `deploy/docker/frontend.Dockerfile`、`.dockerignore` 与 `frontend/e2e/api-performance-compose.mjs`。`.gitignore` 无需改动：项目自有段落本来就忽略 `frontend/dist/`，模板中的 `.next`/`out` 段落保留未动。

## 实际检查

- `pnpm install --frozen-lockfile`：通过，锁文件与 `package.json` 一致。
- `pnpm typecheck`（`tsc --noEmit`）：返回 0。
- `pnpm lint`（`eslint . --max-warnings=0`）：返回 0。
- `pnpm test`（Vitest 5.0.1）：34 个文件、212 项全部通过。
- `pnpm build`：返回 0，产物输出到 `dist/`（`vite build` 报告 322ms）。
- dev server：Vite 8.3.0 在 `localhost:5173` 就绪（227ms）。`GET /` 返回 200 且页头含 `@react-refresh` 注入，证明 React Fast Refresh 生效；`GET /src/main.tsx` 返回 200；`GET /api/v1/auth/setup-status` 经代理到达本机 Spring Boot（8080）返回 200；`GET /projects/<uuid>` 深链返回 200。

## 未验证

- 未运行后端测试，未修改后端、OpenAPI 合约或数据库迁移。
- 未构建容器镜像，未跑 Compose 全链路，未重跑 `frontend/e2e/` 下的浏览器性能与画布脚本。
- 未做浏览器视觉验收；`design-qa.md` 中依赖 Next.js dev server 的既有记录未更新。
- 本机验证用 `pnpm dev` 手动启停，未覆盖生产 Nginx 托管的 `dist/` 深链与 SSE 代理。
