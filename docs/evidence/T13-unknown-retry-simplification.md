# UNKNOWN 重试简化（移除原请求核对）

日期：2026-09-26。

## 行为

- UNKNOWN 面板从“请求结果待核实 → 查看提交账本并处理重试 → 风险勾选 → 明确风险后创建新尝试”简化为“结果未知 + 一个重试按钮”。
- `POST /projects/{id}/tasks/{taskId}/new-attempt` 请求体只剩 `expectedTaskVersion`；不再要求 `riskAcknowledgement`，`Idempotency-Key` 与版本校验保留。
- 移除 `POST .../reconcile`、`GET .../attempts`、`UnknownTaskReconciler`、`ComfyUiUnknownTaskReconciler`、`TaskService.reconciliationCandidate` / `resumeVerifiedOriginal` / `ReconciliationCandidate`、`TaskRepository.recoverUnknownSubmission`、`ComfyUiClient.originalPromptExists`。
- 移除 `ManualUnknownRetryService` 对 ComfyUI 适配器的单槽硬拒绝。ComfyUI 单槽门禁（`JdbcTaskRepository.claimDueComfy`）本就把“已被 `task_manual_replacement` 替代的 UNKNOWN”排除在占用之外，因此重试后原任务立即让出名额；保留该硬拒绝会在删掉核对后造成死锁。
- 系统层行为不变：崩溃恢复不自动重提；UNKNOWN 仍保留用量预留与提交记录；新尝试仍建独立用量预留、仍重连未执行的下游依赖、同键重放仍返回原新任务。

## 契约与前端

`contracts/openapi.yaml` 删除上述两条路径与 `ProviderAttempt` / `ReconciliationResult` schema，`ManualUnknownAttemptRequest` 只保留 `expectedTaskVersion`；`frontend/src/shared/api/schema.ts` 由 `pnpm api:generate` 重新生成。前端新增 `UnknownTaskRetryPanel.tsx` 替换 `UnknownTaskAttemptPanel.tsx`，`client.ts` 删除 `listProviderAttempts` 与 `reconcileUnknownTask`。

## 已运行验证

- 前端：定向 9 个文件、71 项通过（含重写后的 `UnknownTaskRetryPanel`、`AgentRunConversation`、`MediaDraftEditor`、`MediaCanvasCard`、`CallLogsPage`）；`pnpm typecheck`、`pnpm lint`、`pnpm build` 通过。
- 后端单测：`ComfyUiClientTest` 通过。
- 后端集成（PostgreSQL 17.11 Testcontainers）：`ManualUnknownRetryPostgresIT`、`ComfyUiImagePostgresIT`、`TaskRecoveryPostgresIT` 各 1 项通过。
- 单槽让出：在 `ManualUnknownRetryPostgresIT` 末尾把能力切到 `COMFY_IMAGE_V1`，断言已被重试取代的原 UNKNOWN 任务让出名额、替代任务可被 `claimDueBoundMedia` 认领。把 `occupiedMediaClause` 临时回退成“UNKNOWN 一律占用”时该断言失败（`Expected size: 1 but was: 0`），确认它能捕获这个缺口。
- 受同一 SQL 片段影响的测试组：`ComfyUiImagePostgresIT`、`ComfyUiVideoPostgresIT`、`MockImageSchedulerPostgresIT`、`MediaCapabilityPostgresIT`、`TaskLeasePostgresIT`、`TaskQueueMetricsPostgresIT` 全部通过。
- 未运行全量测试，未做浏览器验收，未进行真实 ComfyUI 调用。

## 过程中修掉的缺口

`claimDueBoundMedia` 的 ComfyUI 单槽条件原本用 `occupiedMediaClause`，而后者把 `UNKNOWN` 一律算作占用、没有 `task_manual_replacement` 例外（`claimDueComfy` 有）。原设计下 ComfyUI 被硬拒绝、永远不会有替代任务，所以这个缺口不会暴露；硬拒绝一删，替代任务就会被原 UNKNOWN 永久阻塞。现已统一：`occupiedMediaClause` 与 `claimDueComfy` 都排除已被替代的 UNKNOWN。

## 未验证的限制

- 真实 ComfyUI 下“重试后原任务让出单槽、替代任务被派发”的完整链路未联调；目前由 `claimDueBoundMedia` / `claimDueComfy` 的 SQL 语义与 PostgreSQL 集成测试覆盖。
- 重复生成的成本由用户承担，界面不再提示；这是产品决策，不是遗漏。
- 核对能力实际只覆盖 ComfyUI（云端适配器不保存候选 ID），因此移除它的实质损失仅限本地重复生成，成本≈电费。
