# ADR 0011：前端构建回退到 Vite

状态：接受（2026-09-26）。取代 [ADR 0009](0009-nextjs-static-frontend.md)。

前端页面构建从 Next.js 16 静态导出回退到 Vite 8。产品形态不变：仍是浏览器直接访问 Spring Boot API 的客户端 SPA，React Router 负责运行时 URL 解析，生产产物继续由 Nginx 静态托管。

## 背景

ADR 0009 的迁移只运行了数小时。复核时确认 Next.js 在实际承担的能力只有打包和一个 HTML 页面壳，其区别于普通打包器的能力全部被禁用或未使用：SSR 被 `ssr: false` 关闭，RSC 未使用，文件路由被 React Router 取代，Server Action 与 Route Handler 被 AGENTS.md §3 明令禁止。ADR 0009 记录的选型理由（保留 React Router 以支持构建时未知的项目 UUID）实际是「不使用 Next.js 完整路由」的理由，而非选择它的理由。

迁移同样被关联到 Beautiful UI 的引入，但两者不存在依赖关系：

- Beautiful UI 以 MIT 源码适配引入（见 `frontend/src/shared/ui/beautiful-ui-LICENSE.txt`），分发形态是 copy-paste 的组件原语，官方站点未声明任何框架绑定。
- 上游 primitives 源码自身是纯 React（`react`、`react-dom`、图标包）。
- 本项目适配后的文件（`PageShell.tsx`、`PagePrimitives.tsx`、`LoadingState.tsx`、`PageTheme.css`、`PageShell.css`）零 `next/*` 依赖。
- 时间上 Next.js 迁移（2026-09-26 11:36）早于 Beautiful UI 统一（13:14、14:13）。

## 决策

恢复 `index.html` 与 `src/main.tsx` 入口及 `vite.config.ts`；构建产物目录由 `out` 回到 `dist`，同步 `frontend.Dockerfile`、`.dockerignore` 与 e2e 镜像构建。删除 `next.config.ts`、`next-env.d.ts`、`postcss.config.mjs` 和 `src/app` 下的 Next 专属壳，保留 `src/app` 中既有的 `App.tsx`、`queryClient.ts`。

`@vitejs/plugin-react` 重新加入 devDependencies。`e32dc40` 移除它的前提是「Fast Refresh 能力未被使用」，该前提仅在 Next.js 托管页面、Vite dev server 未运行时成立；回退后 dev server 重新启用，需要它提供 React Fast Refresh。

Tailwind 从 `@tailwindcss/postcss` 回到 `@tailwindcss/vite`，因此不再需要 `postcss.config.mjs`。

## 后果

- `src/app` 回到纯业务目录，前端不再存在第二套路由系统。
- 构建产物回到 `dist`，与 `.gitignore` 既有的 `frontend/dist/` 一致。
- 若将来确需 SSR、RSC 或服务端渲染能力，必须先推翻 AGENTS.md §3 的服务端边界约束并重新评估框架选型，不得为框架能力绕过该约束。
