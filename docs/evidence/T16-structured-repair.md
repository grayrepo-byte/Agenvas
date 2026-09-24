# T16 结构修复与原子工具回合（阶段证据）

2026-09-24：模型响应先完整写入 `llm_turn`，再由 `ToolBatchExecutionService` 在一个数据库事务中执行该响应的所有工具调用。任一工具参数或计划领域校验失败时，整轮业务写入和 `tool_execution` 账本回滚；模型回合及用量检查点保留。

`AgentTurnCommitService` 将修复次数写入下一条持久 Agent-turn Task。每次修复使用前一轮已保存的请求、可安全回放的助手工具参数、仅用于模型协议的合成失败回执和服务端具体校验错误，消耗新的模型回合与用量。合成回执不调用任何业务工具。响应超出修复上下文大小或协议结构损坏时，仅追加服务端错误说明。最多进行两次修复；仍失败或达到 12 回合总上限时，Task 以 `MODEL_OUTPUT_INVALID` 失败，Run 进入 `BLOCKED`，前端仅展示固定的安全说明。越权、配额、配置或外部故障不走自动修复。

`AgentTurnRepairPostgresIT` 使用 Fake 模型与真实 PostgreSQL，覆盖同轮先有效后无效工具的原子回滚、成功修复、计划领域错误两次修复耗尽、回合计数和无额外业务产物。`AgentTurnWorkerPostgresIT` 覆盖正常工具往返。`BlockedRunNotice.test.tsx` 覆盖阻断说明。

限制：尚未用真实 LLM 验证修复提示的实际生成效果；修复上下文有 64 KiB 回放阈值，不保证超大或畸形响应的原始参数被回填。此项不证明完整三镜头生成或真实媒体 Provider 可用。
