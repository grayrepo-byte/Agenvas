# ADR 0048：保留内置 Agent，附加用户级 MCP 接入

用户确认保留现有导演、Agent Runtime 和聊天，通过现有 Spring Boot 附加 MCP 入口，让外部客户端复用创作能力。MCP 凭据属于用户，在每次调用时校验当前项目权限；具备生成权限的 MCP 调用直接受理媒体 Task，不增加收费审批。现有内置 Agent 的媒体审批和结果续接继续保留。

这避免为外部接入替换模型循环、托管 ACP Agent 或管理外部会话，代价是需要用户级凭据、独立写命令幂等及清晰的产物/任务来源。MCP 不创建假 AgentRun/LlmTurn，不占内置活动 Run 槽位；媒体执行复用现有 Task/Provider/归档和 UNKNOWN 规则。详细工具、鉴权及模块改造见 [实施计划](../mcp-extension-implementation-plan.md)；本决策记录接入方向，不表示功能已实施或验收。
