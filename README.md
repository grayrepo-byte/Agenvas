# Agenvas

Agenvas 是一个可自托管的 AI 创作画布。目标是让 Agent 以可操作卡片存在于画布中，在明确的权限、审批、版本和恢复边界内生成三镜头短片。

当前仓库已完成 **M0/T01–T02 工程骨架**：Vite/React 前端、Spring Boot 模块化单体、PostgreSQL/Flyway、权威 OpenAPI、可重复 Mock Provider、非 root 三服务 Compose 和 CI。管理员初始化、项目/画布、Agent Runtime、SSE、真实图片与视频仍按开发清单逐步实现，不能把当前骨架视为稳定 MVP 成品。

## 已实现的最小纵向切片

浏览器 `/setup` → Nginx → `GET /api/v1/auth/setup-status` → Spring MVC → PostgreSQL `app_user` 查询。

- 空库由 Flyway 创建身份与 Spring Session 基线表。
- 前端 API 类型由 `contracts/openapi.yaml` 生成。
- Mock 媒体模式醒目标识；成功、失败和 UNKNOWN fixture 可重复。
- 未授权 API 默认拒绝；真实登录将在 T03 实现。
- 默认不需要模型 Key、GPU 或作者账户，不发起真实模型请求。

## 快速启动

需要 Docker Desktop 或兼容的 Docker Engine/Compose。

```sh
cp .env.example .env
docker compose -f deploy/compose.yaml up -d --build
```

打开 <http://127.0.0.1:8088/setup>。也可以检查反代后的 API：

```sh
curl http://127.0.0.1:8088/api/v1/auth/setup-status
```

预期空库响应：

```json
{"setupRequired":true}
```

停止服务：

```sh
docker compose -f deploy/compose.yaml down
```

该命令保留数据库和资产卷；如需清除测试数据，应明确使用 Compose 的卷删除选项，并确认没有需要保留的内容。

## 本地开发

前端要求 Node 24 LTS 与 pnpm 12.5.1：

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
```

后端要求 JDK 21；运行时需要 PostgreSQL：

```sh
cd backend
./mvnw verify
./mvnw spring-boot:run
```

本地 Vite 会把 `/api` 代理到 `http://localhost:8080`。默认数据库连接为 `jdbc:postgresql://localhost:5432/agenvas`，可通过 `AGENVAS_DB_URL`、`AGENVAS_DB_USER` 和 `AGENVAS_DB_PASSWORD` 覆盖。

## 仓库结构

```text
frontend/       Vite + React + TypeScript SPA
backend/        Java 21 / Spring Boot 4 模块化单体
contracts/      权威 OpenAPI 与后续事件/Artifact Schema
configs/        版本化 Agent、Skill 与受信媒体工作流配置
deploy/         Compose、Nginx 和容器构建
docs/           MVP 规格、依赖基线与开发验收清单
```

核心规格见 [MVP-SPEC.md](docs/MVP-SPEC.md)，执行顺序见 [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md)，实际验证版本见 [dependency-baseline.md](docs/dependency-baseline.md)。贡献前请同时阅读 [AGENTS.md](AGENTS.md)。

## 当前限制

- 只实现初始化状态读取，尚不能创建管理员或登录。
- 没有项目、画布、Artifact、Run、Task 或审批 API。
- 没有真实 LLM/ComfyUI/FFmpeg 调用；Mock 输出不能证明真实 Provider 已接通。
- 目前的 Flyway V1 只是 M0 身份/会话基线，其余业务表随纵向切片以新增迁移加入。

项目目标许可为 Apache-2.0；正式许可证、NOTICE 与第三方/模型许可证清单在 M6/T30 发布门禁完成前仍属于待办事项。
