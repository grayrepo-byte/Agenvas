# Spring AI 2.0.1 对 Agent Skills 的支持边界

调研日期：2026-09-27  
核对版本：[Spring AI `v2.0.1`](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)，tag commit `c9107fdac0a6c88489c08bb88abcee74c146981d`

## 结论

**Spring AI 2.0.1 对 Skill 是“Provider 专属支持，但没有通用 Skill Runtime”。**

- **支持**：`spring-ai-anthropic` 可以在请求中启用 Anthropic/Claude 托管的 Agent Skills。它提供 `AnthropicChatOptions.skill(...)`、`AnthropicSkillContainer`、预置的 XLSX/PPTX/DOCX/PDF 枚举，以及生成文件的提取和下载辅助类。
- **不支持**：Spring AI Core 2.0.1 没有模型无关的本地 Agent Skills 框架，即没有内置的 `SKILL.md` 扫描、元数据发现、按需加载正文、继续加载引用资源/脚本、版本和生命周期管理。
- **可以自行组合**：Tool Calling、Tool Search、MCP、Advisor 和 Prompt Template 可以作为自研 Skill Runtime 的构件，但单独或组合使用它们，不会自动得到 Agent Skills 语义。
- **Agenvas 当前没有启用 Anthropic Skills**：项目当前使用 `spring-ai-client-chat` 和 `spring-ai-starter-model-openai`，没有引入 Anthropic model/starter，也没有引入 Tool Search starter。见项目的[依赖基线](../dependency-baseline.md)和[后端 POM](../../backend/pom.xml)。

因此，若“支持 Skill”指“能否通过 Spring AI 调用 Claude API 上已经存在的 Skill”，答案是**能，但限定 Anthropic Provider**；若指“Spring AI 是否自带类似 Claude Code/Codex 的、跨模型且可自托管的 Skill 包发现与渐进加载机制”，答案是**没有**。

## 1. 本文所说的 Agent Skills

Anthropic 对 Agent Skills 的定义不是“给函数起一个 Skill 名字”，而是可复用的能力包：包含元数据和指令，并可附带脚本、模板和参考资料。其官方运行方式分三层渐进加载：

1. 启动时只加载 `SKILL.md` frontmatter 中的名称和描述，用于发现和匹配；
2. 命中后加载 `SKILL.md` 正文中的操作指令；
3. 执行过程中再按需读取引用文件或运行脚本。

这一定义和加载模型见 Anthropic 官方的 [Agent Skills overview](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview#how-skills-work)。Claude API 的 Skill 还依赖 code execution container；预置和自定义 Skill 都通过请求的 `container.skills` 引用已有 `skill_id`。见 [Claude API 的 Skills 说明](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview#claude-api)。

据此，判断框架是否“支持 Agent Skills”至少应检查：是否有 Skill 包格式、发现/匹配、指令渐进加载、资源/脚本访问和生命周期，而不能只看它是否支持工具调用或动态 Prompt。

## 2. Spring AI 2.0.1 已支持的部分：Anthropic 原生 Skills

Spring AI 2.0.1 的 Anthropic provider 文档有独立的 Skills 章节，明确列出 XLSX、PPTX、DOCX、PDF 四个预置 Skill，并示例通过 `AnthropicChatOptions.builder().skill(AnthropicSkill.XLSX)` 启用。配置 Skill 后，Spring AI 会自动加入所需的 code execution 能力。见 [Spring AI Anthropic Chat — Skills](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html#_skills)。

tag `v2.0.1` 的源码进一步说明了边界：

- [`AnthropicChatOptions.Builder.skill(String)`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-anthropic/src/main/java/org/springframework/ai/anthropic/AnthropicChatOptions.java#L828-L868) 会把四个预置名称转换为 Anthropic Skill；其他字符串会作为 `CUSTOM` 类型的 Skill ID。
- [`AnthropicSkillRecord`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-anthropic/src/main/java/org/springframework/ai/anthropic/AnthropicSkillRecord.java#L25-L81) 只记录 `type`、`skill_id` 和 `version`，然后序列化到 Provider 请求。
- [`AnthropicChatModel`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-anthropic/src/main/java/org/springframework/ai/anthropic/AnthropicChatModel.java#L909-L945) 把这些记录写到 Anthropic 请求的 `container.skills`，补入 code execution tool 及 Anthropic beta headers。

这意味着 Spring AI 在此处承担的是 **Anthropic API 适配**，不是在 JVM 中读取和执行 Skill 包。自定义 Skill 必须先存在于 Anthropic workspace；Spring AI 的 Anthropic 抽象不负责自定义 Skill 的上传、CRUD 或版本管理。官方迁移文档明确要求这类 API 降级到 Anthropic SDK client，并指出这样会离开 Spring AI 的 Provider-neutral `ChatClient` pipeline，见 [`anthropic-migration.adoc`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/anthropic-migration.adoc#L158-L168)。

所以，这项支持具有三个限制：

- 只适用于 Anthropic provider，不是 Spring AI 的 portable model API；
- Skill 内容和执行环境由 Anthropic 托管，不是应用本地自托管；
- Spring AI 可以引用已有自定义 Skill ID，但没有在框架层提供创建和管理 Skill 包的完整生命周期。

## 3. Spring AI Core 2.0.1 没有通用 Skill Runtime

对 `v2.0.1` 源码树和官方参考文档的核查，没有发现通用的 `Skill`/`SkillCatalog`/`SKILL.md` loader、Skill 包扫描器或相应 starter。Core 中名称含 Skill 的正式 API 集中在 Anthropic provider 的上述适配类，而不是模型无关模块。

Spring AI 官方仓库中的 [Skill Extension Framework 请求 #5293](https://github.com/spring-projects/spring-ai/issues/5293) 也把“统一的 Skill 定义、注册、发现和生命周期管理”列为待增加能力；该请求已关闭且没有关联的 Core 实现或 PR。后续 [Skills 支持路线问题 #6511](https://github.com/spring-projects/spring-ai/issues/6511) 截至调研日仍为 open / waiting-for-triage，并把现状描述为 Spring AI Community 中有 `SkillsTool`、是否进入 Spring AI Core 尚不明确。Issue 内容是提议者的陈述，不替代发布合约；这里仅将它作为源码和官方文档核查结果的旁证。

## 4. 容易混淆但不等价的能力

| Spring AI 能力 | 2.0.1 提供什么 | 为什么不等于 Agent Skills |
|---|---|---|
| Tool Calling | 注册 `@Tool`/`ToolCallback`，让模型发起结构化调用，应用执行并回传结果。见 [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)。 | Tool 是可调用动作及 JSON Schema；不包含 `SKILL.md` 指令包、引用资源或 Skill 生命周期。 |
| Tool Search | `ToolSearchToolCallingAdvisor` 按会话索引工具，只逐步向模型暴露命中的工具定义。见 [Tool Search Tool](https://docs.spring.io/spring-ai/reference/api/tools/tool-search-tool.html#how-it-works)。 | 它实现的是“工具定义的渐进披露”，没有加载 Skill 正文、引用文档和脚本。 |
| MCP | 可以消费或暴露 tools、resources、prompts；MCP tools 可适配为 Spring AI `ToolCallback`。见 [MCP overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 和 [MCP utilities](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-helpers.html)。 | MCP 分别提供能力原语和传输协议，不定义 `SKILL.md` 包，也不会自动把一组 prompt/resource/tool 作为一个 Skill 发现、触发和渐进加载。 |
| Advisor | 拦截并修改请求/响应，可向 Prompt 注入内容或编排递归调用。见 [Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html#core-components)。 | Advisor 是扩展点；Skill catalog、匹配、安全检查和加载状态仍须应用实现。 |
| Prompt Template | 从字符串或 `Resource` 渲染 Prompt。见 [PromptTemplate](https://docs.spring.io/spring-ai/reference/api/prompt.html#prompttemplate) 和 [从 Resource 加载模板](https://docs.spring.io/spring-ai/reference/api/prompt.html#_using_resources_instead_of_raw_strings)。 | 模板是显式选定并一次渲染的内容，不提供 Skill 自动发现和多层按需加载。 |

其中 Tool Search 与 Agent Skills 都使用“progressive disclosure”这个表述，但披露对象不同：前者披露工具 JSON 定义，后者从 Skill 元数据扩展到完整操作指令，再到资源和代码。二者不能按名称相似直接视为同一功能。

## 5. 对 Agenvas 的含义

当前项目锁定 Spring AI 2.0.1，只引入 `spring-ai-client-chat` 和 OpenAI 兼容 starter；因此：

1. `AnthropicChatOptions.skill(...)` 不在当前运行路径中；仅升级/保留 Spring AI 版本不会自动获得 Claude Skills。
2. 现有 Tool Calling 可以承载受控业务动作，但不能把工具集合直接称为已经实现 Agent Skills。
3. 若产品未来需要模型无关、可开源独立运行的 Skill，应在应用层定义明确的 Skill catalog/loader 与持久化/授权边界，或把第三方实现隔离在可替换端口后；不能把 Anthropic 托管 Skill 作为核心功能前提。
4. Anthropic 的通用 Skill 定义允许脚本和 shell，但 Agenvas 当前约束禁止工具执行任意 Shell、HTTP 或动态插件。因此即使实现本地 Skill，也不应原样复制 Claude Code 的通用执行模型；可先限定为可信、版本化的指令/参考资源，加上应用预注册的允许列表工具。

## 核查范围与未验证项

- 已核对 Spring AI 官方 `v2.0.1` tag 源码、2.0.1 官方参考文档，以及 Anthropic Agent Skills 官方文档。
- 未调用真实 Anthropic API，未验证具体账号、模型或 beta feature 的可用性和计费。
- 未引入或运行 Spring AI Community 的 `SkillsTool`，本文不对其生产成熟度作结论。
- 本次只形成调研记录，没有修改依赖或实现代码，也没有运行项目测试。
