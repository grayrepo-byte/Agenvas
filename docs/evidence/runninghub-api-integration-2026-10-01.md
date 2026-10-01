# RunningHub API 与动态表单定向验证

日期：2026-10-01。实现依据为 [ADR 0025](../adr/0025-runninghub-versioned-input-contracts.md)、规格 6.13 与 [接入说明](../research/runninghub-api-integration.md)。本记录的下列初始测试只证明本地固定协议、动态表单和任务行为，使用本地假服务。随后真实 Key 验证已完成，见 [真实 Provider 补充记录](runninghub-real-provider-2026-10-01.md)。第三方 Go SDK 只读审阅，不作为运行依赖。

## 交付内容

- Provider：固定 V2 工作流 / AI 应用提交、原 taskId 查询、FILE_NAME / URL 两类上传，以及受约束的结果下载；三个媒体输出适配器共享同一协议。无自动生成重试、重定向或 Key query。
- 设置：RunningHub 连接、版本化能力、只读候选发现、脱敏 JSON 回退、字段/固定值/输出整理和离线表单预览；发布前人工核对。
- 画布：动态文字/数字/整数/布尔/枚举、条件与高级字段，具名图片/音频/视频精确槽位，素材上传、保存草稿、服务端运行校验与不兼容切换确认；按契约决定提示词和时长。
- Task / Asset：先保存私有结果清单，再按稳定序号恢复归档；主输出留在当前节点，额外结果独立节点，混合类型新身份与空白草稿；取消和 CAS 冲突保留历史，fencing 拒绝旧 Worker。
- Usage：预估与供应商原始 usage 分开，未知时长和 null 保持未知；提交前阻断释放未提交费用预留。
- V65 / jOOQ / OpenAPI / TS、规格、术语、ADR、研究与操作说明同步；既有数据没有清空。

## 实际运行的检查

后端工作目录为 `backend`，最终定向命令：

```sh
./mvnw -Dtest=RunningHubDefinitionTest,RunningHubClientTest,RunningHubImportServiceTest,ArtifactContentValidatorTest,MediaCapabilityConfigurationTest,MediaExecutionSchedulerTest -Dit.test=RunningHubPostgresIT,TaskArtifactSelectionPostgresIT,DirectMediaGenerationPlacementPostgresIT,AudioMediaPostgresIT verify
```

最终结果：28 项单元测试和 11 项 PostgreSQL 集成测试通过，0 失败 / 0 错误；后端编译与 `verify` 成功。

单元检查范围：

| 文件 | 例数 | 行为 |
| --- | ---: | --- |
| RunningHubDefinitionTest | 7 | 不完整草稿/执行默认值、范围、未知字段、项目版本 UUID、条件、数字枚举/条件的浏览器 JSON 规范化、映射与输出约束 |
| RunningHubClientTest | 7 | 两类提交路径、Bearer、布尔选项、拒绝与 UNKNOWN、503/重定向不重提、原任务查询、双层 JSON、上传格式 |
| RunningHubImportServiceTest | 3 | 工作流连接不开放、不猜素材语义、LIST 保留标签/类型、删除远程素材默认值、拒绝凭据 |
| ArtifactContentValidatorTest | 4 | 包含无提示词的图片/视频/音频结果，以及既有内容校验 |
| MediaCapabilityConfigurationTest | 6 | 既有能力默认值、输入限制和配置规则回归 |
| MediaExecutionSchedulerTest | 1 | 既有媒体后台调度回归 |

PostgreSQL 17.11 Testcontainers 检查范围：

| 文件 | 例数 | 行为 |
| --- | ---: | --- |
| RunningHubPostgresIT | 8 | 多输出中途下载失败后新 Worker 只恢复归档、已归档项不重下、能力修改后原任务继续核对、较新草稿不覆盖；提交 UNKNOWN；提交前配置变化阻断；取消晚到批量历史；混合音频新身份与空白草稿；fencing 与清单 Schema 约束；管理员/CSRF 预览；具名视频精确引用与跨项目拒绝、未知时长价格、素材消失后阻断与释放预留 |
| TaskArtifactSelectionPostgresIT | 1 | 既有单结果选择 CAS 与取消晚到历史 |
| DirectMediaGenerationPlacementPostgresIT | 1 | 既有直接生成、节点版本与额外输出放置 |
| AudioMediaPostgresIT | 1 | 既有音频上传/重新生成及视频混合参考 |

前端工作目录为 `frontend`：

```sh
pnpm test src/features/canvas/RunningHubForm.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/settings/MediaSettingsPage.test.tsx
pnpm api:generate
pnpm build
pnpm exec eslint src/features/canvas/RunningHubForm.tsx src/features/canvas/RunningHubForm.test.tsx src/features/canvas/MediaDraftEditor.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/settings/RunningHubDefinitionEditor.tsx src/features/settings/MediaSettingsPage.tsx src/features/settings/MediaSettingsPage.test.tsx src/features/settings/mediaAdapterCatalog.ts src/shared/api/client.ts --max-warnings=0
```

前端 3 个定向文件共 51 例通过，覆盖 typed SELECT、false 默认值、条件字段、精确视频槽位、无通用提示词/时长限制、1–60 秒来源、发布核对与既有草稿/设置交互。API 类型重新生成成功；TypeScript 与 Vite 生产构建通过；上述修改文件的 ESLint 通过。Vite 提示部分产物超过 500 kB，构建成功，本次没有做拆包或性能验收。

jOOQ 在隔离空库 PostgreSQL 17.11 执行 Flyway 至 V65 后，运行：

```sh
./mvnw -Pjooq-codegen -Djooq.codegen.jdbcUrl=jdbc:postgresql://localhost:55434/agenvas generate-sources
```

生成成功；生成文件未手工修改。普通后端构建不连接此生成数据库。根目录 `git diff --check` 通过。

## 未验证与当前限制

- 初始测试未使用真实 Key。随后已完成两份示例的上传、工作流 / AI 应用生成、归档与费用核对，见补充记录；24 小时结果有效期仍未实测。本地假服务测试和真实 Provider 检查分别记录。
- 没有运行前远程摘要核验；本地能力版本与来源 hash 不能保证云端实现不可变。外部提交前沿用当前配置/草稿核对，已外部受理任务沿用原绑定。
- 本轮没有运行全量测试、浏览器端到端、并发压力或旧部署升级演练。
- 文本结果、旧式生成、Webhook、远程取消/退款、密码保护目标、目录链接/cURL 解析与视频输入画布连线未实施。
- 结果 URL 超过上游有效期时不能承诺恢复；没有验证重新查询能否刷新过期 URL，归档失败不会重做生成。

合并 main 后 RunningHub 使用 V66（V65 保留给已合入的 AutoDL），升级需同时发布后端、V66 与前端：旧客户端不识别新增平台/输入角色。迁移只增加允许值和私有清单列，保留既有资源、草稿、任务与加密凭据。上述初始验证时尚未提交；main 合并补证见 [合并验证](runninghub-main-merge-2026-10-01.md)。未发布部署。
