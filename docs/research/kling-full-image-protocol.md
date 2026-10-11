# 可灵图片生成协议全目录

核对日期：2026-10-11。依据可灵官方中文文档的当前页面 DOM；未发送生成请求，未验证真实账号权限、计费、返回素材或文档冲突的实际行为。本记录覆盖图片生成模型和生成参数，主体库 CRUD、独立音频与账单接口不属于本次接入范围。

## 1. 当前模型、协议与端点

官方当前图片能力地图列出四个模型；未将 V1、V1.5、V2 等历史模型列为当前可选模型。普通生成页遗留 V1 默认兼容说明，与当前 `model_name` 默认值冲突，不能据此扩展当前模型目录。[官方图片能力地图](https://www.klingai.com/document-api/guides/capability-map/image)、[普通图片生成](https://www.klingai.com/document-api/api/image/3-0-omni/image-generation)

| 模型 ID | 生成请求 | 查询任务 | 分辨率 | 图片参考与额外限制 |
| --- | --- | --- | --- | --- |
| `kling-v3` | `POST /v1/images/generations` | `GET /v1/images/generations/{id}` | `1k`, `2k` | 可选单 `image`；可用 `element_list`；无 `image_reference` / fidelity / 系列图 |
| `kling-v2-1` | `POST /v1/images/generations` | 同上 | `1k`, `2k` | 可选单 `image`，支持 `image_reference=subject/face`、fidelity；不支持主体库引用 |
| `kling-v2-1` 多图参考 | `POST /v1/images/multi-image2image` | `GET /v1/images/multi-image2image/{id}` | 请求字段未提供 resolution | `subject_image_list` 1–4 张，另可选场景图和风格图 |
| `kling-image-o1` | `POST /v1/images/omni-image` | `GET /v1/images/omni-image/{id}` | `1k`, `2k` | `image_list` 与 `element_list` 合计 ≤10；支持比例 `auto`；能力地图不支持 `series` |
| `kling-v3-omni` | 同上 | 同上 | `1k`, `2k`, `4k` | 同上；支持系列图；纯文生图场景能力地图注明不支持比例 `auto` |

来源：[普通生成请求](https://www.klingai.com/document-api/api/image/2-1/image-generation)、[2.1 多图生成](https://www.klingai.com/document-api/api/image/2-1/multi-image-to-image)、[Omni 生成](https://www.klingai.com/document-api/api/image/3-0-omni/image-omni)、[O1 生成](https://www.klingai.com/document-api/api/image/o1/image-generation)、[能力地图](https://www.klingai.com/document-api/guides/capability-map/image)。O1 与 3.0 Omni 文档显示相同的 `/v1/images/omni-image` 字段表。

另外两个不区分模型版本的生成端点：扩图 `POST /v1/images/editing/expand`，主体参考补全 `POST /v1/general/ai-multi-shot`；都没有 `model_name`。它们可作为独立生成操作接入，不应虚构模型 ID。[扩图](https://www.klingai.com/document-api/api/image/common/outpainting)、[智能补全主体图](https://www.klingai.com/document-api/api/image/common/subject-completion)

图片接口目前均采用旧版平铺请求字段与 `task_id` / `task_status` 响应；不能套用新版视频的 `contents`、`settings`、`options` 及 `data.id` / `data.status`。

## 2. 鉴权与地区

当前官方鉴权页支持 **API Key 用于所有模型**，请求头 `Authorization: Bearer <API Key>`。AK/SK 生成 JWT 仅适用于旧版 API；官方用“型号在路径中”为新版、`model_name` 在请求体中为旧版进行区分。图片接口虽是旧版设计，仍可统一使用 API Key。[接口鉴权](https://www.klingai.com/document-api/api/get-started/authentication)

中文官方页面调用根为 `https://api-beijing.klingai.com`，注明适用于服务器在中国地区的用户，且由旧 `api.klingai.com` 迁移。前轮英文文档记录的 Singapore 根与此属于地区差异，不能宣称北京官方地址无效，也不能把地区选择当任意 URL 输入。[接口鉴权](https://www.klingai.com/document-api/api/get-started/authentication)

## 3. 普通图片 `/v1/images/generations`

字段表为当前 `kling-v3` 和 `kling-v2-1` 共用，必须按模型能力进一步收窄参数。[字段来源](https://www.klingai.com/document-api/api/image/3-0-omni/image-generation)、[能力限制](https://www.klingai.com/document-api/guides/capability-map/image)

| 字段 | 类型、默认与边界 |
| --- | --- |
| `model_name` | 可选 string，表中默认 `kling-v3`；枚举 `kling-v2-1`, `kling-v3`；应显式发送 |
| `prompt` | 必填 string，≤2500 字符 |
| `negative_prompt` | 可选 string，≤2500 字符；`image` 非空时不支持 |
| `image` | 可选单张 URL 或裸 Base64；`image_reference` 非空时必填 |
| `image_reference` | 可选 `subject` / `face`；仅 2.1 的能力地图标支持；`face` 需要图片只有一张人脸 |
| `image_fidelity` | 可选 float [0,1]，默认 0.5；仅 2.1 |
| `human_fidelity` | 可选 float [0,1]，默认 0.45；仅 2.1，且 `image_reference=subject` 时有效 |
| `element_list` | 可选 array `[{"element_id": long}]`；仅 V3 的能力地图标支持；主体数与参考图片数合计 ≤10 |
| `resolution` | 可选 `1k` / `2k`，默认 `1k` |
| `n` | 可选 int [1,9]，默认 1；这是图片输出数量，不是参考数量 |
| `aspect_ratio` | 可选八种比例，默认 `16:9`；不含 `auto` |
| `watermark_info` | 可选 `{"enabled": boolean}`；是否生成水印伴随结果，无自定义水印 |
| `callback_url` | 可选 string；状态变更回调 |
| `external_task_id` | 可选 string；账号内唯一，不能覆盖服务端任务 ID |

八种比例：`16:9`, `9:16`, `1:1`, `4:3`, `3:4`, `3:2`, `2:3`, `21:9`。

输入图片：JPG/JPEG/PNG，单文件 ≤10MB，宽和高均 ≥300px，宽高比在 0.4–2.5；裸 Base64 不包含 `data:image/...;base64,`。使用 Base64 时所有传入图像数据均采用 Base64，而不是混用 URL。[输入约束](https://www.klingai.com/document-api/api/image/2-1/image-generation)

## 4. 多图参考 `/v1/images/multi-image2image`

这不是普通生成的 `image_list` 扩展，需单独序列化。字段表没有 `negative_prompt`、`resolution`、fidelity、主体库引用。[官方多图接口](https://www.klingai.com/document-api/api/image/2-1/multi-image-to-image)

| 字段 | 类型、默认与边界 |
| --- | --- |
| `model_name` | 可选 string，仅 `kling-v2-1`，默认该值 |
| `prompt` | 可选 string ≤2500 字符 |
| `subject_image_list` | 必填 array，1–4 个 `{"subject_image": string}`；API 不裁剪，上传已经选定主体的图像 |
| `scene_image` | 可选单张场景参考图 |
| `style_image` | 可选单张风格参考图 |
| `n` | int [1,9]，默认 1 |
| `aspect_ratio` | 八种比例，默认 `16:9` |
| `watermark_info` / `callback_url` / `external_task_id` | 与普通生成相同 |

三类图片字段分别支持 URL 或裸 Base64；各张 JPG/JPEG/PNG ≤10MB、宽高 ≥300px、比例 0.4–2.5。主体最多四张，场景和风格各一张，总输入最多六张。请求示例含空 `negative_prompt`，但字段表未定义，不能根据示例承诺其有效。[官方多图接口](https://www.klingai.com/document-api/api/image/2-1/multi-image-to-image)

## 5. Omni `/v1/images/omni-image`

编辑、风格迁移、细节修改与融合通过此接口的文本和参考图表达；当前 O1/3.0 Omni 导航没有独立 mask/inpainting 字段或独立编辑端点。[Omni 字段](https://www.klingai.com/document-api/api/image/3-0-omni/image-omni)、[O1 字段](https://www.klingai.com/document-api/api/image/o1/image-generation)

| 字段 | 类型、默认与边界 |
| --- | --- |
| `model_name` | 可选 string，默认 `kling-image-o1`，枚举 O1 / V3 Omni；应显式发送 |
| `prompt` | 必填 string ≤2500；正负意图写在同一字符串，无独立 negative_prompt |
| `image_list` | 可选 array `[{"image": string}]`，值不得为空；URL 或裸 Base64 |
| `element_list` | 可选 array `[{"element_id": long}]`，已有主体库 ID |
| `resolution` | 默认 `1k`；O1 限 `1k`/`2k`，V3 Omni 另支持 `4k` |
| `result_type` | 默认 `single`；V3 Omni 可选 `series`；O1 能力地图不支持 series |
| `n` | int [1,9]，默认 1；series 时忽略 |
| `series_amount` | 默认 4，枚举数字 2–9 或 string `auto`；single 时忽略；auto 占用实际出图数对应并发 |
| `aspect_ratio` | 八种比例加 `auto`，默认 `auto`；V3 Omni 纯文生图能力图注明不支持 auto |
| `watermark_info` / `callback_url` / `external_task_id` | 与普通生成相同 |

`image_list` 每张 JPG/JPEG/PNG ≤10MB、宽高 ≥300px、比例 0.4–2.5；图片数与主体数之和 ≤10。图片标签字段说明是 `<<<image_1>>>`，示例又使用 `<<<object_1>>>` 和 `<<image_1>>` / `<<element_1>>`，存在文档歧义；不要自动改写用户标签。`series_amount` 表头标 int，但枚举包含 string `auto`，JSON 应建模为受限联合类型。[Omni 字段与示例](https://www.klingai.com/document-api/api/image/3-0-omni/image-omni)

## 6. 查询、结果与恢复

四类生成均 POST 到基础路径，单个 GET 到同基础路径 `/{id}`，列表 GET 基础路径并带 `pageNum` [1,1000]（默认1）、`pageSize` [1,500]（默认30）。文档称服务端 `task_id` 与 `external_task_id` 二选一查询，但只给出 `/{id}` 示例，未明确自定义 ID 的额外编码；恢复应优先使用受理后持久化的服务端 task_id。[普通查询](https://www.klingai.com/document-api/api/image/3-0-omni/image-generation)、[多图查询](https://www.klingai.com/document-api/api/image/2-1/multi-image-to-image)、[Omni 查询](https://www.klingai.com/document-api/api/image/3-0-omni/image-omni)、[扩图查询](https://www.klingai.com/document-api/api/image/common/outpainting)

共同信封：`code`, `message`, `request_id`, `data`。受理 `data` 含 `task_id`, `task_status`, `task_info.external_task_id`, `created_at`, `updated_at`（ms）。状态严格为 `submitted`, `processing`, `succeed`, `failed`。查询增加 `task_status_msg`, `task_result`，也可能返回扣减信息，不影响媒体结果解析。

- 普通、多图与扩图：`task_result.images[]` 含 `index`, `url`；支持水印时伴随 `watermark_url`。
- Omni：`task_result.result_type` 决定 `images[]` 或 `series_images[]`，条目同样是 `index`, `url`, `watermark_url`。不能混合两个数组重复归档。
- 主体补全：`task_result.images[]` 结构不同，条目是 `index`（0–2）与 `url_1`, `url_2`, `url_3`；需独立结果展开，不是普通 `url`。
- 所有官方示例提示生成结果 30 天后清理，应及时归档。[普通结果](https://www.klingai.com/document-api/api/image/3-0-omni/image-generation)、[Omni 结果](https://www.klingai.com/document-api/api/image/3-0-omni/image-omni)、[主体补全结果](https://www.klingai.com/document-api/api/image/common/subject-completion)

以上是文档契约，不证明真实 URL host 白名单、返回图片编码、权限或计费已实测。未知受理不可重发 POST；查询及下载恢复必须固定原模型、端点、任务 ID 与输入参数。这项为 Agenvas 的执行不变量。

## 7. 无模型版本的生成操作

扩图 `POST /v1/images/editing/expand`：必填 `image` 和 `up_expansion_ratio`, `down_expansion_ratio`, `left_expansion_ratio`, `right_expansion_ratio` 四个 float。每项 [0,2]，目标面积不能超过原图三倍，等价于 `(1+up+down)*(1+left+right) <= 3`。可选 `prompt` ≤2500、`n` [1,9] 默认1、watermark/callback/external ID。没有 model、negative_prompt、resolution、aspect_ratio 字段。图片规则与普通图片相同，单 GET 同路径 `/{id}`，结果为 images。[扩图](https://www.klingai.com/document-api/api/image/common/outpainting)

主体补全 `POST /v1/general/ai-multi-shot`：必填 `element_frontal_image`（正面参考图，URL/裸 Base64，JPG/JPEG/PNG ≤10MB、宽高 ≥300px、比例0.4–2.5）；仅另外定义 callback/external ID，不含 prompt/model/n。单 GET 同路径 `/{id}`。此操作生成多角度参考图，与主体库管理 CRUD 不同。[智能补全主体图](https://www.klingai.com/document-api/api/image/common/subject-completion)

## 8. 明确记录的官方文档冲突

1. 普通生成页旧兼容提示说空 `model_name` 调 V1，字段表说默认 V3；统一显式发模型。
2. Omni 页面主端点为 `/v1/images/omni-image`，较后“引入主体”示例误用 `/v1/images/generations`；按端点声明和查询路径采用 omni-image。
3. Omni 图片引用标签存在三角/双角及 image/object 命名差异；不应代替用户猜语法。
4. `series_amount` 类型写 int 但有 `auto`；采用受限联合类型。
5. 2.1 能力地图组图行有支持标记，但普通/多图字段表无 series/result_type；没有足够公开契约支持 2.1 系列参数。
6. Omni 字段表列所有模型可用 4k/series/auto，能力地图收窄 O1 不支持4k/series、V3 Omni纯文生图不支持auto；实现采用更窄范围。
7. 多图示例有 negative_prompt，字段表无此字段；不发送未定义参数。

来源为以上各节对应官方接口页与[图片能力地图](https://www.klingai.com/document-api/guides/capability-map/image)。这些差异需要官方确认或单列真实账号验证，不能声称已经实测解决。
