# ADR 0072：GPT Image 2.6 与动画 GIF

状态：接受（2026-11-05）。

用户要求接入 GPT Image 2.6 Flare、Sunburst 和 GIF。复用 `OPENAI_GPT_IMAGE_2` 的固定 Images API、服务端凭据与显式模型配置，不自动替换已有模型、价格、能力或任务。

## 协议与产品范围

- 新模型为 `gpt-image-2.6-flare`、`gpt-image-2.6-sunburst`。静态生成与编辑继续使用 `/v1/images/generations`、`/v1/images/edits`；支持 low/medium/high/xhigh/max 画质。2.6 可配置独立的 low/medium/high complexity，省略沿用上游默认。
- 管理员可发布 PNG 或动画 GIF 图片能力，创作者通过既有能力选择器使用；GIF 帧数和每帧时长属于不可变能力配置。GIF 不支持编辑、蒙版、参考图、压缩、流式或 partial images。GIF 能力只声明自动尺寸，避免把静态图的尺寸表当成未知的 GIF 像素预算。GIF 访问取决于供应商账户权限。
- 官方指南写 2–64 帧、20–2000ms，而 API reference 写 2–16 帧及不同的时长约束；采用交集 2–16 帧、20–2000ms（10ms 整数倍），默认 9 帧、200ms。未付费实测前不承诺更宽范围。指南明确 GIF 仅生成，编辑 schema 的字段枚举不能证明 GIF 编辑可用。
- GIF 原件按原字节归档、恢复、下载和播放，使用实际解码 MIME `image/gif` 与 `.gif` 后缀。PNG 缩略图只代表静态首帧。上传沿用 20MiB、40MP 单帧限制，并限制帧数与累计解码像素；解码每帧后释放缓冲。编辑静态参考路径仍产生 PNG，因此动画 GIF 不作为模型编辑参考或本地图像工具输入。
- 新价格预设只匹配精确模型及官方 `/v1` 端点：美元/百万 Token 文本输入 5、图片输入 8、图片输出 30。沿用真实 usage 核算和冻结费率，不按帧数伪造 Token 或费用。缓存报价不等于直接 Images API 可验证命中；在官方说明和响应不能确认适用性时，不自动预填缓存折扣。已有人工价格保持不变。
- 不支持 SVG；本次不扩大到矢量输出。请求同步返回，无可查询远程任务 ID；不自动重发生成，受理不确定继续 UNKNOWN。下载及归档恢复仅处理已有结果。

配置、参数、模型和价格沿用 JSONB 不可变能力版本，无 SQL 迁移或 jOOQ 变更。GIF 与静态能力双向切换复用现有参数整理、素材清理、标签、连线及 CAS 规则；后台更新不改写已保存草稿或已受理任务。GIF 能力不提供生成式编辑工具绑定。

## 官方依据与验证边界

核验日期 2026-11-05：[图片指南](https://developers.openai.com/api/docs/guides/image-generation)、[生成 API](https://developers.openai.com/api/reference/resources/images/methods/generate)、[定价](https://developers.openai.com/api/docs/pricing)、[Flare](https://developers.openai.com/api/docs/models/gpt-image-2.6-flare)、[Sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst)。

本次验证通过 347 项前端、117 项后端单元测试、6 个隔离 PostgreSQL 集成测试，及三种尺寸的生产 Chrome 动画双帧播放测试。使用合成媒体和本地假 HTTP；真实付费生成、账户 GIF 权限、效果、延迟和真实账单未验证。未运行全量测试。


## OpenAI Images 内容审核参数（2026-11-05）

按用户要求，在所有 `OPENAI_GPT_IMAGE_2` 能力（包括 2.5、2.6 及兼容端点）增加可选 `moderation`，仅接受 `auto`、`low`。管理页默认显示标准审核 `auto`；`low` 是较宽松的过滤，内容审核仍然生效，不提供关闭审核选项。其他适配器没有此控件，后端白名单拒绝该字段。

显式选择的值进入不可变能力版本及映射摘要；生成 JSON、multipart 编辑和 URL 编辑均从任务固定的历史能力版本读取并发送，不读取当前编辑器或最新能力。更新能力不改写任务冻结参数：尚未提交的旧任务沿用既有 `MEDIA_CAPABILITY_CHANGED` 拦截，不能自动套用新值；已开始的请求继续使用原值，结果沿原任务归档。审核拒绝的诊断、状态和不自动重新生成的规则保持原行为。

历史配置、未选择新字段的编辑草稿和历史任务继续省略该字段，以保持既有能力哈希、幂等重放与兼容网关的请求形态。直连官方时省略即使用 `auto`；明确选择 `auto` 时发送 `auto`。不迁移历史 JSONB，不增加数据库迁移。

依据：[OpenAI 内容审核说明](https://developers.openai.com/api/docs/guides/image-generation#content-moderation)、[生成接口](https://developers.openai.com/api/reference/resources/images/methods/generate)、[编辑接口](https://developers.openai.com/api/reference/resources/images/methods/edit)。验证仅使用本地合成 HTTP 和隔离 PostgreSQL，不做真实付费调用。


## GPT Image 2.5 快捷选择（2026-11-05）

按用户追加要求，模型选项增加 GPT Image 2.5 Flare、GPT Image 2.5 Sunburst 的明确快捷入口。品牌模型名称在四种界面语言中保持一致，周围标签及审核说明按语言显示。既有默认模型不变；自定义名称及 `2026-09-08` 快照仍从可编辑模型名入口完整保存，不自动升级或替换。

2.6 GIF 显式切换到 2.5 时回到 PNG，移除帧数/时长/complexity 及 GIF 的零参考图限制；保留目标支持的 xhigh/max、moderation 和已保存人工费率。当前会话未修改的自动预设沿用既有来源标记随精确模型更新。2.5 不新增 GIF 生成或编辑能力，不新增未经实现的参数。
