# ADR 0048：保留内置 Agent，附加用户级 MCP 接入

用户确认保留现有导演、Agent Runtime 和聊天，通过现有 Spring Boot 附加 MCP 入口，让外部客户端复用创作能力。MCP 凭据属于用户，在每次调用时校验当前项目权限；具备生成权限的 MCP 调用直接受理媒体 Task，不增加收费审批。现有内置 Agent 的媒体审批和结果续接继续保留。

这避免为外部接入替换模型循环、托管 ACP Agent 或管理外部会话，代价是需要用户级凭据、独立写命令幂等及清晰的产物/任务来源。MCP 不创建假 AgentRun/LlmTurn，不占内置活动 Run 槽位；媒体执行复用现有 Task/Provider/归档和 UNKNOWN 规则。详细工具、鉴权及模块改造见 [实施计划](../mcp-extension-implementation-plan.md)；客户端配置见[接入指南](../mcp-client-guide.md)，实际验证范围见[开发清单](../DEVELOPMENT-CHECKLIST.md)。

实现采用现有 Spring AI 2.0.1 管理的稳定官方 Java SDK 2.0.0，显式注册 12 个工具，使用无状态 Streamable HTTP，最高协商 `2025-11-25`。逐请求传递认证身份，不通过 ThreadLocal 或客户端参数恢复用户，不自动导出内置 Agent Tool Bean。手工 Bearer 省去 OAuth 服务与外部 Agent 会话，但要求客户端支持自定义认证头，OAuth 专用客户端不在本版范围。

V30 增加用户凭据、命令账本、Task/版本关联四表及 `EXTERNAL_TOOL` 文字来源，不重写旧内容。已有 MCP Task 继续走原执行链，关闭新入口或撤销令牌不取消它。网页显式对 MCP UNKNOWN 创建新尝试时转换为网页直接任务，保留替代关系，避免错误归到原外部命令。
