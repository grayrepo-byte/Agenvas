# Agenvas 依赖基线

**基线日期：2026-09-23｜适用版本：0.1.0-SNAPSHOT**

本文件记录已经在当前项目解析、编译或构建验证的直接依赖。它是 `MVP-SPEC.md` 第 4.4 节所要求的 M0 基线，不表示后续业务模块或真实 Provider 已经实现。

## 工具链

| 项目 | 冻结版本 | 验证说明 |
|---|---:|---|
| Java | 21 | 本机 Corretto 21.0.9；容器 Temurin 21.0.9+10 |
| Maven Wrapper | 3.9.12 | `backend/mvnw` |
| Node.js | 24.21.0 | 前端构建镜像；`package.json` 接受同一 Node 24 LTS 系列的 24.12+ |
| pnpm | 12.5.1 | `packageManager`、engine、CI 和容器一致 |
| PostgreSQL | 17.11 | Compose 中真实执行 Flyway V1 |

## 后端直接依赖

| 依赖 | 版本来源/版本 |
|---|---:|
| Spring Boot Parent / Maven Plugin | 4.0.8 |
| Spring AI BOM / `spring-ai-client-chat` | 2.0.1 |
| MyBatis-Plus Boot 4 Starter | 3.5.17 |
| springdoc OpenAPI WebMVC UI | 3.0.3（按规格保持 3.0.x） |
| Spring MVC / Security / Session JDBC / Actuator / JDBC / Validation | Boot 4.0.8 依赖管理 |
| Flyway / PostgreSQL JDBC | Boot 4.0.8 依赖管理 |

Spring AI 2.0 不再提供旧教程常见的 `spring-ai-core` 直接模块名；本项目使用 BOM 管理的 `spring-ai-client-chat`，避免混入 1.x API。默认只加载应用自有 Mock `GenerationGateway`，没有凭证时不会创建真实聊天或媒体客户端。

## 前端直接依赖

运行依赖：React/React DOM 19.3.0、React Router 7.18.4、TanStack Query 5.103.2、Zustand 5.0.15、React Flow 12.11.6、React Hook Form 7.88.0、Zod 4.6.5。

构建与测试：Vite 8.3.0、TypeScript 5.9.3、Tailwind CSS 4.3.3、Vitest 5.0.1、Testing Library React 16.3.3、MSW 2.15.0、openapi-typescript 7.13.0、ESLint 10.11.0、typescript-eslint 8.70.1。

没有采用当时最新的 TypeScript 7.0.2，因为 `typescript-eslint` 8.70.1 的正式兼容范围小于 6.1；选择 5.9.3 是经过 peer dependency 核对的稳定组合。React Router 保持规格要求的 7.x，不升级到 8.x。

精确解析结果与完整传递依赖见 `frontend/pnpm-lock.yaml` 和 Maven effective dependency tree；生成的 API 类型来自 `contracts/openapi.yaml`。

## 容器镜像

| 用途 | 精确镜像 | 多架构 digest |
|---|---|---|
| 前端构建 | `node:24.21.0-alpine` | `sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1` |
| 后端构建 | `maven:3.9.12-eclipse-temurin-21-alpine` | `sha256:8b2f036477a5bc9fbeb16cfb7301c484d7fff727b1c4907301ac665526bd7a8e` |
| 后端运行 | `eclipse-temurin:21.0.9_10-jre-alpine` | `sha256:08eecc477dbe3f2e33daac27f36e41daf7f4ec51d2f3396006e54fa41832c74c` |
| Web/Nginx | `nginx:1.28.0-alpine` | `sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284` |
| 数据库 | `postgres:17.11-alpine` | `sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24` |

所有运行容器使用非 root 用户；server 和 web 使用只读根文件系统及受限 tmpfs。默认宿主端口只绑定 `127.0.0.1`。

## 已执行验证

```text
frontend: pnpm api:generate
frontend: pnpm typecheck
frontend: pnpm lint
frontend: pnpm test                 # 1 test passed
frontend: pnpm build                # Vite production build succeeded
backend:  ./mvnw verify             # 4 tests passed; executable JAR built
root:     docker compose config --quiet
root:     docker compose up -d --build
```

Compose 验证使用真实 PostgreSQL 17.11 空库：Flyway V1 成功，`app_user`、`spring_session`、`spring_session_attributes` 和 `flyway_schema_history` 存在；server 及 PostgreSQL 健康；经 Nginx 请求 `/api/v1/auth/setup-status` 返回 HTTP 200 和 `{"setupRequired":true}`；受保护的未实现项目接口返回 HTTP 401。

## 尚未验证或不在本基线范围

- T03 的管理员初始化、登录、退出、改密、初始化竞态和会话重启行为尚未实现。
- T04 的完整 ProblemDetail 映射、契约测试、生成漂移自动门禁和安全扫描尚未完成。
- Spring AI 只验证依赖解析与编译；没有真实 LLM、Tool Calling 或视觉调用。
- ComfyUI、图片、视频与 FFmpeg 未接入；当前只有明确标注的 Mock fixture。
- SSE 配置已写入 Nginx，但端点与断线补发测试属于 M2，尚未实现。
