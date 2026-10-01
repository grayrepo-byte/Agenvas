# RunningHub 合并 main 验证

2026-10-01，用户要求合并回 main。主分支已合入 AutoDL 与画布连线改动；保留这些行为，与 RunningHub 一起交付。合并前 main 工作区干净。

AutoDL 保留 V65 / ADR 0024 / 规格 6.12。尚未发布的 RunningHub 迁移改为 V66，平台约束同时允许 AUTODL 和 RUNNINGHUB；RunningHub ADR 改为 0025、规格为 6.13，相关引用同步。未修改已合入的 V65 迁移，未清空业务数据。

冲突处理保留两边的能力注册、凭据校验、参数冻结、素材限制、画布运行条件、设置表单与契约字段。生成文件由工具重新生成，未手改：隔离 PostgreSQL 17.11 执行 V1–V66 后运行 jOOQ profile；TypeScript 从合并后的 OpenAPI 生成。

实际检查：

```sh
./mvnw -Pjooq-codegen -Djooq.codegen.jdbcUrl=jdbc:postgresql://localhost:55434/agenvas generate-sources
./mvnw clean -Dtest=RunningHubDefinitionTest,RunningHubClientTest,RunningHubAdapterTest,RunningHubImportServiceTest,AutoDlWorkflowsTest,AutoDlClientTest,MediaCapabilityConfigurationTest,ArtifactContentValidatorTest,MediaExecutionSchedulerTest,DebugHttpCaptureTest,ImageOperationSpecTest -Dit.test=RunningHubPostgresIT,AutoDlVideoPostgresIT,TaskArtifactSelectionPostgresIT,DirectMediaGenerationPlacementPostgresIT,AudioMediaPostgresIT,MediaCapabilityPostgresIT verify
```

62 项单元测试、16 项真实 PostgreSQL + 本地假 Provider 集成测试通过，0 失败 / 0 错误；后端 verify 成功。clean 清除了旧编号的构建资源，避免迁移重复加载。

```sh
pnpm api:generate
pnpm typecheck
pnpm test src/features/canvas/RunningHubForm.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/settings/MediaSettingsPage.test.tsx src/features/canvas/taskErrorMessages.test.ts
pnpm build
pnpm exec eslint src/features/canvas/RunningHubForm.tsx src/features/canvas/RunningHubForm.test.tsx src/features/canvas/MediaDraftEditor.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/settings/MediaSettingsPage.tsx src/features/settings/MediaSettingsPage.test.tsx src/features/settings/mediaAdapterCatalog.ts src/features/settings/RunningHubDefinitionEditor.tsx --max-warnings=0
```

60 项前端测试、类型检查、生产构建与定向 ESLint 通过。Vite 部分产物超过 500 kB 的提示仍存在。合并标记扫描与 git diff 检查通过。

本轮未重跑付费生成；此前两份 RunningHub 示例的真实上传、生成、归档和费用核对见 [真实验证记录](runninghub-real-provider-2026-10-01.md)。全量测试、部署、浏览器端到端和生产升级演练未运行。main 合并不等于发布部署。
