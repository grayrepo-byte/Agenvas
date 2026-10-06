# ADR 0028 与实施设计：Agent 媒体批次审批与结果续接

状态：接受（2026-10-02）。用户明确要求扩展后端，使 Agent 可以提出媒体生成，统一经用户批准后调用真实第三方，生成完成、失败或过期后继续原对话；后端首阶段不改前端、不接 Webhook，随后用户确认接入前端，见 [ADR 0029](agent-conversation-stream-design.md)。本文覆盖 [ADR 0013](adr/0013-contract-to-direct-generation.md) 中「Agent 无媒体能力」和「只有用户直连媒体生成」的边界；角色、场景、镜头、执行计划、两阶段审批、关键帧、镜头局部重做和媒体导出的收缩继续有效。

## 决策与边界

Agent 通过安全工具提出一批 1–6 项独立的图片、视频或音频输出。提案只创建媒体产物、节点、草稿与审批，不受理第三方生成任务。一次用户批准只覆盖当前固定批次；模型不能批准、不能宣称已获批准，也不能用旧对话或先前审批授权新的批次。

批准后复用已有持久 Task、能力目录与固定 Provider 适配器，不增加 Node 服务端、消息中间件或通用工作流引擎。后台查询供应商原任务、下载并归档结果，然后向原 Run 追加结果并创建下一模型回合。模型没有等待结果的查询循环；本次没有公开 Webhook 接收接口，也不要求本地部署具备公网回调地址。

`WAITING_TASKS` 同时表示审批等待和媒体等待，保持现有 Run 状态集合。审批具有独立状态和版本；后端首阶段使用鉴权 API 批准或拒绝，随后在 Agent 对话内接入整批审批卡并按权威 OpenAPI 重新生成前端类型。

## 工具输入与作用域

`list_media_capabilities` 返回当前用户可使用的安全能力目录、输入契约及已发布的 RunningHub / ComfyUI 输入字段摘要，不暴露工作流图、固定图文本、endpoint、Key、上传/下载地址或供应商原始响应。模型只能选择目录内的能力；权限、项目和 Run 身份由可信服务端上下文注入。

`propose_media_generation` 输入为：

```text
outputs: 1–6 项
  kind: IMAGE | VIDEO | AUDIO
  title: 输出标题
  prompt: 生成提示词
  capabilityId?: 已配置能力 ID；缺省使用对应类型默认能力
  parameters?: 能力支持的结构化参数
  durationSeconds?: 视频时长；音频使用所选能力支持的 parameters
  videoInputMode?: TEXT | START_END | GENERAL_REFERENCE；仅视频
  mediaInputs?: [{ versionId: 同项目精确内容版本 ID, role: 已支持的输入角色 }]
```

每项输出生成一个独立 Task，不允许借参数扩展为未经审批的多个收费输出。引用必须属于本 Run 可见的绑定或已产生内容，同时验证同项目归属、类型与能力限制；模型不能通过任意对象 ID 扩大输入范围。工具不接受外部 URL、任意工作流 JSON、脚本、表达式或请求模板。

提案参数校验的 HTTP 400 与 422 均映射为 `TOOL_ARGUMENT_INVALID`，进入现有最多两次的模型参数修正流程；失败回合的产物、草稿与工具副作用同事务回滚。权限拒绝与版本冲突保留原错误，不转换为参数修正；修正成功仍须重新形成固定批次并等待用户批准。

2026-10-03 引用交互修订：Agent 提案中的媒体引用通过与用户拖线相同的持久 `MEDIA_INPUT` 连线连接来源与输出节点，保留精确版本、顺序和首尾帧等角色。优先复用正在展示该版本的来源 CanvasItem；没有匹配卡片时放置该精确版本的独立来源卡片，不修改已有节点的选用版本或资源默认版本。提案引用只保留连线来源，断线或编辑栏移除引用沿用既有 CAS 与来源删除规则；删除后旧审批失效，不能提交未经批准的新输入。卡片下方不再展示引用缩略图，工作引用仅在媒体编辑栏展示。来源节点、连线、草稿与审批同事务提交；连线不触发生成。

提案固定每项输出的 Artifact、CanvasItem、草稿版本及安全预检摘要，摘要含提示词、有效参数、能力、素材范围和估算费用或费用未知。批准前不进行第三方生成调用；只创建草稿也不代表已有真实媒体字节。

## HTTP 合约

所有路径带 `/api/v1` 前缀，并复用已有登录、项目授权与 CSRF 边界。

| 方法与路径 | 请求 | 响应 |
|---|---|---|
| GET `/projects/{projectId}/runs/{runId}/media-approvals` | 无 | `AgentMediaApproval[]`，无批次时为空 |
| GET `/projects/{projectId}/runs/{runId}/media-approvals/{approvalId}` | 无 | `AgentMediaApproval` |
| POST `/projects/{projectId}/runs/{runId}/media-approvals/{approvalId}/decision` | 必需 `Idempotency-Key`（1–200 字符）；正文 `{expectedVersion, decision: APPROVE | REJECT}` | HTTP 200 + 决策后的 `AgentMediaApproval`；批准后异步执行 |

审批响应字段：`id / projectId / runId / operationId / status / version / outputs / taskIds / result / createdAt / expiresAt / executionDeadline`。每项 `outputs` 含 `kind / title / artifactId / canvasItemId / draftVersion / preview`；`preview` 是固定安全摘要。批准前 `taskIds` 为空，未产生最终结果时 `result` 为空，批准前 `executionDeadline` 为空。响应不包含原始模型消息、私有推理、连接地址或密钥。

决策必须核对审批版本与状态。相同幂等键、相同请求返回同一审批的当前状态，不重复受理；相同键、不同正文返回 HTTP 409。尚为 `PENDING` 的过期审批转为 `EXPIRED` 并返回 HTTP 200，提交终态通知；其他非 `PENDING` 状态或并发版本变化返回冲突。批准时再次核验 Run 未取消、固定草稿/输入/能力/有效参数/价格未变化；变化不提交 Provider，不用模型猜测修复。整个批次统一批准或拒绝，不提供部分批准、审批正文修改生成参数或可重复使用的长期授权。

权威结构见 [OpenAPI](../contracts/openapi.yaml)。新增端点为增量接口；Run 状态集合和现有直连媒体路径保留。Flyway 以新迁移增加审批账本和约束，不修改已发布迁移；升级前仍按部署流程备份，审批链路不删除既有创作数据。构建不连接数据库生成 jOOQ，schema 改动后重新生成并提交源码。

## 状态、最终结果与续接

审批状态为 `PENDING / APPROVED / SUCCEEDED / FAILED / REJECTED / EXPIRED / CANCELED`。`PENDING` 自创建后 24 小时到期；`APPROVED` 自批准后 24 小时达到执行截止。成功必须包括归档后的真实媒体结果；仅收到第三方任务 ID 或生成完成地址不等于成功。

最终结果保存 `schemaVersion=1`、审批终态 `status` 和 `tasks` 数组。每项包含 `taskId / kind / status`，按实际结果包含 `errorCode / artifactId / artifactVersionId / selected / additionalResults`。拒绝和过期等无 Task 的结果允许空数组并给出稳定 `errorCode`；一个批次部分成功时保留成功输出及每个任务的失败信息。

`UNKNOWN` 任务结束本次审批等待并以审批 `FAILED` 通知 Agent，原 Task 仍保持 `UNKNOWN` 和原供应商提交记录。系统不会自动重提；用户显式创建新尝试仍是独立操作，费用风险与既有规则一致。到期或取消只停止本系统后续编排，不能宣称第三方已停止或退款。晚到结果继续按现有规则归档，不覆盖用户选择。

提案工具的执行账本记录提案已完成，保持原 `tool_call_id`；恢复消息从持久账本与审批终态组装，完整结果进入 `mediaApproval`。审批最终结果、Run 状态、下一回合任务与项目事件原子提交，重复通知或重启恢复不得重复创建模型回合。仍活动的 Run 收到成功、失败、拒绝或过期结果后继续，让 Agent 明确交代结果或完成剩余文字工作。已取消或终结的 Run 只保留最终结果审计，不恢复模型调用。

任务状态变化通知用于及时续接，审批账本的内部 `notification_pending` 标记与后台扫描用于重启及遗漏恢复；数据库仍是状态真相。后端查询只核对已提交的原请求，不等于生成重试；下载或归档失败只重试归档，不重做生成。网络调用不持有数据库事务，等待不占用模型执行线程，旧 Worker 仍须通过租约和 fencing epoch 拦截。

新 Run 继续使用策略 schema v2，并固定系统提示词版本 3。历史提示词版本 1/2 的检查点保留原规则，不在恢复时静默升级工具授权。

## 实施与验收范围

本文记录已确认的产品决定与接口设计，不单独证明功能验收通过。实施与实际测试结果以 [开发清单](DEVELOPMENT-CHECKLIST.md) 本次条目和交付报告为准。

必需专项覆盖：批准前没有 Provider 提交；批次统一授权；审批幂等与异参冲突；CAS 与跨项目/越权拒绝；完整成功、部分失败、UNKNOWN、拒绝、审批过期、执行过期；取消不续接；晚到结果不覆盖；重复结果只续接一次；重启扫描恢复；固定输入与能力变化不产生未批准提交。数据库约束、事务和恢复使用真实 PostgreSQL 验证，供应商协议可用明确标记的假 HTTP 验证。

真实第三方与真实工具模型调用须单列。Mock、假 HTTP 或合成素材通过只能证明应用路径和固定协议行为，不能证明本次已完成真实供应商调用，也不能替代前端专项验收；前端实施与验收见 [ADR 0029](agent-conversation-stream-design.md)。未经专项测试的分支保持未验收，不运行全量测试作为本次默认门禁。
