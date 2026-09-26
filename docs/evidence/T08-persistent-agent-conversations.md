# T08：持久 Agent 会话

日期：2026-09-26。产品决定见 [ADR 0010](../adr/0010-persistent-agent-conversations.md)。

## 行为与实现

一条会话归属于项目中的一个 Agent；每次发送在该会话中追加一条有序 Run。会话选择、消息记录与记忆存入 PostgreSQL，刷新后可以继续交流；“新建会话”开始空历史，既有会话仍可切回。Agent 长期指令和明确绑定不因新会话而删除。

每次受理 Run 都冻结前序公开上下文及精确产物版本。模型输入包含真实 User/Assistant 消息，排除模型私有元数据、原始工具参数和未提交回复；历史审批不成为新 Run 的授权。上下文有界保留首轮与近期交流，最多 20 条历史消息、32,000 Unicode 码点，原始记录不删。历史产物只继承同会话前序 Run 当前选用的非人工版本；用户修改仍受版本与权限检查保护。

卡片新增会话列表、新建入口和返回运行会话入口，支持分页消息、独立输入草稿和刷新恢复。切换会话不取消在途任务；每个 Run 的停止、计划审批及 UNKNOWN 处理继续位于所属消息。慢发送响应只更新原会话，不清空另一会话的输入。

## 合约与迁移

- 新增会话列表/创建、选择和会话消息分页 API；Run 与摘要增加 `conversationId` / `conversationTurn`。
- 预检增加会话身份、版本、轮次和继承范围；创建 Run 校验预检会话版本。预检在一致性读快照中拒绝已有活动 Run，避免漏看尚未完成的上一轮。
- `contracts/openapi.yaml` 为权威来源，TypeScript Schema 重新生成，前端客户端同步。
- V45 创建 `agent_conversation`、当前会话指针和 Run 会话外键/唯一轮次。每条旧 Run 迁移成独立会话，保留任务、审批、内容版本与活动状态。前后端须一起升级；迁移后的数据库不能直接配不写会话字段的旧后端。

## 实际检查

定向测试使用 PostgreSQL 17.11 Testcontainers、Mock LLM；没有运行全量测试或真实 Provider。

- `InitialModelContextServiceTest`：6 项通过，覆盖公开消息恢复、空会话、字段白名单、异常快照、有界 Unicode 及后续 12 轮请求容量。
- `ConversationMemoryPostgresIT`：通过，真实持久工具账本和公开回复进入下一轮冻结快照；新会话隔离、私有字段排除、源记录变化不改已固定模型请求，以及中文/表情截断。
- `ConversationArtifactInputsPostgresIT`：通过，同会话产物可续改，其他项目和人工版本不能隐式继承，人工并发修改不会被覆盖。
- `AgentConversationPostgresIT`、`AgentRunPostgresIT`：通过，会话 API 的项目/Agent/用户隔离、创建幂等重放、分页和刷新读取、活动槽位与过期预检拒绝，以及并发发送只产生一个会话轮次；原 Run 并发与取消行为保持有效。
- `AgentConversationUpgradePostgresIT`：通过，从 V44 真实数据升级 V45，旧 Run/产物/任务/审批逐行保留，旧会话不混并，复合外键拒绝跨 Agent 与项目，二次迁移无额外修改。
- `AgentChatCard`、`AgentRunConversation`、`ProjectWorkspacePage`、`projectEvents` 四个定向前端测试文件：43 项通过，包括刷新续聊、会话切换、首次创建失败/重试、慢发送、修改草稿后旧确认失效、历史分页及 SSE 刷新。
- 前端 ESLint、TypeScript 与 Next 静态生产构建通过。

以下为按对应模块复现定向检查的命令；本次执行中部分目标合并运行，测试数据修正后按失败类单独重跑：

```sh
./mvnw --batch-mode --no-transfer-progress -q -Dtest=InitialModelContextServiceTest test
./mvnw --batch-mode --no-transfer-progress -q -Dit.test=ConversationMemoryPostgresIT,ConversationArtifactInputsPostgresIT,AgentRunPostgresIT test-compile failsafe:integration-test failsafe:verify
./mvnw --batch-mode --no-transfer-progress -q -Dit.test=AgentConversationUpgradePostgresIT test-compile failsafe:integration-test failsafe:verify
./mvnw --batch-mode --no-transfer-progress -q -Dit.test=AgentConversationPostgresIT test-compile failsafe:integration-test failsafe:verify
pnpm test src/features/canvas/AgentChatCard.test.tsx src/features/canvas/AgentRunConversation.test.tsx src/features/canvas/ProjectWorkspacePage.test.tsx src/features/canvas/projectEvents.test.ts
pnpm typecheck
pnpm lint
pnpm build
```

本次浏览器访问 localhost 再次返回 `net::ERR_BLOCKED_BY_CLIENT`。实际指针操作、布局截图与浏览器控制台未验收，见 [design-qa.md](../../design-qa.md)；组件测试不替代该项。
