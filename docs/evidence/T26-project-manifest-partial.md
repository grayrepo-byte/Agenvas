# T26 脱敏项目清单：阶段性证据

2026-09-23。`GET /api/v1/projects/{projectId}/export-manifest` 在身份和项目归属校验后，从同一 PostgreSQL REPEATABLE READ 快照返回项目身份、全部 Artifact 不可变版本与 READY Asset 元数据，并以附件形式下载 JSON。前端“无声顺序导出”面板提供同源会话保护的下载入口。API 合约在 `contracts/openapi.yaml`，TS 类型由该合约重新生成。

内容采用逐类型字段白名单：文本/角色/场景/镜头保留创作字段，图片/视频仅保留资产 ID、提示词、Provider 配置版本、工作流版本和视频关键帧版本。任意媒体 `parameters`、`sourceTaskId`、Provider requestId、原始对象路径、缩略图路径、Owner ID、会话、Key 与可复用签名 URL 均不进入清单；素材字节仍须经独立鉴权端点读取。用户主动写入提示词或正文的文本会作为创作内容导出，不能把此清单当作任意用户文本的秘密扫描器。

`ProjectExportManifestPostgresIT` 用真实 PostgreSQL、HTTP 权限边界和有效图片资产验证两版历史、Provider 配置/工作流版本元数据、资产清单、附件响应、匿名/越权拒绝，以及合法媒体 `parameters` 中的私有标记不会泄露。前端 `MediaExportPanel.test.tsx` 验证下载入口。当前媒体产物尚未钉住可核实的具体模型版本，也尚未从真实三镜头 Provider 项目下载最终媒体。

2026-09-24 隐私补验：同一 PostgreSQL 测试现实际保存 AES-GCM 加密的 LLM Provider Key、模型 ID 和端点，并在项目清单 HTTP 请求中带入指定会话 ID 与私有会话属性；响应逐项确认不含这些值，也不含媒体参数中的签名 URL、内部 requestId、对象路径和 ownerId。Mock 会话用于验证响应不复制会话上下文，未宣称生产浏览器 Cookie 流程已验收。首版断言曾把 MockHttpSession 默认 ID `1` 当作秘密误报；改用明确的长会话标记后，定向测试通过。用户自己放入创作正文的文本仍会按规格导出，不做任意秘密扫描。T26 的该项隐私边界已勾选，其余项仍未完成。

本轮最终源码的 `./mvnw --batch-mode --no-transfer-progress -q verify` 返回 0；`ProjectExportManifestPostgresIT` 的 Failsafe 报告为 1 test、0 failures、0 errors，当前 Surefire/Failsafe 共 111 个测试套件报告无失败/错误，`git diff --check` 无输出。首次全量运行仍在无关的 `ArtifactPostgresIT` 建连阶段遇到 Docker Desktop/PostgreSQL EOF，该测试单独重跑通过。随后只在 `backend/src/test/resources/application.properties` 增加 Hikari 启动连接有限重试窗口，生产配置不变；完整重跑通过。一次通过不证明间歇建连问题已彻底消失。未运行前端测试或真实 Provider。

用量阶段性实现：V25 新增 append-only `usage_ledger`，V26 用项目复合外键把 Run/Task 锁在同一项目。LLM 回合在 REQUESTED 检查点预留一次请求、完整响应持久化时结算一次；仅 Provider 报告的输入/输出 Token 才记入数量，Spring AI `EmptyUsage` 的占位零值映射为 null。响应前崩溃仍可能重复发起外部 LLM 调用，旧预留保持待核实，不声称绝对准确计费。批准媒体计划时，每个 Task 与审批同事务写入唯一 `media:{taskId}:reserve` 预留；媒体结果通过 fenced Task 状态提交时，同事务写入唯一 `media:{taskId}:settle` 结算。项目 MP4 导出也在创建 Task 时写入 `export:{taskId}:reserve`，仅在导出 Task 成功时写入 `export:{taskId}:settle`；排队/运行中取消与工具失败不写成功结算。记录的 `costStatus=UNKNOWN`，`estimatedCost`/`actualCost` 均为 null，不把 Mock、未定价外部推理或本地 FFmpeg 说成免费。每次新写入产生 `usage.changed` 项目事件；重复相同 operation key 不重复写入或发事件，不同 payload 冲突。`GET /api/v1/projects/{projectId}/usage` 只返回所属项目的记录，金额为十进制字符串或 null；前端区分预留与结算，显示“费用未知”，不合计两阶段数量。`LlmTurnPostgresIT` 验证已报告 Token 与重放不重复结算，`MockStoryboardPostgresIT` 验证 Mock 回合不编造 Token；`ExecutionPlanPostgresIT` 在真实 PostgreSQL 覆盖并发审批只预留一次、图片完成后结算一次、重复结算不增行、视频时长钉住、匿名/越权拒绝及跨项目外键阻断；`MediaExportPostgresIT` 覆盖导出预留/结算幂等、取消/失败不伪装成功及项目事件序号。

后续补充：项目 MP4 导出在排队时取消、运行中确认取消、运行失败，以及进程中断后的取消租约恢复时，均在终态同一事务写入唯一 `export:{taskId}:release`。重复取消、重复释放或重复恢复不产生第二条账本或项目事件；已成功结算的导出不能释放。`MediaExportPostgresIT` 使用真实 PostgreSQL 与本地 FFmpeg 覆盖这些路径。`RELEASE` 是对预留输出数量的关闭，不把本地计算成本宣称为零；金额仍是未知。

媒体预留释放补充：审批后仍在 `PENDING/READY` 的图片/视频 Task 被 Run 取消时，由同一事务只为这些已确认未提交的任务写入唯一 `media:{taskId}:release`。已认领但尚无提交检查点的 `RUNNING` Task，在确定的本地预检失败或取消租约过期恢复后释放；尚未确认终态的运行任务不提前释放。`SUBMITTING`、已保存外部请求 ID、`UNKNOWN` 和已结算任务均不走这条释放路径。`ExecutionPlanPostgresIT` 用真实 PostgreSQL 验证提交前失败、排队取消、过期租约恢复和重复取消；`ComfyUiImagePostgresIT` 验证已受理但阻断的请求在取消 Run 后仍保留预留。

限制：当前核算模型请求次数和 Provider 实际返回的 Token、审批后的图片/视频数量、钉住的视频秒数及项目 MP4 导出次数。已进入 `SUBMITTING` 后 Provider 明确拒绝的费用语义、LLM 确定性失败释放、真实 Provider 价格/实际账单与配置币种还未接入；UNKNOWN 提交的预留仍保持待核对，不自动退款。具体模型版本和真实三镜头下载也未验证，因此 T26 与 M5 继续未勾选。

2026-09-24 用量显示补验：`MediaExportPanel.test.tsx` 现向 UNKNOWN 记录注入与状态矛盾的 `estimatedCost=0.00 USD`，仍只显示“费用未知”，不呈现 `0.00 USD`。后端 `ExecutionPlanPostgresIT` 验证 HTTP 未知金额为 null、20 路审批只保留单组预留和同一媒体任务重复结算只留一行；`MediaExportPostgresIT` 验证重复导出结算不增账本或事件；`LlmTurnPostgresIT` 验证已保存模型回合重放不再调用模型或追加账本。前端全套 Vitest 12 文件/42 测试、TypeScript typecheck、ESLint、Vite build，以及三组后端定向 PostgreSQL 测试均返回 0。由此勾选“未知费用不显示零；同一任务重复通知不重复结算”。这不解决已预留后发生不确定外部调用的费用释放；没有 Provider 明确证明未提交前，仍必须保留预留。

契约兼容说明：`UsageEntry.quantity` 新增必填的 `llmRequestCount`、可空的 `inputTokens`/`outputTokens`，是开发阶段的破坏性响应结构升级；前端类型已从 OpenAPI 重新生成。V27 Flyway 迁移为已有媒体/导出账本行回填新字段；旧版客户端未适配新数量维度，不能假定其兼容。
