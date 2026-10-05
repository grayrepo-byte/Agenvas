# ADR 0009：Next.js 只作为静态页面构建层

状态：已被 [ADR 0011](0011-revert-to-vite.md) 取代（2026-09-26）。以下内容保留为历史决策记录。

> 该决策在生效数小时后回退。Beautiful UI 的引入不依赖 Next.js，迁移所换取的独特能力也被 AGENTS.md §3 禁用，详见 ADR 0011。

前端从 Vite 迁移到 Next.js 16，但产品仍是由浏览器直接访问 Spring Boot API 的高交互 SPA。采用 App Router 的 optional catch-all 页面和 `output: "export"`，生产物继续由 Nginx 静态托管；不增加 Route Handler、Server Action、SSR 数据获取、Next.js API 或 Node 生产运行时。

React Router 暂时保留在 client-only 入口内。项目 URL 含构建时未知的 UUID，而 Next.js 静态导出不能为这些动态参数生成有限清单；保留客户端路由可维持 `/projects/:projectId` 的直达、刷新、历史导航和现有测试语义。代价是当前只获得 Next.js 的构建、静态页面壳和后续渐进迁移入口，不使用其服务端渲染或完整文件路由能力。

开发模式可用 Next.js rewrite 将 `/api` 转发到本机 Spring Boot，以保持同源 Cookie、CSRF 和 SSE 联调；该 rewrite 不进入生产静态构建。业务状态、鉴权、API 和外部副作用仍全部属于 Spring Boot，Nginx 继续在生产统一代理 `/api` 并关闭 SSE 缓冲。
