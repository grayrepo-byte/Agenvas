# 开源 Agent 的上下文摘要与输出预算

核对日期：2026-11-15。只依据官方源码与文档；下列建议尚不代表 Agenvas 已完成实施或真实模型验收。核验的是 Codex 本地文本压缩路径和 OpenCode 通用摘要路径，不推断其远程原生压缩服务的内部策略。

## 源码事实

| 项目与固定提交 | 摘要内容 | 模型请求与失败处理 |
| --- | --- | --- |
| Codex `b016cd18472588606c04af565528c4cd6746a41f` | 提示保留进展、决定、约束、下一步及必要引用，要求简洁、结构化；本地压缩路径没有生成后按固定 Token 数拒绝摘要的校验。源码的 `COMPACT_USER_MESSAGE_MAX_TOKENS=20_000` 限制保留的用户消息，并非摘要正文上限。 | 复用模型调用及压缩用途的 reasoning 配置，等待完整响应；窗口溢出时逐次移除最早历史，直到无法继续缩减。其他错误沿 Provider 的有限重试配置处理，不能恢复时返回错误。 |
| OpenCode `d432a06b9c07bab0be4e8d642173b1cb14137f11` | 使用任务交接模板，更新既有摘要并保留近期对话；没有独立的小摘要 Token 上限或生成后固定长度拒绝。模板缺失只提醒一次。 | `outputLimit` 取模型输出能力与窗口剩余空间；摘要 `fitOutput` 根据实际输入再次适配，空间允许时保留最高 32,000 Token 的输出下限。窗口或请求过大按有限缩减序列处理。`finish=length` 仍明确失败，并未接受半截摘要或无限重试。 |

Codex 来源：[摘要提示](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/prompts/templates/compact/prompt.md)、[本地压缩与重试](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/compact.rs#L308-L395)、[保留用户消息及摘要组装](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/compact.rs#L737-L863)。

OpenCode 来源：[模型输出额度](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/model-request.ts#L94-L105)、[模板验证与续接](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L470-L556)、[有限缩减](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L615-L706)、[截断拒绝](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L797-L840)、[实际输入与输出适配](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L1213-L1234)。

因此，“没有额外固定摘要内容上限”符合这两条源码路径；“没有输出或容量上限”不符合。长度目标、模型生成额度、下一轮输入容量是三个不同约束。

## reasoning 与摘要额度的已知耦合

OpenCode 官方问题 [#59110](https://github.com/anomalyco/opencode/issues/59110) 记录并复现过：把压缩缓冲额度同时用作模型输出额度时，推理模型可能全部用于 reasoning，公开摘要为空。官方修复提交 [6709f564](https://github.com/anomalyco/opencode/commit/6709f5646de29460a7caf8e4f553369a130413b7) 将摘要输出额度改为普通回合的计算方式；上述固定提交已包含这一行为。该问题的复现不能证明所有兼容服务都采用同一种计量方式。DeepSeek 官方 [Responses 用量说明](https://api-docs.deepseek.com/guides/responses_api/) 则明确将 reasoning 作为输出用量的组成部分。

## 对 Agenvas 的最小建议

以下是工程判断，需由实施测试确认：

1. 删除“摘要估算超过固定 Token 数就拒绝”的规则及摘要 Token 目标设置。提示只要求围绕继续任务所需事实、约束和精确引用组织简洁交接，不再规定硬或软的摘要 Token 数。
2. 压缩请求依据已固定的模型输出能力和实际窗口余量选择 wire 输出额度；技术重试保留原检查点，新修复请求沿已固定计划构造。
3. 验收下一轮的完整请求能否进入实际输入预算，并为继续工作保留空间。需要再次压缩时针对真实容量或未取得压缩进展处理，避免围绕固定摘要长度反复修复。
4. 截断摘要不进入续接上下文；允许有次数上限的重新生成与明确修复提示。reasoning 配置只能按连接已声明的协议能力处理，不对所有兼容模型盲加专属参数。
5. 保留结构、完整响应、取消、租约和持久化校验。摘要中的文字不能批准媒体调用、授予权限或提供当前 CAS；先前已提交工具和媒体结果不可因压缩重发。

不直接照搬 OpenCode 的删除旧来源策略：Agenvas 已要求分块持久保存来源、保留媒体 UNKNOWN 和审批事实，应继续遵守现行应用边界。

## 用户决定与 OpenCode 算法核验

用户进一步明确：取消摘要目标设置及所有固定摘要长度提示，采用成熟开源 Agent 的压缩方式。以下整理 OpenCode 上述固定提交的核心流程，供 Java/Spring AI 实施；其中 `keep` 是近期原文的保留预算，`buffer` 是窗口余量，两者都不是摘要长度。

当前提交已由 `inputWindow`、`calculateCeiling`、`due`、`recentStart` 取代较旧实现中的 `usable`、`isOverflow`、`preserveRecentBudget` 名称，不能把旧版函数名与当前算法混写为同一版本。

```text
effectiveInputWindow:
  输入与总窗口均已知 → 取较小值
  只知其一 → 使用已知值；均未知 → 不凭估算自动压缩

autoThreshold:
  buffer 为合法 Token 数或窗口比例 → effectiveInputWindow - buffer
  否则预留窗口的 15%；窗口至少 32k 时预留至少 16k

shouldCompact:
  刚完成压缩 → 先让普通请求从新历史重建，暂不再次触发
  其余情况 → 同一模型报告的已用量 + 后续新增内容估算 >= autoThreshold

retainedTail:
  从最新消息向前累计，取 keep 预算内的原文（上游默认 15k）
  起点退到用户消息边界，保持同一 exchange 的工具调用与结果完整
  所有历史都适合时，只留最后一个 exchange，避免原文与摘要全部重复
  没有可分离旧历史时，摘要整个 exchange，或只更新已有摘要

normalOutputAllowance(actualInput):
  modelCeiling = 已知模型输出能力；未知时上游回退 32k，通用保护为 256k
  room = 总窗口 - 已测输入 - 1.15 * 未测输入估算
  返回 modelCeiling 与 room 的较小值（上游最小请求额度为 1k）

compactionOutputAllowance(actualRequest):
  先按 normalOutputAllowance 计算
  根据实际发出的请求重新适配，不能按预期缩减前的错误假设选择输出额度
  若估算余量挤压额度，允许使用实际剩余空间中的最多 32k 作为输出保障
  仍受模型输出能力、总窗口和实际请求大小约束

compaction:
  优先发送可容纳的完整历史前缀，必要时排除会原样保留的 tail
  输入仍过大 → 转为文本形式并缩减来源
  Provider 证明溢出 → 按首次被拒大小的 85%、70%、50%、35% 依次缩减
  无法保住最新 exchange/已有 checkpoint 或缩减次数耗尽 → 明确失败
  摘要缺模板 → 提醒一次；仍无效 → 明确失败
  完整有效摘要 → 组成 checkpoint(summary + retainedTail)，继续普通 Agent
```

近期保留依据：[splitConversation/recentStart](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L1011-L1069)。窗口与防连续压缩依据：[due](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L440-L449)、[calculateCeiling/inputWindow](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L1158-L1186)。预算、有限缩减和模板提醒依据见前表链接。

数值是本次核验的上游策略，不是摘要正文目标。当前源码没有“新摘要必须小于旧摘要”的单独比较；防止无工作进展时连续压缩主要依靠刚压缩后的跳过、历史切分及有限溢出缩减。Agenvas 若增加压缩进展校验，应作为自身适配明确记录，不能宣称来自上游。

Agenvas 的适配边界：复用 Java/Spring AI 与现有持久模型回合，不引入 OpenCode 的 Effect Runtime、内存后台任务或原生远程服务。冻结请求和模型容量继续保存到检查点；技术重试重放原请求，新压缩/修复请求才按相同固定计划构造。已存在的原检查点不覆盖。来源超量沿现有持久分块处理，不能为仿照上游而丢弃未归纳的来源。摘要截断后的有界修复属于 Agenvas 自身的恢复策略；上游当前仍将 `length` 判失败，不应把这项扩展描述成原样移植。

## 小窗口核验与 Codex 主流程

OpenCode 上述 `keep=15k` 并没有在普通摘要路径中按窗口或固定工具 Schema 动态缩小。`recentStart` 为了完整 exchange 可以超过该预算；`due` 仅在最后消息是 `type=compaction, status=completed` 时跳过，下一条普通模型消息后即可再次触发。因此在 32k 窗口、较大固定工具 Schema 和 16k 自动阈值下，原样采用该 tail 配置仍可能每个普通回合后压缩。后台摘要的 `budget * 0.4` 尾部校验只影响缓存复用，不能拿来解释普通路径已经动态限额。[近期切分](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L1034-L1055)、[触发状态](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L440-L449)、[后台缓存尾部条件](https://github.com/anomalyco/opencode/blob/d432a06b9c07bab0be4e8d642173b1cb14137f11/packages/core/src/session/compaction.ts#L413-L429)。

Codex 本地压缩的替换历史更适合 Agenvas 当前的单任务模型：`build_compacted_history` 先保留客户端明确标记跨窗口保留的项，再倒序选取用户消息，最后附加交接摘要；没有例行保留近期普通模型回答和工具结果。其 20k 原文预算用于用户消息，并非摘要目标。明确保留的工具项仍需配对，因此不能概括成所有工具历史一律删除。[历史替换算法](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/compact.rs#L736-L861)、[显式保留标记](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/history/src/lib.rs#L232-L238)、[保留项配对](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/compact_remote_v2.rs#L580-L603)。

压缩调用复用普通模型客户端，单独选择压缩用途的 reasoning 配置；调用点没有摘要 Token 目标，标准 Responses 请求构造也不设置 `max_tokens` / `max_output_tokens`。接受条件按阶段有别：PostTurn 明确要求非空摘要，其他阶段读取已经记录的最后 assistant 文本并添加交接前缀；并无 JSON 结构或摘要 Token 校验。[压缩调用](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/compact.rs#L876-L904)、[响应与交接组装](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/compact.rs#L395-L410)、[普通请求构造](https://github.com/openai/codex/blob/b016cd18472588606c04af565528c4cd6746a41f/codex-rs/core/src/client.rs#L1321-L1349)。该事实不代表 Provider 没有输出上限，也不代表所有部署的拦截器均不改请求。

据此，Agenvas 可采用 Codex 的“稳定任务上下文 + 交接摘要”主流程，移除摘要 Token 设置和近期工具回合设置，而不是引入 OpenCode 固定 15k tail：

```text
达到上下文触发条件：
  pinned = 系统/创作指令 + 当前完整任务 + 已激活 Skill 主说明 + 当前媒体引用
  source = 前次交接 + 全部已完成模型/工具历史
  freeze = 模型配置、容量、原来源片段与待恢复请求
  在无工具的持久回合中生成公开交接；来源过大则沿持久分块合并
  校验完整响应、必要结构和引用语义边界，不按摘要 Token 数拒绝
  next = pinned + handoff + 已冻结工具定义
  校验 next 的真实输入预算；成功后继续原 Agent
```

这里保留 JSON 结构校验、完整响应要求、真实输入预算和有界修复，是 Agenvas 现有持久执行边界的适配。来源和工具账本保留在数据库中，替换的是未来请求的历史表示；不覆盖检查点、不重发已完成副作用。固定输入本身超过模型容量仍需明确报错，成熟压缩流程不能把不可压缩的原指令变小。
