# 万相、Seedream 与 Google 原生媒体接入

本轮增加当前主力图片/视频生成协议，按固定 model/operation 发布能力。供应商详细契约及官方来源分别记录于[万相研究](research/wan-generation-protocol.md)、[Seedream 研究](research/seedream-image-protocol.md)、[Google 视频研究](research/google-video-protocol.md)，共同决策见 [ADR 0044](adr/0044-native-media-generation-protocols.md)。本轮未发起真实生成或付费调用，也未部署。

## 发布目录

服务端 `GET /api/v1/settings/media-profiles` 返回十九个 profile 及完整 creator 字段。能力设置固定 `model`、`operation`，服务器派生只读 `nativeInputs`；默认参数仅采用该 profile 声明的字段，不能提供任意请求 body、任意模型或外部素材 URL。

| 平台 / 适配器 | Model ID | Operation | 输出 |
| --- | --- | --- | --- |
| WAN / WAN_IMAGE | `wan2.7-image`、`wan2.7-image-pro` | GENERATE，各一个 | 图片，单次普通1–4或组图最多12 |
| WAN / WAN_VIDEO | `wan3.0-video`、`wan3.0-video-prime` | GENERATE，各一个 | 单视频 |
| ARK / ARK_SEEDREAM_IMAGE | `doubao-seedream-5-0-pro-260628` | GENERATE / LAYER_DECOMPOSITION / INTERACTIVE_EDIT | 单图或图层最多17 |
| ARK / ARK_SEEDREAM_IMAGE | `doubao-seedream-5-0-flash-260915` | 同 Pro | 单图或图层最多17 |
| ARK / ARK_SEEDREAM_IMAGE | `doubao-seedream-5-0-260128` | GENERATE | 单图或组图最多15，受参考数量收紧 |
| GOOGLE / GOOGLE_VEO | `veo-3.1-generate-preview`、`veo-3.1-fast-generate-preview` | GENERATE / EXTEND，各两个 | 单含音轨视频 |
| GOOGLE / GOOGLE_VEO | `veo-3.1-lite-generate-preview` | GENERATE | 单含音轨视频 |
| GOOGLE / GOOGLE_OMNI_VIDEO | `gemini-omni-1.1-flash` | GENERATE / EDIT / EXTEND | 单含音轨视频 |

Seedream Lite alias 不重复发布，4.x 与即将下线模型只在研究中记录；Veo 3.0 已弃用、Omni preview 与 GA 重复，均不加入本轮发布目录。历史能力和任务不随目录选择自动换模型。

## 连接与原生协议

| 适配器 | 地址与凭据 | 执行与恢复 |
| --- | --- | --- |
| WAN_IMAGE / WAN_VIDEO | `https://{WorkspaceId}.{region}.maas.aliyuncs.com`；区域 API Key Bearer。图片北京/新加坡，视频另准入东京/法兰克福/弗吉尼亚/香港。 | POST 图片 generation / 视频 video-synthesis，固定异步头；GET `/api/v1/tasks/{task_id}`。 |
| ARK_SEEDREAM_IMAGE | `https://ark.cn-beijing.volces.com`；沿用 ARK API Key Bearer。 | POST `/api/v3/images/generations` 同步获得完整结果清单；没有远程任务查询。 |
| GOOGLE_VEO | 地址可留空，默认固定 `https://generativelanguage.googleapis.com`；沿用 GOOGLE API Key 请求头。 | POST `/v1beta/models/{model}:predictLongRunning`；GET 原完整 operation.name。 |
| GOOGLE_OMNI_VIDEO | 同官方 Google 根地址；普通图像代理地址不可作为视频下载 origin。 | POST `/v1beta/interactions`；固定 store=true、stream=false；GET 原 interaction.id，支持 inline 恢复。 |

视频/图片 API Key 只在服务器加密连接版本保存，不能进入浏览器、素材 URL、Task、Prompt、SSE、导出或 debug 正文。连接根地址与模型区域在发布时校验，账号开通情况仍由供应商实际调用确认。

## 创作参数与素材

原生标量和媒体槽使用 `parameters.dynamicValues`。Prompt 取画布 Prompt，时长仅在 `nativeInputs` 声明 DURATION_SECONDS 时取画布时长；媒体槽为精确 ArtifactVersion UUID，附属 images/videos/audios 数组保留来源 artifact/version 与角色。图片为 REFERENCE，视频为 VIDEO_REFERENCE，音频为 AUDIO_REFERENCE；不接受 creator 输入 URL 或 data URI。

| Profile | 参数与关键组合 |
| --- | --- |
| Wan 图片 | size、n、enable_sequential、thinking_mode、watermark、seed；9参考图，每图2矩形框，3–10颜色及精确百分比；pro仅无图非组图允许4K，组图禁止配色。稀疏槽按实际填入顺序紧凑编号。 |
| Wan 视频 | duration、smart_duration、resolution、ratio、audio、prompt_extend、watermark、seed；首帧/尾帧互斥其他参考；参考图片≤10、视频≤5、音频≤5。视频/音频各总≤15秒，指定时长时输入视频+输出≤30秒。 |
| Seedream 普通/交互 | size、output_format、optimize_prompt_mode、background、watermark；Lite有sequential/max_images/web_search。交互点/框使用有限 edit_regions JSON，服务器转换官方 prompt 标记。透明背景需一张实际带alpha的参考图与PNG输出。 |
| Seedream 拆层 | 固定layer_decomposition，一张原图，size auto/1K/1.5K/2K；底图及逐层z_index/name/description/bbox随结果保存，不丢弃布局信息。 |
| Veo 普通生成 | durationSeconds4/6/8、aspectRatio、resolution、personGeneration及可选原生negativePrompt/enhancePrompt/seed；首尾帧、非Lite最多3参考图；1080p/4k/参考图固定8秒。 |
| Veo 扩展 | 同连接版本的Veo归档Files身份必填；原片720p、≤141秒，原生durationSeconds8、输出新增约7秒。普通上传片不可替代。 |
| Omni 三操作 | aspect_ratio、resolution、原生字符串duration、delivery、background、seed/max_output_tokens；生成可auto或指定task，编辑/扩展必填≤10秒base_video，可附6张参考图和3段≤3秒参考视频。角色prefix按实际顺序构造，参考编号从0开始。 |

应用沿用单节点最多14条精确素材的总上限；Wan混合参考即使各类槽分别允许10/5/5，也不能同时装满20条。Google复用首尾帧只计一条授权素材。本地图片导入支持PNG/JPEG/WebP，Wan原生BMP输入尚不能由本地素材入口提供。

全部精确素材读取原始资产，校验授权、摘要、实际格式、尺寸与时长；所需本地解码或 ffprobe 在任务预检及提交准备执行。Wan 视频/音频通过冻结中转配置签名，Google 素材使用 inline 原字节，Seedream 使用 data URI。签名、上传、生成、查询和下载都不持有数据库事务。真实原生字段、默认值、格式上限与各模型组合以研究文档和编译契约为准；未证实的极限组合不宣称验收完成。

Google GENERATE 的 first_frame / last_frame 可复用同一精确图片版本，授权 mediaInputs 与冻结图片来源只保存一条该版本记录，两槽分别发送首帧与末帧角色。此复用不适用于参考槽、EDIT 或 EXTEND；其他重复来源仍按普通槽位约束校验。

## 冻结、结果与重试

每次受理固定四字段 `providerParameters.native={adapterId,model,operation,values}`；values 与冻结 dynamicValues 相同，适配器同时核对固定模型/操作、能力/连接版本和 mapping hash。价格、素材版本、输入视频秒数与自动数量语义一同固定。密钥轮换、管理员更新模型或创作者继续编辑不能改变历史尝试。

Wan 持久化原task_id，Google 持久化原operation/interaction身份。Seedream 在一次同步响应后持久化完整内部不可变清单，Base64仅用于内部恢复，不公开到普通Task内容。同步没有远程查询，不将本地requestKey假称为远程ID。受理不明保持UNKNOWN，不能自动补发收费请求；查询/下载/归档技术重试继续同一身份。供应商明确拒绝、任务失效、永久结果协议错误分别终结或阻断，不能偷偷降级模型或修改参数。

组图与图层由一个任务归档全部结果，provider结果身份固定，主结果及额外节点均有不可变版本。序列数量和拆层上限是估算上界，实际数量用于结算。视频按实际归档探测秒数估算，Wan加上冻结输入参考视频秒数；未知数量/时长/价保持未知。应用估算不能代表供应商实际扣费。

结果下载策略分别为Wan精确官方结果桶、Seedream内联Base64、Google固定官方Files路径。Google只有该固定路径发送Key，单跳精确GCS主机重新校验DNS且不转发凭据；结果URI/签名不保存到公开产物。Veo扩展要求数据库不可变TASK版本来源，metadata仅为adapterId/model/connectionId/connectionVersion/fileName五字段，fileName为安全`files/<id>`；用户复制metadata不能赋予扩展身份。过期下载仅刷新或查询原身份；没有原结果就阻断，不重生成。

所有结果归档仍执行文件格式、完整解码与资产版本规则。Google另检查MP4、音轨、时长和单视频流；模型private thought及原始媒体Base64不进入公开记录或debug正文。

## 切换与交付边界

与现有Kling、Seedance、MiniMax、普通图片及RunningHub/ComfyUI能力双向切换，保留节点、历史结果、兼容标量及精确素材。按目标声明重排可兼容槽，删除不存在字段/素材时同步清理结构化标签和来源连线。保存冲突或失败保留草稿；后台能力约束变化只产生运行不兼容，不覆盖草稿。Source时长草稿可保留，但普通运行需通过目标原生限制；Omni与Wan自动时长不带不适用的旧durationSeconds。

V21增加WAN连接平台约束，ARK/GOOGLE沿用既有连接；V22将Task、ProviderAttempt、CallLog的provider_request_id统一扩为512字符，完整保存新协议允许的原ID；V23只允许NATIVE_MEDIA内部结果清单最多17项，其他协议仍为16项，支持Seedream拆层而不放宽既有协议。OpenAPI新增只读目录与CreatorInputField/nativeInputs，生成TypeScript与jOOQ同步；运行数据继续使用既有版本化JSON与资产归档。

验证按模块覆盖fake HTTP、原生参数/状态/失败、精确冻结、UNKNOWN无重发、清单恢复、实际合成文件归档和真实PostgreSQL。具体已运行命令/计数以开发清单为准；本说明不把已保存的测试等同于执行通过。真实供应商、付费API、账号区域与全部极限组合未实测。Wan其他区域若返回未确认的新结果桶会被当前严格下载策略阻断；Google Veo seed及图片对象的官方资料差异见研究记录。
