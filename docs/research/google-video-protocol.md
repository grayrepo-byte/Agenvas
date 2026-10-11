# Google 原生视频协议接入

核对时间：2026-10-11。只核对官方文档与 Google SDK 源码；没有真实生成、付费调用或私人素材测试。此说明先于实现保存，现补充已实现的参数契约；测试由根代理串行运行，检查结果以开发清单为准。

## 身份、地址与凭据

沿用 `GOOGLE` 连接的加密 API Key。视频生成是独立原生协议，不能根据既有普通图片 `generateContent` 地址拼出下载 URL。连接地址可留空，默认官方服务 `https://generativelanguage.googleapis.com`；显式地址仍须通过固定官方来源校验。生成与状态调用使用 `x-goog-api-key` 请求头；Key 不进入 URL、日志、Task 或结果清单。测试只允许构造器指定字面 `http://127.0.0.1:<port>`。

适配器为 `GOOGLE_VEO` 和 `GOOGLE_OMNI_VIDEO`，输出均为单个含原生音轨的 VIDEO。本次按用户确认发布以下四个模型、八个 profile；不发布已弃用的 Veo 3.0 或与 GA 重复的 Omni preview。Preview API 模型不等于引入 preview 软件依赖。[视频目录](https://ai.google.dev/gemini-api/docs/video)、[Veo 版本表](https://ai.google.dev/gemini-api/docs/veo#model-versions)、[Omni 模型](https://ai.google.dev/gemini-api/docs/models/gemini-omni-flash)

| 适配器 | Model ID | Operation |
| --- | --- | --- |
| GOOGLE_VEO | `veo-3.1-generate-preview` | GENERATE / EXTEND |
| GOOGLE_VEO | `veo-3.1-fast-generate-preview` | GENERATE / EXTEND |
| GOOGLE_VEO | `veo-3.1-lite-generate-preview` | GENERATE |
| GOOGLE_OMNI_VIDEO | `gemini-omni-1.1-flash` | GENERATE / EDIT / EXTEND |

## Veo 请求与边界

实际 REST 为 `POST /v1beta/models/{model}:predictLongRunning`，`instances[0]` 接收 `prompt`、`image`、`lastFrame`、`referenceImages`、`video`；`parameters` 接收 `aspectRatio`、`durationSeconds`、`resolution`、`personGeneration`，文档另提 `seed`。与 landing 的 generateContent 概述措辞冲突时按详细 REST 示例执行。图片为原字节 Base64 与 MIME，不接受创作者任意 URL。

图片对象使用官方 SDK 的 mldev 转换 `{bytesBase64Encoded,mimeType}`，`referenceImages` 项为 `{referenceType:"asset",image:...}`；详细指南的部分 REST 例子改写为 `inlineData`，这两处官方表达并不一致。本客户端采用 SDK 的原生 REST 转换，不把 Gemini `generateContent` 的整个 inlineData 协议搬进 Veo。扩展 `video.uri` 只能从服务器保存的 Files 身份还原。[Google 官方转换源码](https://raw.githubusercontent.com/googleapis/python-genai/main/google/genai/models.py)

3.1：4/6/8 秒，16:9 或9:16；1080p/4k、参考图、扩展要求8秒。Lite 无4k、参考图和扩展。参考图最多3张；末帧必须搭配首帧。音频固定开启、单结果。扩展仅允许已有 Veo 生成视频，720p、最长141秒，返回原片与约7秒扩展的合并视频；不能把普通上传片伪装为 Veo 结果。[官方 Veo 约束](https://ai.google.dev/gemini-api/docs/veo#veo-api-parameters-and-specifications)、[扩展](https://ai.google.dev/gemini-api/docs/veo#extending-veo-videos)

| 创作字段 | 原生位置、范围与默认值 |
| --- | --- |
| `prompt` | `instances[0].prompt`；画布 Prompt 必填，应用上限 12,000 字符。文档模型 Token 上限不等于字符数，未调用远程 tokenizer。 |
| `first_frame` / `last_frame` | `image` / `lastFrame`；精确授权图片版本，末帧依赖首帧。 |
| `image_1…image_3` | 按已填槽顺序构造 `referenceImages`，仅非 Lite。 |
| `base_video` | 仅 EXTEND；精确授权归档视频，且同连接版本的 Veo Files 来源身份有效。 |
| `durationSeconds` | 整数 4/6/8，默认 8；来自画布时长。EXTEND 的原生参数为 8，实际新增片段约 7 秒。 |
| `aspectRatio` | 16:9（默认）/9:16。 |
| `resolution` | 720p（默认）/1080p/4k；Lite 无 4k，EXTEND 只允许 720p。 |
| `personGeneration` | 可省略；T2V 与 EXTEND 文档一般为 allow_all，图生、插值、参考图为 allow_adult；部分地区所有操作只允许 allow_adult。发送明确选择，地区拒绝原样归类。 |
| `negativePrompt` / `enhancePrompt` | 可省略的原生负面提示和布尔优化选项，官方 SDK mldev 映射支持；负面提示本地上限 12,000 字符。 |
| `seed` | 可省略的整数 0…4,294,967,295；指南说明 Veo 3 可用，但当前 Python SDK mldev 转换显式拒绝 seed。本 REST 接入保留原生字段，真实账号兼容性尚未验证，不能宣称已消除该官方资料差异。 |
| `sampleCount` | transport 固定 1；不开放供应商文档未支持的多视频输出。 |

图片本地字节限制 10 MiB；全部 inline 素材合计 60 MiB，均为应用传输预算。当前资产入口 PNG/JPEG/WebP 按原 MIME 发送并完整解码。Veo 扩展只接受实际 1280×720 或 720×1280、≤141 秒的归档 MP4；原始字节仍被授权、摘要与探测校验，不通过 URI 绕过来源验证。

## Gemini Omni 请求与边界

`POST /v1beta/interactions` 使用 model、input、response_format、generation_config.video_config。input 是文本或有序 image/video/text 内容；可发送裸 Base64。video_config.task 可省略由提示推断，或指定 text_to_video、image_to_video、reference_to_video、edit、extend。response_format.type=video，aspect_ratio=16:9/9:16；resolution=360p/720p/1080p/4k，后两档是上采样；duration 是字符串，模型输出3–10秒。[Interactions REST](https://ai.google.dev/api/interactions-api)、[Omni](https://ai.google.dev/gemini-api/docs/omni)、[模型边界](https://ai.google.dev/gemini-api/docs/models/gemini-omni-flash)

首尾帧为有序1–2图；上传片编辑/扩展≤10秒；参考视频最多3片、每片≤3秒且忽略音轨。当前 API 不接受音频引用，不支持负面提示单独字段、system instruction、temperature/top_p/stop sequence。官方图片参考示例达6张，但未声明更高硬上限；应用容量属于本地限制，不能称为供应商限制。跨多片推理有官方质量限制，不把参考片数量支持当作多片推理保证。地区限制由原生错误如实返回，不自动改写或降级请求。[Omni 输入与限制](https://ai.google.dev/gemini-api/docs/omni#limitations)

| 创作字段 | 原生位置、范围与默认值 |
| --- | --- |
| `prompt` | 最后的 text input；画布 Prompt 必填，应用上限 12,000 字符。 |
| `first_frame` / `last_frame` | GENERATE 的 source images；末帧依赖首帧。 |
| `image_1…image_6` | GENERATE / EDIT / EXTEND 的 reference images，本地容量 6。 |
| `video_1…video_3` | 三类操作均可使用的 reference videos，逐片≤3秒。 |
| `base_video` | EDIT / EXTEND 必填的一段 source video，≤10秒。 |
| `task` | GENERATE 可选 auto（默认，不发送该字段）/text_to_video/image_to_video/reference_to_video；EDIT / EXTEND 固定原生 edit/extend。显式任务与素材矛盾时拒绝。 |
| `aspect_ratio` | `response_format.aspect_ratio`：16:9（默认）/9:16。 |
| `resolution` | `response_format.resolution`：360p/720p（默认）/1080p/4k。 |
| `duration` | 原生字符串，可省略，原样发送；官方 schema 未给出规范拼写示例，不强行从画布整数拼出 `Ns`。模型输出范围 3–10 秒。 |
| `delivery` | inline/uri（默认 uri）；GET 恢复同时支持内联与 Files 结果。 |
| `background` | 布尔，默认 true；同步返回也保留 acknowledged interaction ID 再查询归档。 |
| `seed` / `max_output_tokens` | 可省略的 Interactions generation_config 整数；前者 signed 32-bit，后者正整数。本地不把通用 schema 的温度等字段当作 Omni 支持。 |
| `store` / `stream` | transport 固定 true / false，以保留原 ID 可恢复性；不开放 store=false 的不可查询尝试。 |

首尾帧和参考图统一按字段顺序构造有序 input，但角色分别保留。客户端自动加官方 source/reference prefix，Image/Video 的 1-based 位置对应实际发送列表，`IMAGE_REF_N` / `VIDEO_REF_N` 从 0 开始对应已填参考槽。比如只填 first_frame 与 image_5，会生成 `[# Sources <FIRST_FRAME>@Image1] [# References <IMAGE_REF_0>@Image2]`；结构化 mention 也使用这个参考编号。EDIT / EXTEND 的 base_video 是 source，额外视频仍是 reference。该声明避免任意槽序号被误当成原生媒体顺序；自由文字中的编号不自动重写。[官方角色标签](https://ai.google.dev/gemini-api/docs/omni#using-tags-in-prompts-to-set-image-and-video-roles)

当前单轮上传片扩展以≤10秒原片生成3–10秒继续片段，可返回约20秒合并结果；文档另述 previous_interaction_id 多轮能到40秒。本次不建立会话历史串链，不能把后者宣称为当前精确冻结上传片的范围。QuickTime 原 MIME 在 Interactions 中归一化为原生 video/mov；无自动转码。

Google GENERATE profile 的 `first_frame` 与 `last_frame` 允许指向同一精确 ArtifactVersion，表达循环首尾帧；授权 `mediaInputs` 及冻结图片来源只需该版本的一条记录。两个槽仍分别构造原生首帧/末帧角色，不把重复版本当成额外授权来源。Omni 官方循环示例也将 FIRST_FRAME 和 LAST_FRAME 指向同一 Image。此复用只开放这两个 GENERATE 槽，参考图、参考视频以及 EDIT / EXTEND 槽保持独立来源约束。[官方循环示例](https://ai.google.dev/gemini-api/docs/omni#using-tags-in-prompts-to-set-image-and-video-roles)

## 受理、恢复与归档

Veo 持久化完整 operation.name，GET `/v1beta/{name}`；done=true 时只解析 response.generateVideoResponse.generatedSamples[].video，错误终结。Omni 持久化 interaction.id，GET `/v1beta/interactions/{id}`；完成只读 steps 中 model_output 的 video，忽略 thought。Omni 后台执行需要 store=true；本次单轮调用固定冻结素材，不伪造 previous_interaction_id 会话。多轮需后续授权的精确历史身份设计。[后台执行](https://ai.google.dev/gemini-api/docs/background-execution)、[存储与状态](https://ai.google.dev/gemini-api/docs/interactions-overview#data-storage-and-retention)

Omni delivery=uri 的初次响应仍需缓存结果身份；官方说明 GET 可能返回 inline Base64，必须支持原 ID 的 inline 恢复。URI 结果等待 Files ACTIVE，再下载；失败/过期保留原 Task，绝不转为重新 POST。Veo 视频保留2天。Files 元数据包含 name、uri、downloadUri、state、expiry。下载只能在核验过的 Google Files 路径发送 Key；跳转目标须单独限制、跨主机不转发 Key；未知 CDN 不能凭域名后缀附加凭据。归档验证实际 MP4、时长及音轨，保留用户选择的原结果。[Omni URI](https://ai.google.dev/gemini-api/docs/omni#retrieving-videos-with-an-uri)、[Files REST](https://ai.google.dev/api/files)

本实现仅持久化 operation.name / interaction.id，不把 Base64 放进 Task。Veo operation 名允许官方 `operations/<opaque>` 与 `models/<同一模型>/operations/<opaque>`，两类操作名整体上限512字符，前缀与不透明尾段合计受此上限约束，查询返回身份必须完全相同。Omni 的 queued/in_progress 继续等待；completed 只接受一个 MP4 model_output；failed/cancelled/incomplete/requires_action 终结，不执行任何模型要求的动作。没有有效媒体或含多结果/模型身份变化时显式阻断。

下载精确为 `GET /v1beta/files/<id>:download?alt=media`，使用请求头 Key；此路径同时由 Omni REST 示例和官方 Files.download 源码确认。结果 URI 只解析固定 Google 主机、默认 HTTPS 端口、可识别 Files 路径与无凭据查询；不把签名 URI 公开保存。单跳重定向只允许精确 `storage.googleapis.com`，重新校验 DNS 并构造不含 Key/Authorization 的 GET；不跟随第二跳。单结果限制 100 MiB，响应 JSON 上限 145 MiB（为 inline Base64 预留），均为应用限制。[Files SDK 下载实现](https://raw.githubusercontent.com/googleapis/python-genai/main/google/genai/files.py)

Veo 归档 metadata 仅含 `{adapterId,model,connectionId,connectionVersion,fileName}`，fileName 为符合官方格式的 `files/<id>`，没有 scheme、query 或签名。EXTEND 先核对数据库不可变版本来源必须为 TASK，再逐项检查这五个字段和连接版本；用户正文中复制或伪造同样 metadata 不能赋予扩展来源身份。未知来源、上传视频或过期 Files 不可伪造扩展。凭据轮换后只有新生成结果符合新连接版本；历史 Task 查询仍使用原版本。

实际归档用 ffprobe 校验 MP4、单视频流、音轨和有效时长。普通 Veo 时长与冻结请求容差±1.5秒，扩展总长≤148.5秒；Omni GENERATE / EDIT≤10.5秒，当前上传扩展≤20.5秒。无效编码媒体永久阻断，本地工具不可用/超时与下载网络失败保留技术重试；每次重试查询同一原 ID。最终资产归档还执行既有完整解码与版本不变量。

提交 HTTP 4xx 明确拒绝与 429/5xx、断线、超时、无可确认 ID 区分。未知受理 UNKNOWN；原 ID 查询与下载恢复不重生成。费用只采用管理员估算与归档实际时长，未知价保持未知，不把估算视作供应商真实扣费。

## 双向切换与验证边界

与 Kling/Seedance/MiniMax/工作流视频互切，保留精确素材版本与可兼容标量；整理槽位后再保存。Veo Lite 移除不支持的引用；Omni 没有独立负面提示/音频上传；duration 草稿可保留，完整运行按目标模型约束阻断。扩展视频必须验证供应商来源身份，不接受仅相同文件格式作为证明。所有状态、素材、模型及连接版本在受理时冻结。

已添加 GoogleVideoModelsTest、GoogleVideoClientTest、GoogleVideoPreparedMediaTest、GoogleVideoAdapterTest 共30项 synthetic 单元用例，覆盖模型/操作目录、首尾帧复用声明、标量/媒体组合、角色顺序、严格 request/response、状态原 ID、512字符操作名真实 fake HTTP 创建/查询、UNKNOWN 无重发、下载路径、冻结输入、不可伪造版本来源、metadata 与技术重试。内联视频使用独立有界 JSON parser，最大 Base64 字符串按 100 MiB 视频换算，覆盖超过普通文本 parser 20M 上限的恢复；禁止重复键与多段 JSON。适配器单测的 MP4 header 与 ffprobe 都明确为合成/模拟，不能证明真实视频可解码；真实 FFmpeg 与 PostgreSQL 链路由专项 IT 验证，结果以开发清单记录为准。此研究代理未运行 Maven，真实 Google 尚未实测，未声明真实下载 CDN、模型参数组合、地区权限或费用已验收。
