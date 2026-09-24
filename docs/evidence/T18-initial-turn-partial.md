# T18 首个持久化回合：阶段性证据

后续 Mock 视频纵向链路已推进，见 `docs/evidence/T23-mock-video-partial.md`；本文件以下关于“尚无视频”的记录仅反映此前 T18 检查时点。
后续已补运行历史只读列表与卡片入口，见 `docs/evidence/T18-run-history-partial.md`；以下“运行历史列表尚未实现”也仅反映此前检查时点。

- 行为：创建新 Run 的同一事务内创建唯一 `agent-turn-0`、`READY` 的 `AGENT_TURN` Task，并依次提交 Run 与 Task 项目事件。相同 Idempotency-Key 的精确重放不创建第二个 Run 或首任务；取消 Run 会关闭尚未认领的首任务。
- 执行域：`TaskService.claimDue` 与 `TaskWorker` 只认领非 Agent 任务；新增 `claimAgentTurns` 专门认领持久化模型回合，使用同一短事务 `SKIP LOCKED`、租约和 fencing epoch。媒体 Worker 不会意外把模型回合当作素材任务处理。
- 会话恢复：`LlmProtocolCodec` 从版本化请求/响应 JSON 恢复 Spring AI 消息、助手工具调用 ID 与 JSON 可表示的供应商协议元数据；`LlmConversationService` 只在响应和全部工具结果已提交时，从数据库重建下一回合消息。缺失结果、越权 Run、未知 Schema 均拒绝继续。工具执行仅接受选中的第一个模型候选中的调用，其他候选不能触发副作用。
- 首回合上下文：Run 创建时还固定项目名称/画幅、Agent 名称/指令；`InitialModelContextService` 从这些快照及精确绑定的历史 ArtifactVersion 组装受限消息。Agent 卡片或素材的后续编辑不会改变该 Run 首回合输入；输入过大明确失败，不静默截断。
- 副作用 fencing：`executeLeased` 在工具业务事务内锁定并验证 `AGENT_TURN` Task 的 owner、epoch、未过期租约及取消标志。旧 Worker 被接管后不能再通过此运行时入口触发工具副作用。
- 后台执行：`AgentTurnWorker` 由持久 Task 表认领回合，租约由独立有界心跳线程续约，模型调用在独立有界执行器中最多等待 90 秒且不持有数据库事务；完整响应入账后串行执行选中候选的工具。`AgentTurnCommitService` 将当前任务成功、下一依赖回合或 Run 终态同事务提交，12 回合边界阻止无限模型循环。无可用工具调用模型时任务失败并将 Run 置 `BLOCKED`，不伪装成功。
- 审批崩溃窗口：提案工具可能已使 Run 进入 `WAITING_APPROVAL`，但当前 Task 仍为 `RUNNING`。认领条件只允许此类过期 Task 被接管收尾，不允许在等待审批时启动新的 READY 回合；集成测试模拟租约过期、接管并完成账本收尾。非 Agent Worker 仅在 `RUNNING`/`WAITING_TASKS` 认领，不执行 QUEUED 或终态 Run 的任务。
- 调度：生产配置默认启用 `AgentTurnScheduler`；测试配置关闭自动定时器，专门的定时器 PostgreSQL 测试覆盖没有持续 HTTP 连接时后台推进 Run。默认安装使用明确标记的确定性 Mock ChatGateway；`configured` 模式若没有真实 ChatModel，画布 preflight 会阻止启动，直接调用创建 API 仍会进入 `BLOCKED`。
- Mock 三镜头：默认演示网关从已保存的模型消息与工具结果中恢复下一步，不依赖单例可变回合状态；沿受控工具账本创建演示说明、场景、三个有精确引用的镜头，再提出三张图片的待审批计划。批准前不创建图片 Task；批准后使用同一 Mock 图片 Worker 归档三张实际 PNG。图片完成后仍须用户逐镜头选定精确关键帧，才会恢复 Agent 并完成当前阶段 Run。所有文本和媒体均标明非真实 AI 生成。`MockStoryboardPostgresIT` 使用真实 PostgreSQL 验证这一阶段性纵向路径；还没有视频、导出或浏览器端到端验证。
- 审批后唤醒：批准在同一事务内创建依赖规划回合和全部媒体 Task 的下一模型回合，并推进 Run 游标至 `WAITING_TASKS`；拒绝创建不依赖媒体的继续回合。规划 Task 即使在用户决定后才因崩溃重领，也只重放已保存的工具结果并完成原 Task。视频计划的前置图片阶段即使全部成功，依赖回合仍为 `PENDING`，直到每个结果都有来自该 Task 的人工精确关键帧选择；选择与 Task 提升同事务提交。恢复上下文同时核对并附带已保存的选择，不从模型文本推断批准。假模型 + PostgreSQL 测试覆盖批准、Mock 图片完成、关键帧门禁、拒绝、旧回合收尾与最终 Run 完成；该测试手动触发真实 Mock 图片 Worker，另有 `MockImageSchedulerPostgresIT` 验证后台定时器在无 HTTP 客户端时自动推进图片任务且不误领视频。
- Mock 图片归档：`MockImageWorker` 使用已提交的 requestKey 调用同步 Mock Gateway，`COMPLETED` 结果生成带显眼 DEMO 字样的 PNG，先经同一 Asset 服务归档，再由 fenced Task 成功路径创建 IMAGE ArtifactVersion。`FAILURE` 走 `REJECTED`，不确定返回保留 `SUBMITTING` 并由恢复扫描转 `UNKNOWN`；Provider 配置版本失效在提交 checkpoint 之前失败，不创建 attempt。测试核对 requestKey、实际 PNG 字节、演示标记及下一回合唤醒。未接入真实媒体 Provider。
- 画布输出：新 IMAGE Artifact 完成时在同一业务事务中放到发起 Agent 输出组右侧的首个空位，不移动既有或锁定卡片；取消后晚到的历史结果不自动放到当前画布。画布和关键帧选择面板显示私有缩略图及演示素材标记，原图只由显式链接打开。`TaskNewOutputPostgresIT` 与前端组件测试覆盖画布卡片；关键帧面板缩略图与完整浏览器黄金路径尚未单独验证。
- 卡片入口：Agent 卡片先读取只读 Run preflight，展示当前 Agent 指令、精确绑定版本及其类型/标题、模型适配器/模型 ID、工具能力和服务端调用限额，再由用户确认创建持久 Run；未配置或不支持工具调用的模型不能从卡片启动。创建请求带预览时的 Agent 版本及幂等键，同一未确认指令的网络重试沿用该键；版本冲突要求重新预览并换键。当前状态来自项目快照，停止当前所属 Run 已接入。`AgentRunPostgresIT` 和前端组件测试覆盖预览、鉴权、版本冲突、重试与停止。运行历史列表尚未实现，记录按钮保持禁用。
- 合约兼容：OpenAPI 在 0.1.0 开发期加法增加 `GET /runs/preflight` 和创建 Run 可选的 `expectedAgentVersion`；服务端旧调用路径仍可省略该字段。前端生成类型已重建。

- 2026-09-24 运行前模型配置竞态补验：预览的 `policySnapshot` 正式声明配置来源/版本；Agent 卡片确认时一并提交 `expectedModelConfigSource` 和 `expectedModelConfigVersion`。服务端用同一份创建时策略快照比较，不匹配返回 409 `MODEL_CONFIG_CONFLICT`，不会创建 Run；前端清除旧确认并要求重新预览，网络失败重试仍沿用相同幂等键。真实 PostgreSQL `AgentRunPostgresIT` 覆盖错版本无 Run、预览返回身份和带正确身份的 20 路 HTTP 并发创建；前端 `ProjectWorkspacePage.test.tsx` 覆盖字段发送和冲突后换键。OpenAPI 与生成 TS 同步；新增字段为可选对照条件，旧 API 调用仍可省略，但 UI 总是提交。未新增迁移；仍未做真实模型配置切换期间的浏览器端到端测试。
- 2026-09-24 系统提示词版本补验：预览现展示 `systemPromptVersion`，卡片确认提交 `expectedSystemPromptVersion`；创建时版本不同返回 409 `SYSTEM_PROMPT_CONFLICT`，无 Run 或首轮任务。该值参与幂等请求指纹，网络失败仍可同键重放原请求，规则冲突则清除旧确认。OpenAPI/生成 TS 加法更新，旧 API 可省略此对照条件；没有数据库迁移。真实部署交错仍未做浏览器端到端演练。
- 本次检查：最终 Java 源码 `./mvnw --batch-mode --no-transfer-progress -Dit.test=AgentRunPostgresIT verify -q` 及完整 `./mvnw --batch-mode --no-transfer-progress verify -q` 均退出码 0。前端最终源码 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，15 个测试文件、48 个测试通过；`git diff --check` 退出码 0。真实模型和浏览器端配置切换竞态均未实测。
- 停止边界：已批准媒体 Task 确认失败或其提交恢复为 `UNKNOWN` 时，Run 与任务事件同事务转为 `BLOCKED`，未完成的后续 Agent 回合不被唤醒。媒体 Worker 的明确拒绝结果经过租约与 epoch 校验，将 `SUBMITTING` Task 置为 `FAILED`、相应 attempt 置为 `REJECTED`；过期 Worker 不能写回。成功归档同步把 attempt 置为 `ACCEPTED`。含糊的网络失败仍等待租约恢复为 `UNKNOWN`，不自动重新提交生成请求。
- 合约/迁移：Run preflight 是加法 HTTP 合约，Mock ChatGateway 只增加部署模式配置，没有新数据库迁移；Flyway V19 曾增加 Provider attempt 的 `REJECTED` 状态。数据库继续以 Run/Task/Provider attempt 行为真相。
- 实际检查：关键帧门禁改动后，定向的 `MockStoryboardPostgresIT`、`PlanResumeWorkerPostgresIT`、`ExecutionPlanPostgresIT` 与完整 `./mvnw verify -q` 通过（原 29 个单元测试、21 个 PostgreSQL Testcontainers 集成测试集合）。前端 `typecheck`、`lint`、14 个 Vitest 测试与 Vite 构建通过；构建有单包超过 500 kB 的警告。`git diff --check` 通过。此前 `docker compose config --quiet` 通过，本轮未重跑 Compose 新版本端到端；关键帧面板缩略图未做浏览器端到端验证。
- 未完成：Mock 视频和真实媒体 Provider 的自动 Worker 调度、真实 LLM 与 Provider 黄金路径、运行历史列表、审批等待期限、更多崩溃点的进程重启测试仍缺失；没有真实 Provider 协议恢复测试。首回合上下文尚未覆盖当前选中对象及项目创作说明。测试中的假模型和 Mock 字节不能证明真实生成。T18 与 MVP 均保持未完成。
