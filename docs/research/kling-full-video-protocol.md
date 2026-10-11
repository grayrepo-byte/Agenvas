# 可灵视频完整协议调查

核实日期：2026-10-11。来源为用户指定的 `www.klingai.com` 官方文档，浏览器页面未跳转至 `kling.ai`。文档检索工具无法取得正文，以下通过官方页面加载后的可见 DOM 核对；没有读取应用内部状态、没有提交任务、没有使用真实凭据。本文是协议资料，不是实际供应商接通或计费验证。

## 官方目录与协议差异

当前视频目录列出 Kling 3.0 Turbo、Kling 3.0 & 3.0 Omni、Kling O1、Kling 2.6、Kling 2.5 Turbo、动作控制、多模态视频参考编辑、数字人、音频生成；展开“查看更多模型”另有对口型、图像识别。用户本次范围是全部当前图片/视频生成模型与参数，排除数字人、对口型、主体/音色 CRUD、独立音频、计费等旁支。视频能力图当前列出六个生成模型；本次不把没有列出的 v1/v1.5/v1.6/v2/master/v2.1 推断为仍在当前目录中。[官方目录](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)、[当前能力图](https://www.klingai.com/document-api/guides/capability-map/video)

新接口采用“操作/模型”路径、`settings` 与 `options`、统一 `/tasks` 查询；旧接口通过 `/v1/...` 和 `model_name` 定义能力，不能只替换模型名便认为参数兼容。新接口不同模型的输入和设置也有差异，不能将 3.0 的音频、尾帧或 4K 能力自动带到 Turbo。

| 模型/操作 | 已核实路径 | 主要差异 |
| --- | --- | --- |
| 3.0 Turbo 文生视频 | `POST /text-to-video/kling-3.0-turbo` | `prompt`；720p/1080p；3–15 秒整数；没有音频、multi_shot 设置 |
| 3.0 Turbo 图生视频 | `POST /image-to-video/kling-3.0-turbo` | `contents` 仅 prompt、first_frame；没有尾帧、音频设置或 4K |
| 3.0 文生视频 | `POST /text-to-video/kling-3.0` | prompt；音频 native/off，multi_shot，4K |
| 3.0 图生视频 | `POST /image-to-video/kling-3.0` | prompt、first_frame、last_frame、element；音频 native/off，multi_shot，4K |
| 3.0 Omni | `POST /omni-video/kling-3.0-omni` | prompt、首尾帧、参考图、特征视频、待编辑视频、主体；组合限制随输入改变 |
| O1 | `POST /omni-video/kling-o1` | 同类 contents，只有多图主体；3–10 秒，首帧独立输入限定 5/10 秒；没有 native 音频、多镜头或 4K 设置 |
| 2.6 图生视频 | `POST /image-to-video/kling-2.6` | 首尾帧、音色；5/10 秒；native 或尾帧要求 1080p |
| 2.6 文生视频 | `POST /text-to-video/kling-2.6` | prompt；5/10 秒；native 要求 1080p；文生接口未列音色 contents |
| 2.5 Turbo 图生视频 | `POST /image-to-video/kling-2.5-turbo` | 首尾帧；5/10 秒；尾帧要求 1080p；没有音频设置 |
| 2.5 Turbo 文生视频 | `POST /text-to-video/kling-2.5-turbo` | prompt；5/10 秒；没有音频设置 |
| 3.0 动作控制 | `POST /motion-control/kling-3.0` | image、video、可选 element；朝向 image/video；输出可能截取有效动作 |
| 2.6 动作控制 | `POST /motion-control/kling-2.6` | image、video；没有主体参考 |

逐项来源：[Turbo 文生](https://www.klingai.com/document-api/api/video/3-0-turbo/text-to-video)、[Turbo 图生](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)、[3.0 文生](https://www.klingai.com/document-api/api/video/3-0-omni/text-to-video)、[3.0 图生](https://www.klingai.com/document-api/api/video/3-0-omni/image-to-video)、[3.0 Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)、[O1](https://www.klingai.com/document-api/api/video/o1/video-omni)、[2.6 文生](https://www.klingai.com/document-api/api/video/2-6/text-to-video)、[2.6 图生](https://www.klingai.com/document-api/api/video/2-6/image-to-video)、[2.5 文生](https://www.klingai.com/document-api/api/video/2-5-turbo/text-to-video)、[2.5 图生](https://www.klingai.com/document-api/api/video/2-5-turbo/image-to-video)、[3.0 动作控制](https://www.klingai.com/document-api/api/video/motion-control)、[2.6 动作控制](https://www.klingai.com/document-api/api/video/motion-control/2-6)。

注意能力图展示名对应的旧 `model_name` 是 `kling-3.0-turbo`、`kling-v3`、`kling-v3-omni`、`kling-video-o1`、`kling-v2-6`、`kling-v2-5-turbo`；新路径 ID 则是表内的 `kling-3.0-turbo`、`kling-3.0`、`kling-3.0-omni`、`kling-o1`、`kling-2.6`、`kling-2.5-turbo`。不能混用这些名字。[模型信息](https://www.klingai.com/document-api/guides/capability-map/video)

## 鉴权与根地址

国内站 `www.klingai.com` 认证页明确根地址 `https://api-beijing.klingai.com`，适用于服务器位于中国地区；国际站 `kling.ai` 认证页已独立重新核对，根地址 `https://api-singapore.klingai.com`，适用于服务器位于中国境外。两页都称旧 `api.klingai.com` 已更换。`Authorization: Bearer <API Key>` 支持所有模型；AK/SK 只适用于旧设计。应按部署区域及凭据配置选择官方根地址，不能假定两站的凭据或额度可跨区域复用。[国内鉴权](https://www.klingai.com/document-api/api/get-started/authentication)、[国际鉴权](https://kling.ai/document-api/api/get-started/authentication)

## 3.0 Turbo

文生视频 `prompt` 必填，最多 3072 字符，建议不超过 2500；图生视频 `contents` 必填，支持 `{type:"prompt",text}`（该接口文本最多 2500 字符）与 `{type:"first_frame",url}`。图片支持 URL 或 Base64，jpg/jpeg/png、≤50MB、宽高各≥300px、宽高比 0.4–2.5；只支持首帧，禁止首尾帧及仅尾帧。[文生](https://www.klingai.com/document-api/api/video/3-0-turbo/text-to-video)、[图生](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)

`settings.resolution` 为 720p/1080p、默认 720p；`duration` 为整数 3–15、默认 5；仅文生有 `aspect_ratio`（16:9/9:16/1:1，默认 16:9）。没有 `audio` 或 `multi_shot` 参数。多镜头通过 Prompt 格式 `镜头 n, m, words; ...` 指定：1–6 镜头、每段至少 1 秒、时长之和等于总时长、每段文字最多 512 字符。[文生](https://www.klingai.com/document-api/api/video/3-0-turbo/text-to-video)、[图生](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)

`options` 可含 `callback_url`、账户内唯一 `external_task_id`、`watermark_info.enabled`（默认 false，不支持自定义水印）。自定义 ID 能用于查询，页面没有承诺重复提交幂等生成。[图生完整协议](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)

## 3.0 常规生成

文生请求 `{prompt,settings?,options?}`；图生请求 `{contents,settings?,options?}`。图生 `contents` 为 `{type:"prompt",text}`、必需首帧 `{type:"first_frame",url}`、可选尾帧 `{type:"last_frame",url}`、可选主体 `{type:"element",element_id,id}`；最多 3 个主体，`element_id` 是系统主体 ID，`id` 是本任务中唯一的索引，用 `@id` 引用。没有独立 `voice` 输入。图片要求和 Turbo 相同：URL/Base64、jpg/jpeg/png、50MB、各边≥300px、比例 0.4–2.5；只支持首帧或首尾帧，不支持仅尾帧。[3.0 文生](https://www.klingai.com/document-api/api/video/3-0-omni/text-to-video)、[3.0 图生](https://www.klingai.com/document-api/api/video/3-0-omni/image-to-video)

两接口提示词最多 3072 字符、建议≤2500。`settings`：multi_shot 为 boolean、默认 true；audio native/off、默认 off；resolution 720p/1080p/4k、默认 720p；duration 为整数 3–15、默认 5；仅文生有 aspect_ratio 16:9/9:16/1:1、默认 16:9。multi_shot=false 时即使使用分镜格式也不能生成多镜头。分镜 Prompt 语法及 1–6 段、每段≥1 秒、总和匹配 duration、每段≤512 字符与 Turbo 相同。options 与统一设计相同。[3.0 文生](https://www.klingai.com/document-api/api/video/3-0-omni/text-to-video)、[3.0 图生](https://www.klingai.com/document-api/api/video/3-0-omni/image-to-video)

## Omni 与 O1 输入结构

两模型请求都为 `{contents,settings?,options?}`，没有单独 text-to-video 或 edit-video 路径；纯文本、图片参考、视频特征参考与视频编辑通过同一 Omni 路径的输入组合定义。

| contents.type | 字段 | 含义 |
| --- | --- | --- |
| prompt | text 必填 | 提示词；3.0 Omni ≤3072（建议≤2500），O1 ≤2500 |
| first_frame / last_frame / refer_image | url 必填，id 可选 | 首帧、尾帧、场景/风格等参考图片；URL 或 Base64 |
| feature_video / base_video | url 必填，id 可选 | 特征参考视频或待编辑视频；只支持 URL |
| element | element_id、id 必填 | 主体库标识及本次 Prompt 中的索引 |

非 Prompt 素材的 id 用 `@id` 引用，任务内不得重复；官方建议不要使用互相包含的名称或与 Prompt 其他部分混淆的名称。图片要求统一为 jpg/jpeg/png、≤50MB、各边≥300px、比例 0.4–2.5。尾帧必须伴随首帧。最多一段视频；不应把普通视频参考与待编辑视频同时添加两段。[3.0 Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)、[O1](https://www.klingai.com/document-api/api/video/o1/video-omni)

### 3.0 Omni

settings：multi_shot boolean（默认 true）；audio native/original/off（默认 off，original 保留参考视频原声）；resolution 720p/1080p/4k（默认720p）；aspect_ratio 16:9/9:16/1:1（默认16:9）；duration 整数3–15（默认5）。该页原文写“当没有首帧图或没有参考视频时，当前参数必填”，与 O1 页的“且”不同，应保留文档歧义，不声称两者条件相同。分镜 Prompt 规则与3.0相同。[3.0 Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)

参考视频 mp4/mov、≤200MB、时长3–15.5秒（含边界）、宽高各700–4553px（含边界）、总像素≤8,294,400、宽高比0.4–2、帧率24–60fps；生成视频帧率24fps。feature_video 不支持音画同出，因此 audio=off；页面说生成多镜头视频时 multi_shot 只能为 true。base_video 禁止首尾帧、禁止多镜头、禁止 native 音频，可用 original 保留原声。[3.0 Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)

主体有 video_character_elements（视频角色主体）与 multi_image_elements（多图主体）两类，数量不能按统一 max7 验证：

| 输入组合 | 限制 |
| --- | --- |
| 首帧或首尾帧生成 | 主体最多3个 |
| 无参考视频，仅多图主体 | 参考图片+多图主体之和≤7 |
| 无参考视频，仅视频角色主体 | 视频角色主体≤3 |
| 无参考视频，两类主体并存 | 视频角色主体≤3，参考图片+多图主体之和≤4 |
| 有参考视频，仅多图主体 | 参考图片+多图主体之和≤4 |
| 有参考视频，仅视频角色主体 | 视频角色主体≤1；不能同时有参考图片 |
| 有参考视频，两类主体并存 | 禁止 |

素材类型只能由真实主体元数据确定，单独 element_id 字符串不能证明它属于哪类；本次排除主体管理 UI 不等于可以忽略该服务端约束。[3.0 Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)

### O1

settings：audio original/off（默认off），resolution720p/1080p（默认720p），aspect_ratio16:9/9:16/1:1（默认16:9），duration整数3–10（默认5）；无 multi_shot、native 或4K。没有首帧且没有参考视频时 aspect_ratio 必填。只有首帧、没有 refer_image/feature_video/base_video 时，duration 仅5或10秒。能力图还把 O1 纯文字单镜头限制为5/10秒（详见文档冲突节）。[O1](https://www.klingai.com/document-api/api/video/o1/video-omni)

参考视频 mp4/mov、≤200MB、3–10秒（含边界）、各边700–2160px、24–60fps；生成24fps。O1 页面没有给出3.0 Omni的像素面积和比例限制，不能擅自挪用。feature_video 仅支持首帧、不支持尾帧；base_video 禁首尾帧。O1 只支持多图主体，不支持视频角色主体。无视频时图片+主体≤7，有视频时≤4；首尾帧时不允许其他参考图或主体。[O1](https://www.klingai.com/document-api/api/video/o1/video-omni)

## 2.6 与 2.5 Turbo

文生为 `{prompt,settings?,options?}`，prompt≤2500；两模型 settings 都有 resolution720p/1080p（默认720p）、aspect_ratio16:9/9:16/1:1（默认16:9）、duration整数5/10（默认5）。2.6另有audio native/off（默认off），native要求1080p；2.5 Turbo无audio设置，也没有multi_shot。不能根据能力图的2.6通用“3–10秒”覆盖该文生接口明确5/10的枚举。[2.6文生](https://www.klingai.com/document-api/api/video/2-6/text-to-video)、[2.5文生](https://www.klingai.com/document-api/api/video/2-5-turbo/text-to-video)

图生为 `{contents,settings?,options?}`，contents含prompt（text≤2500）、first_frame（url必填）、可选last_frame；2.6另支持voice，2.5没有voice。图片要求相同：jpg/jpeg/png、≤50MB、各边≥300px、比例0.4–2.5、URL/Base64。禁止仅尾帧；首尾帧只能1080p。图生settings没有aspect_ratio，其他分辨率/时长与文生相同。2.6 native音频只能1080p，voice引用时不能audio=off。voice为 `{type:"voice",voice_id,id}`，两个ID都必填，本任务id唯一；最多2音色，通过Prompt `@id`指定。[2.6图生](https://www.klingai.com/document-api/api/video/2-6/image-to-video)、[2.5图生](https://www.klingai.com/document-api/api/video/2-5-turbo/image-to-video)

2.6能力图额外说首尾帧“仅1080P且仅无声视频”，图生API页只写1080p；实施需明确采用保守交集 tail→1080p/off，或先取得官方进一步说明，不把页面缺省文字当作允许的证据。[2.6图生](https://www.klingai.com/document-api/api/video/2-6/image-to-video)、[能力图](https://www.klingai.com/document-api/guides/capability-map/video)

## 动作控制 3.0 / 2.6

请求 `{contents,settings,options?}`：contents为prompt（text≤2500）、image（形象参考图url）、video（动作参考视频url）；3.0另可加element（element_id、id必填）最多1个，引用主体时character_orientation必须video。2.6没有element。图片支持URL/Base64，jpg/jpeg/png、≤50MB、各边≥300px、比例0.4–2.5。[3.0动作](https://www.klingai.com/document-api/api/video/motion-control)、[2.6动作](https://www.klingai.com/document-api/api/video/motion-control/2-6)

3.0页面把 element 描述为“可辅助提升主体一致性”，没有声明它可以替代 image；实施保留形象图与动作视频必需、主体可选。各新图生接口把 prompt 条目内部的 text 标为必填，但没有单独声明 prompt 条目能否省略；不能仅凭内部字段的必填标记推断整项必填。普通图生本地仍要求用户提供提示词；动作控制允许省略提示词。[3.0动作](https://www.klingai.com/document-api/api/video/motion-control)、[3.0图生](https://www.klingai.com/document-api/api/video/3-0-omni/image-to-video)

settings.character_orientation 必填，image/video分别跟随图片或视频朝向；audio为original/off、默认original；resolution720p/1080p、默认720p。没有duration/aspect_ratio/multi_shot。动作参考视频仅URL，mp4/mov、≤100MB、各边340–3850px；至少3秒，orientation=image时最多10秒、video时最多30秒。该页没有帧率参数要求，不能挪用Omni的24–60fps限制。[3.0动作](https://www.klingai.com/document-api/api/video/motion-control)、[2.6动作](https://www.klingai.com/document-api/api/video/motion-control/2-6)

动作输入需角色完整、避免遮挡，推荐单人单镜头、避免切镜/快速动作。模型可能仅截取有效连续动作（至少3秒），因此输出可短于素材；不要套用普通生成“输出必须等于请求时长”的归档断言。费用以输出时长计算不代表事前精确报价。[3.0动作](https://www.klingai.com/document-api/api/video/motion-control)、[2.6动作](https://www.klingai.com/document-api/api/video/motion-control/2-6)

## 文档冲突与本地验证边界

1. Turbo接口没有audio设置，但能力图的文生/图生音画同出都有支持图标。应表达“音频不可配置”，不能声称Turbo一定无声或拒绝正常音轨；实际是否始终带音轨未实测。
2. O1能力图纯文本生成只支持5/10秒，而Omni接口通用duration为3–10；只有首帧无其他图/视频的限制也为5/10。纯文本采用5/10是两份文档的保守交集。
3. 2.6首尾帧无声限定在能力图，API页只写1080p；保守交集为1080p/off。
4. 3.0Omni aspect_ratio必填条件用“或”，O1用“且”；不能无依据解释为相同条件。
5. 单独主体/音色ID不带类型元数据。若本地没有可信主体类型，不能声称已验证依赖类型的组合；供应商明确拒绝与UNKNOWN仍需分别处理。
6. Omni3/O1 将 first_frame、last_frame、refer_image 放在共同的图片素材段落，再描述“参考图片与主体数量之和”限制，没有声明首尾帧可以豁免。当前实现采用保守解释：7/4 数量上限计入所有图片 contents，包括首帧、尾帧和参考图，另行检查首尾帧的组合限制。重新检查当前能力地图也没有更明确的数量表。极限组合尚未实测，不能因此标记全部素材组合已验收。

来源：[能力图](https://www.klingai.com/document-api/guides/capability-map/video)、[Turbo](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)、[O1](https://www.klingai.com/document-api/api/video/o1/video-omni)、[2.6](https://www.klingai.com/document-api/api/video/2-6/image-to-video)、[3.0Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)。

Omni3/O1 的三类图片素材都允许 `id:string` 选填，可通过 `@id` 在 Prompt 引用。常规图生的首尾帧与动作控制的 image/video 素材没有此字段；不能把 Omni 的索引参数透传到这些不同输入结构。新视频 element_id 是 string，图片旧协议的 element_list.element_id 是 long，两者保持各自 JSON 类型。[3.0Omni](https://www.klingai.com/document-api/api/video/3-0-omni/video-omni)、[O1](https://www.klingai.com/document-api/api/video/o1/video-omni)、[3.0图生](https://www.klingai.com/document-api/api/video/3-0-omni/image-to-video)、[3.0动作](https://www.klingai.com/document-api/api/video/motion-control)

当前目录没有独立视频延长/extend生成操作。旧I2V页仍给`POST /v1/videos/image2video`，model_name当前枚举仅kling-v2-5-turbo/kling-v2-6/kling-v3，采用旧顶层image/image_tail、mode、sound、duration字符串、negative_prompt、multi_prompt等；这些不是新请求的兼容字段。旧页泛型材料与能力地图须一起判断，不能从泛型duration3–15推断2.5支持任意时长。[旧I2V](https://www.klingai.com/document-api/api/video/2-5-turbo/image-to-video/legacy)

多模态参考编辑 `/v1/videos/multi-elements` 需要先初始化session、标记/增删/预览选区，再提交addition/swap/removal；这是与模型版本无关的独立编辑工作流，并非当前六个生成模型的参数，按用户排除旁支范围单列。Omni中的base_video编辑输入仍属于当前生成合同。[独立多模态编辑](https://www.klingai.com/document-api/api/video/multi-elements)、[能力地图](https://www.klingai.com/document-api/guides/capability-map/video)

## 统一异步任务

新接口提交 `code=0` 后，`data` 含 `id`、`status`、`create_time`、`update_time`、`external_id`。状态 submitted/processing/succeeded/failed。`GET /tasks` 查询参数 `task_ids` 或 `external_task_ids` 至少且只能一种；多个 ID 用逗号分隔。查询 `data` 为数组，必须匹配原任务 ID；视频输出 `{type:"video",id,url,watermark_url,duration:"秒数"}`。媒体 30 天后清理。通用输出枚举还包括 image/audio/voice/element，不表示每个模型都会产生这些结果。[统一查询](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)

`POST /tasks` 是游标查询，绝不是生成：start_time/end_time 为毫秒，默认过去 30 天至当前时间；cursor 来自 next_cursor，非空时覆盖时间筛选；limit 默认 100、最大 500；filters 的 key 可为 status 或 product_type，values 支持状态 submitted/processing/succeeded/failed，以及产品 video/image/try_on。返回 `data.result`、count、next_cursor、has_more。[游标查询](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)

受理响应丢失/未知时保持 UNKNOWN，不因外部 ID 自动再次生成；归档失败只查询和下载原 ID。本文没有确认取消或退款保证。实际输出 CDN 域名未验证，不应通过通用 URL 字段放宽既有下载安全策略。

官方明确业务错误：1000–1004鉴权（HTTP401）；1100–1102账户/余额/额度（HTTP429）、1103无权限（403）；1200–1201参数（400）、1202方法/1203资源（404）；1300–1301平台/内容策略（400）、1302速率/1303并发/1304IP白名单（429）；5000内部（500）、5001维护（503）、5002超时（504）。服务端收到明确拒绝和没能确认受理不同；不能因官方“稍后重试”建议把5xx提交自动变成再次生成。查询鉴权失败可标记凭据阻塞，仍保留原任务ID。[当前错误码](https://www.klingai.com/document-api/api/get-started/error-codes)

验证说明：已逐项读取上述新生成接口、当前能力地图、国内及国际鉴权、旧I2V范围和错误码。研究文件的Markdown差异检查已运行；Maven未运行；真实Provider任务、实际输出CDN、地区额度互通及计费/退款均未验证。
