# ADR 0048：保留内置 Agent，附加用户级 MCP 接入

用户确认保留现有导演、Agent Runtime 和聊天，通过现有 Spring Boot 附加 MCP 入口，让外部客户端复用创作能力。MCP 凭据属于用户，在每次调用时校验当前项目权限；具备生成权限的 MCP 调用直接受理媒体 Task，不增加收费审批。现有内置 Agent 的媒体审批和结果续接继续保留。

这避免为外部接入替换模型循环、托管 ACP Agent 或管理外部会话，代价是需要用户级凭据、独立写命令幂等及清晰的产物/任务来源。MCP 不创建假 AgentRun/LlmTurn，不占内置活动 Run 槽位；媒体执行复用现有 Task/Provider/归档和 UNKNOWN 规则。详细工具、鉴权及模块改造见 [实施计划](../mcp-extension-implementation-plan.md)；客户端配置见[接入指南](../mcp-client-guide.md)，实际验证范围见[开发清单](../DEVELOPMENT-CHECKLIST.md)。

实现采用现有 Spring AI 2.0.1 管理的稳定官方 Java SDK 2.0.0，显式注册 12 个工具，使用无状态 Streamable HTTP，最高协商 `2025-11-25`。逐请求传递认证身份，不通过 ThreadLocal 或客户端参数恢复用户，不自动导出内置 Agent Tool Bean。手工 Bearer 省去 OAuth 服务与外部 Agent 会话，但要求客户端支持自定义认证头，OAuth 专用客户端不在本版范围。

V30 增加用户凭据、命令账本、Task/版本关联四表及 `EXTERNAL_TOOL` 文字来源，不重写旧内容。已有 MCP Task 继续走原执行链，关闭新入口或撤销令牌不取消它。网页显式对 MCP UNKNOWN 创建新尝试时转换为网页直接任务，保留替代关系，避免错误归到原外部命令。

媒体上传采用 MCP `prepare_media_upload` 发现要求，再以同一 Bearer 向精确 `/mcp/uploads` 发送 multipart 的两步接口，当前共 14 个工具。直接把大文件放进 MCP JSON 会扩大内存与协议限制；接受服务端路径或任意 URL 又会扩大文件和出站访问权限，因此文件由客户端发送，JSON 仍保持原有小请求限制。代价是客户端需要普通 HTTP 文件上传能力，反代需要独立上传路由。准备工具不创建业务状态；真实文件校验及存储 I/O 在事务外完成，短事务复核授权后原子发布不可变版本、可选节点及用户命令账本，重传不会重复创建。不新增数据库表、模型调用或生成授权，版本使用现有 `EXTERNAL_TOOL` 作者与 `UPLOAD` 来源。
