# 媒体工具结果与 Skill 上下文管理

核对日期：2026-10-06。仅阅读第一方规范、官方文档和本地代码；本文件记录研究建议，具体实施与验收以对应 ADR、代码和测试为准。

## 适用方案

推荐采用**精简工具结果、稳定引用、按需读取**：完整对象保存在应用的持久状态中，模型每轮接收继续工作所需的字段，并通过工具获取详情。Anthropic 的上下文工程指南明确介绍用文件路径、查询和链接等轻量引用在运行时读取数据，以及逐步披露上下文；更大窗口仍需要筛选有效信息。[Anthropic 上下文工程](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

工具结果应优先提供高信号信息。字段过滤、分页、范围读取和明确的精简/详细结果都是官方推荐的方法；实际需要用于后续调用的标识符仍须保留，配合名称等语义信息方便模型理解。[Anthropic 工具设计](https://www.anthropic.com/engineering/writing-tools-for-agents)

Skill 的标准加载顺序是名称与描述、激活时加载主文件、需要时加载资源。此规范与 Agenvas 已接受的 [ADR 0035](../adr/0035-progressive-multiple-skills.md) 一致；媒体提案再次逐项附带主文件和附件正文，会绕过这一分层。[Agent Skills 渐进式加载规范](https://agentskills.io/specification#progressive-disclosure)

OpenAI 的 tool search 也采用运行时加载详情，只提前提供可发现的名称和描述。不过它解决的是工具定义目录的加载，并非自动删除业务工具结果中的重复数据；该 Provider 特定能力不能直接替代 Agenvas 的结果投影。[OpenAI tool search](https://developers.openai.com/api/docs/guides/tools-tool-search)

## 与其他手段的区别

| 手段 | 处理的问题 | 对本问题的作用 |
| --- | --- | --- |
| 精简结果与稳定引用 | 工具将完整持久对象复制进上下文 | 在产生和续接结果时去掉冗余正文，直接消除每个输出的重复 Skill 快照。 |
| Prompt caching | 多次请求复用相同前缀的处理成本 | 缓存复用输入前缀；应用仍发送输入，缓存 token 仍计入速率限制。因此不能绕过本地 JSON 字节检查或消除上下文冗余。[OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching) |
| 历史 compaction | 长期交互的有效历史仍超过窗口 | 保留必要状态的较短表示，可作为后续长对话能力。OpenAI Responses compaction 使用不透明的压缩项并有规定的续接方式，不能假定所有 Provider 或现有 Spring AI 路径都兼容。[OpenAI compaction](https://developers.openai.com/api/docs/guides/compaction) |

## Agenvas 的落点与边界

以下为结合本地实现的工程判断：`AgentMediaApprovalService.toolResult()` 当前直接序列化审批展示模型，展示中的每个输出预览包含完整 `creativeSkill`；`AgentMediaOutcomeService.finalToolResult()` 又复制该回执并附加终态。`LlmConversationService.afterToolRound()` 把上一请求、原 assistant 工具调用和结果继续带入下一轮，重复对象因而随输出与回合累积。

1. 保留审批预览、Run 的固定 Skill 版本快照、Task 与内容版本的完整来源，保证用户审批、审计和恢复仍可读取原信息。持久化快照与模型上下文使用不同投影。
2. 模型回执仅保留继续执行所需的审批状态、审批/操作标识、输出标题和种类、产物/画布标识与版本、必要参数和固定 Skill 版本引用；终态包含任务状态、错误与已生成版本。完整主文件、附件和固定素材内容不随每个输出复制。现有 `read_skill`、`read_skill_resource` 与产物读取工具负责详情获取，并沿用项目、Run、激活和权限校验。
3. 对已有大型回执，可在**组装下一轮的新请求**时应用相同的确定性投影。已冻结的请求检查点、原始工具执行记录及审批快照不原地修改；同一轮重试仍读取其已冻结请求。原始 tool call ID、名称、调用顺序及一一配对的工具响应必须保持。
4. 投影不能改变审批 hash、Skill 激活账本或来源权限，不能让摘要批准媒体提交，也不能触发重新生成。工具终态、UNKNOWN 与可能产生外部成本的信息必须准确保留。

验收宜使用多个输出各含大型合成 Skill 的回执，验证新上下文字节数不会按完整快照乘输出数增长；同时验证审批详情与持久来源仍完整、工具调用配对不变、旧回执续接可投影、冻结检查点不被改写。没有真实 Provider 验证时，不将合成结果称为真实模型接通证据。
