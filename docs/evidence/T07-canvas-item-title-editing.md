# T07 CanvasItem 卡片标题原位编辑证据

日期：2026-09-27。

## 行为与数据归属

Artifact 卡片标题支持双击原位编辑，也可在标题获得焦点后按 F2/Enter 进入。输入态沿用黑色画布视觉，使用紧凑紫色描边；Enter 或失焦保存，Esc 取消。空标题、HTTP 失败和乐观锁冲突不会关闭编辑器或丢弃草稿。

可编辑标题属于单张 `CanvasItem`，通过既有 `POST /api/v1/projects/{projectId}/canvas/commands` 的 `UPDATE_TITLE` 命令保存，并使用 CanvasItem `expectedVersion`。保存不修改 Artifact 标题、Artifact CAS、ArtifactVersion 或任务输入；同一 Artifact 的多张卡片可独立命名。新卡片从被展示 Artifact 的标题或 Agent 名称初始化。

V49 为旧 `canvas_item` 增加非空 `title`：Artifact 卡片从 `artifact.title` 回填，Agent 卡片从 `agent_instance.name` 回填，然后添加 1–160 字符数据库约束。生成的 jOOQ 源码已按 ADR 0012 从一次性 PostgreSQL 17 数据库重新生成。

## 检查范围

- `CanvasItemTitleEditor.test.tsx` 覆盖双击、CanvasItem CAS 请求、Artifact 名称不变、空标题、冲突草稿保留、Esc 和 F2。
- `CanvasPostgresIT` 覆盖新卡片标题初始化、同一 Artifact 多卡片独立改名、规范化、幂等重放、旧 CAS 冲突及布局更新保留标题。
- `CanvasItemTitleUpgradePostgresIT` 从 V48 旧数据升级到 V49，验证 Artifact/Agent 两种旧卡片均正确回填，并验证数据库拒绝空标题。
- 权威 OpenAPI 新增 CanvasItem `title` 和 `UPDATE_TITLE` 命令；生成 TypeScript 类型不手改。

## 实际运行结果

- `corepack pnpm api:generate`：通过，重新生成 `frontend/src/shared/api/schema.ts`。
- `./mvnw -q -Pjooq-codegen -Djooq.codegen.jdbcPassword=agenvas generate-sources`：通过，迁移一次性 PostgreSQL 17 数据库后重新生成 jOOQ 源码；临时容器随后已删除。
- `./mvnw -q clean -Dit.test=CanvasPostgresIT,CanvasItemTitleUpgradePostgresIT verify`，以及最终断言调整后的同范围 `verify`：通过。Maven 生命周期同时执行 125 个现有后端单元测试，并在 PostgreSQL 17.11 上执行指定的 2 个集成测试；报告中失败、错误、跳过均为 0。控制台出现的存储/Provider 故障日志来自现有故障路径测试。
- `corepack pnpm test -- --run ...`：通过；当前脚本实际执行全部前端测试，共 43 个测试文件、270 个用例。
- `corepack pnpm typecheck`、`corepack pnpm lint`、`corepack pnpm build`：均通过。
- `git diff --check`：通过。

## 未验证限制

未运行完整后端集成测试集；未在完整登录工作区中连接真实后端执行浏览器端到端保存，也未调用真实 Provider。标题编辑视觉态此前已在临时并排核对页检查，范围记录在 `design-qa.md`。
