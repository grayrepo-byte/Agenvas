# T01 补验：数据访问迁移到 jOOQ

日期：2026-09-26。决策与取舍见 [ADR 0012](../adr/0012-jooq-persistence.md)。

## 范围

三件事：

1. **生产数据访问从 Spring `JdbcClient` 换成 jOOQ。** 24 个类（仓储、Reader 及 `ActiveRunMetrics`、`TaskQueueMetrics`、`SystemDiagnosticsService`、`ComfyUiClientRegistry`、`LegacyMediaImportService`）改写为 `Jooq*` 实现，229 处 `sql(...)` 调用点全部消失。
2. **状态魔法值收敛为枚举或常量。** 新增 `ToolResultStatus`、`ResumeDecision`、`IdempotencyState`（共享）、`TaskOrigin`、`MediaPlatform`；`asset.status` 收敛为仓库内常量；`LegacyMediaImportService` 的 task/plan 状态、kind、stage 字面量改用既有枚举。
3. **移除 MyBatis-Plus starter。** 迁移前 `grep -rn "^import.*baomidou\|^import.*mybatis" src/` 命中 0 行，属纯冗余依赖。

jOOQ 生成源码（101 个文件，包 `dev.agenvas.db`）提交在 `backend/src/jooq/java`，构建期不连数据库。

## 命令与结果

| 命令 | 结果 |
|---|---|
| `./mvnw -o test-compile` | 通过 |
| `./mvnw -o verify -DskipITs` | Surefire 112 项，0 失败 |
| `./mvnw -o verify` | Failsafe 80 项，0 失败（见下「既有失败」） |
| `./mvnw -Pjooq-codegen generate-sources` 连续两次 | 生成结果字节一致（可加 CI 漂移门禁） |
| `grep -rn "JdbcClient\|JdbcTemplate\|NamedParameterJdbcTemplate" backend/src/main/java` | 0 命中 |
| `grep -rn "\.sql(" backend/src/main/java` | 0 命中 |

覆盖各批次的定向集成测试：`CallLogPostgresIT`（7 项）、`ToolExecutionPostgresIT`、`PlanResumeWorkerPostgresIT`、`ExecutionPlanPostgresIT`、`TaskRecoveryPostgresIT`（含 `/settings/diagnostics` 聚合查询）、`ReadinessPostgresIT`（健康组 `db`）、`AgentPostgresIT`、`CanvasPostgresIT`、`IdentityPostgresIT`、`LlmProviderConfigPostgresIT`、`LlmDiagnosticPostgresIT`，以及 artifact/asset/export/provider/run/task/plan/llm 共 30 个 IT。

### 真库探针（无项目测试覆盖的两条路径）

- **`claimDueExports` 的崩溃恢复分支**：临时库中构造「RUNNING 且租约已过期」「RUNNING 且租约有效」「READY 且到期」三个导出任务，调用真实 `JooqTaskRepository`。结果：过期租约任务被重新认领且 `lease_epoch` 递增，有效租约任务未被误抢，到期 READY 任务被认领。
- **`queueStatus`（从 SQL 文本改写为 DSL 行值比较）**：同一数据集下新实现返回 `waitingAhead=2`，与原 SQL 的 `count(*)` 完全一致。

### jOOQ 纯 SQL 绑定 java.time 的坑

jOOQ 3.19.37 默认把 `OffsetDateTime` 编码成字符串绑定，`dsl.resultQuery("... occurred_at < ?", offsetDateTime)` 在 PostgreSQL 上报 `operator does not exist: timestamp with time zone < character varying`。生成代码的类型化字段不受影响。修法是全局 `Settings.bindOffsetDateTimeType(true)`（`JooqSettingsConfiguration`）；`CallLogPostgresIT` 在移除 SQL 里的显式 `::timestamptz` 绕过写法后通过，证明该设置在实际 Spring 上下文中生效。

## 既有失败：诊断、证据与修复

本次迁移前 `./mvnw verify` 已有两项失败。两项都用 A/B 对比确认与迁移无关，并已修复。

### 1. `ProjectEventPostgresIT`：期望值停留在会话事件引入之前

A/B：把原始 `JdbcProjectEventRepository` 从 git 恢复后重跑，失败完全相同（`expected [2..15] but was [2..16]`）。

根因：Run 创建现在按序追加**三个**事件——`agent.run.changed`、`agent.conversation.changed`、`task.status.changed`（会话变更事件随 ADR 0010 的持久会话引入），测试的序列期望与 `snapshotSeq` 偏移仍按两个事件计算。诊断输出确认序列为 `2:agent.run.changed, 3:agent.conversation.changed, 4:task.status.changed, 5..16:test.changed`，`event_seq=16`。

修复：测试期望改为 `sequence(2, CONCURRENT_EVENTS + 4)`、`event_seq = CONCURRENT_EVENTS + 4`、`snapshotSeq = CONCURRENT_EVENTS + 5`，并显式断言第三个事件类型；`assertConcurrentSnapshotPairs` 的偏移 `+3` 改为 `+4`。`ProjectEventPostgresIT`、`ProjectEventStreamPostgresIT` 复跑通过。

### 2. `MediaExportPostgresIT`：30 分钟租约与测试窗口不匹配

A/B：在 HEAD 的干净 worktree 中构建并重跑，失败完全相同（`expected SUCCEEDED but was RUNNING`，108.6 秒）。

根因：提交 `a45aa88` 把租约默认值从 `TaskProperties` 的 30 秒改为 `application.yaml` 的 `PT30M`（为覆盖 GPT Image 单次 30–40 秒的同步调用，避免结果被判 UNKNOWN 丢弃）。该测试启动的真实子进程没有覆盖租约时长，而它的 95 秒恢复窗口建立在「租约很快过期」之上；诊断显示任务 `lease_epoch=1`（从未被重新认领）、`lease_until = 认领时刻 + 30 分钟`。

修复：测试给子进程显式传 `--agenvas.task.lease-duration=PT30S`（不改生产默认值）。修复后 51.4 秒通过——被击杀进程的在途导出由新进程按租约过期重新认领并完成。

## 未验证与限制

- **没有真实 Provider 调用**：全部证据来自 Mock 适配器、假 HTTP 与真实 PostgreSQL。与迁移前一致，不构成 Provider 已接通。
- **测试仍用 `JdbcClient`**：约 60 个集成测试继续用它写独立 SQL 断言（有意保留，理由见 ADR 0012「范围边界」）。因此「仓库中无 `JdbcClient`」不是验收标准。
- **生成代码漂移**只由 CI 的 backend job 兜底（对一次性 PostgreSQL 重跑 codegen 并要求无差异）；本地 `./mvnw verify` 不会发现。
- **未跑容器镜像构建与 Compose 演练**：本补验只覆盖 Maven 构建、单元测试与集成测试。
- **未在浏览器中验收前端**；本次没有改动 API 契约（controller 签名与 DTO 字段类型未变，`platform` 仍以字符串出参），`contracts/openapi.yaml` 无需重新生成。
