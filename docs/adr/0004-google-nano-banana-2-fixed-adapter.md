# ADR 0004：Google Nano Banana 2 固定图片适配器

状态：接受（2026-09-25；2026-09-28 修订有序多参考输入；2026-09-30 修订配置状态文案）

## 背景

用户要求接入 Google Nano Banana。现有媒体能力目录可按连接版本固定密钥、按能力版本固定模型映射，并让图片步骤在审批前选择能力。Google 官方将 Nano Banana 2 对应为稳定模型 `gemini-3.1-flash-image`，通过 Gemini `generateContent` 支持文字生图和带图片编辑。

## 决策

- 新增 `GOOGLE` 平台和固定的 `GOOGLE_NANO_BANANA_2` 图片能力；模型固定为 `gemini-3.1-flash-image`，目标固定为官方 `https://generativelanguage.googleapis.com/v1/models/gemini-3.1-flash-image:generateContent`。管理员不能更改模型 ID、路径或端点。
- 支持文字提示、项目的 16:9／9:16／1:1 画幅、1K 图片及最多 14 个已冻结的同项目参考图片版本。适配器按任务顺序将所有图片编码为独立 `inlineData` part，不得只取第一张或静默丢弃；限制单图 10 MiB、合计 60 MiB。不接入 Google Search grounding、视频输入或任意工作流参数。
- Google API Key 由管理员配置，通过现有服务端加密连接版本保存。任务继续使用审批绑定、提交检查点、UNKNOWN、不可变资产归档和旧 Worker fencing。请求可能已受理而响应丢失时，不自动重提；同步图片 API 没有可核对原结果的任务 ID。
- 已配置不等于真实生成已验证。无真实 Google 密钥时仅用本地假 HTTP 服务和 PostgreSQL 验证协议与业务流程。2026-09-30 按用户确认移除媒体配置页写死的“未实测”，连接仅显示“已配置”；保存配置不调用付费生成，不据此标记“已实测”。真实成功、失败及 UNKNOWN 以调用日志和验收记录为准，未验证能力继续在验收记录中明确。

## 影响

新增 Flyway V41 允许 `GOOGLE` 平台，OpenAPI 平台枚举增加 `GOOGLE`；现有平台、能力和历史任务不迁移。旧客户端若穷举平台枚举，应更新生成类型和 UI 分支。2026-09-28 的多参考升级不新增数据库或 OpenAPI 字段，能力目录把 `maxReferenceImages` 提升为 14，并把草稿通用保护上限同步提升为 14；已受理任务仍只读取自身冻结输入。

官方依据：[Nano Banana 2 模型](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-image)、[图片生成 REST 示例](https://ai.google.dev/gemini-api/docs/generate-content/image-generation)。
