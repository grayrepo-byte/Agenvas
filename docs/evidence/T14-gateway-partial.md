# T14 Spring AI Gateway：阶段证据

本切片尚未完成 T14，更不满足 M3 门禁。当前没有真实 LLM Provider 凭证或真实模型调用。

Spring AI 2.0.1 的 ChatClient 在本调用路径关闭默认 ToolCallingAdvisor 自动注册。单次 Gateway 调用返回原始 ChatResponse，业务层用 V13 `llm_turn` 在外部模型调用前保存有版本的请求，收到完整响应后保存所有 generation、tool_call_id、工具参数与协议元数据，再向调用者返回。已记录响应的精确重放直接读取数据库，不重发模型请求；请求已发但响应未落库的崩溃仍可能多花一次模型费用。模型网络调用显式拒绝在事务内运行。项目事件仅包含 runId/stepIndex，不把 Prompt 或模型元数据送到 SSE。

`SpringAiChatGatewayTest` 使用假 ChatModel 验证一个模型回合仅调用一次、不自动执行 ToolCallback、原始 tool_call_id 与元数据保留，以及手工工具结果按原 ID 回填后的下一回合。`LlmTurnPostgresIT` 使用真实 PostgreSQL 与假 ChatGateway 验证 V13、请求/响应事件、完整响应落库、无长事务调用、精确重放不再次调用模型、异参冲突及越权阻断。视觉与原生结构化输出能力保持 false。

未完成：完整的受信 Tool Registry、持久 Run Worker 与回合租约、真实 LLM Provider 配置/调用、模型实际工具兼容性与视觉/结构化输出能力验证。V14 已新增 `create_text` 的 ToolContext 与工具账本切片，见 `T15-tool-ledger-partial.md`。当前 `REQUESTED` 状态的并发调用尚未由回合租约排他，不能把此服务直接开放为并发 Run 执行入口。
