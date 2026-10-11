# Seedream 图片协议接入依据

核对时间：2026-10-11。本说明依据火山方舟官方文档的当前页面与其模型目录，不将其他火山产品（例如 LAS 算子）的相似接口当作方舟协议。

## 来源与当前模型

- [图片生成 API](https://docs.volcengine.com/docs/ark/image-generation-api?lang=zh)，页面更新时间 2026-10-10 22:39:45。
- [模型列表](https://docs.volcengine.com/docs/ark/model-list?lang=zh)，页面更新时间 2026-10-09 17:49:07。
- [图片生成教程](https://docs.volcengine.com/docs/ark/seedream-4-0-5-0?lang=zh)。
- [Pro / Flash 交互编辑指南](https://docs.volcengine.com/docs/ark/seedream-5-0-pro-editing-guide?lang=zh)，页面更新时间 2026-09-22 12:13:36。
- [模型下线通知](https://docs.volcengine.com/docs/ark/model-deprecation-notice?lang=zh)。目录中的“即将下线”不能等同于 API 已删除该模型；实际账号可用性仍需运营方确认。

| 模型 | 编译 Model ID | 单图输入上限 | 单次输出 | 特殊能力 |
| --- | --- | --- | --- | --- |
| 5.0 Pro | `doubao-seedream-5-0-pro-260628` | 10 | 1；拆层最多 17 | 图层拆解、交互编辑、透明背景 |
| 5.0 Flash | `doubao-seedream-5-0-flash-260915` | 10 | 1；拆层最多 17 | 同 Pro，较快生成 |
| 5.0 Lite | `doubao-seedream-5-0-260128`；官方亦接受 `doubao-seedream-5-0-lite-260128` | 14 | 1；组图最多 15 | 组图、联网搜索 |
| 4.5 | `doubao-seedream-4-5-251128` | 14 | 1；组图最多 15 | 组图 |
| 4.0 | `doubao-seedream-4-0-250828` | 14 | 1；组图最多 15 | 组图、快速提示词优化 |

当前 API 页只列上述五个系列；不根据旧教程添加 Seedream 3.0 或 Seededit 模型。5.0 Lite、4.5、4.0 在当前目录标为即将下线。用户本轮确认接入“当前主力模型，补齐生成参数”，实际可发布 catalog 只包含 5.0 Pro、5.0 Flash、5.0 Lite canonical 三个模型；4.5/4.0 仅用于这份完整协议核对，不新增可发布 profile，Lite alias 也不重复出现在选择器。既有能力或 Task 的模型不自动改写。

## 地址、凭据、执行方式

官方请求为 `POST https://ark.cn-beijing.volces.com/api/v3/images/generations`，JSON body，使用 `Authorization: Bearer <ARK API Key>`。沿用 ARK Connection 的服务端加密 Key；不创建第二种凭据类型。运行时协议路径固定，默认官方 origin；管理员配置不扩大已有出站/private 地址策略，不提供任意图片 URL 或任意 JSON body。

这是同步接口，没有可查询的远程 Task ID、查询或取消端点。非流式 `stream=false` 一次 POST 可获得所有结果；Pro/Flash 不支持流式，Lite/4.5/4.0 可选流式，完整结果归档不需要实现流式。客户端不能重试创建请求：HTTP 4xx 确定拒绝（429 除外）；响应丢失、5xx、429、不可解析的受理结果保留 UNKNOWN。取得 request ID 和完整有效 manifest 后同事务持久化，恢复只归档这份 manifest。缺少供应商 request ID 时以冻结 requestKey 作为本地 identity，不能声称可远程查询。

输出 URL 有效期 24 小时，API 未承诺固定结果域名。为了不扩大结果 URL 出站权限，接入固定 `response_format=b64_json`；内联结果在持久化内部 manifest 后再进入现有资产归档，总编码 JSON 响应上限 96 MiB，单项解码结果上限 50 MiB。超过响应上限不能保存确定 manifest，保留 UNKNOWN，不能自动重 POST；内部 manifest 和 base64 不公开。这些 transport 限制不影响模型的图片内容参数。

## 当前生成参数

`model` 必填；服务端只接受 catalog 编译的 Model ID。`prompt` 为普通生成必填，图层拆解可省略；官方建议 300 汉字或 600 英文单词是建议，不是硬性上限。应用继续使用有界 prompt 字符串。

`image` 支持单个/数组的 HTTPS URL 或 `data:image/<小写格式>;base64,...`。Agenvas 只从授权的精确 ArtifactVersion 解析资产字节，按槽位顺序发 data URL；不接受 creator 指定的外部 URL。普通生成：最多 10（Pro/Flash）或 14（其余）；宽高均大于 14 px，总像素 196–36,000,000，宽高比 1/16–16，单项不超过 30 MB。官方格式包括 JPEG、PNG、WebP、BMP、TIFF、GIF、HEIC、HEIF；当前 Agenvas 资产入口允许的 PNG/JPEG/WebP 是可实际使用的子集，不能声称尚未上传/解码的格式已被验证。拆层必须恰好一张 PNG/JPEG，像素 262,144–36,000,000，同样的比例与字节上限。

| 参数 | 当前约束与默认值 |
| --- | --- |
| `size` | Pro/Flash 生成：1K / 1.5K / 2K，默认 2K，亦可 `宽x高`（像素 921,600–4,624,220、比例 1/16–16）。拆层：1K / 1.5K / 2K / auto，默认 auto，不支持显式宽高。Lite：2K / 3K / 4K，4.5：2K / 4K，4.0：1K / 2K / 4K；这三个默认 2048x2048，亦可显式宽高；Lite/4.5 像素 3,686,400–16,777,216，4.0 为 921,600–16,777,216，比例均为 1/16–16。 |
| `optimize_prompt_options.mode` | 默认 standard；仅 Pro 与 4.0 支持 fast，Flash/Lite/4.5 不允许 fast。 |
| `output_format` | Pro/Flash/Lite 的 jpeg（默认）或 png。4.5/4.0 固定 JPEG。拆层该参数只决定底图；其他图层始终 PNG。 |
| `background` | 仅 Pro/Flash：opaque（默认）或 transparent。transparent 必须恰好一张带 alpha 通道的参考图并输出 PNG；省略输出格式时供应商选 PNG，显式 jpeg 不可组合。 |
| `layer_decomposition` | 仅 Pro/Flash，默认 false；拆层固定 true，一张底图加最多 16 层，任一层失败视作整体失败。 |
| `sequential_image_generation` | 仅 Lite/4.5/4.0：disabled（默认）或 auto。auto 允许模型自主决定组图。 |
| `sequential_image_generation_options.max_images` | auto 时整数 1–15，默认 15；是生成上限而非精确数量。输入参考张数加实际输出张数最多 15，供应商会收紧输出数量，不能因默认 max_images=15 和一张参考图而直接拒绝。 |
| `tools` | 仅 Lite，可选 `[ {"type":"web_search"} ]`，无其他任意工具；用量由 `usage.tool_usage.web_search` 返回。 |
| `watermark` | 布尔，默认 true；是否附 AI 生成角标。 |
| `response_format` / `stream` | transport 固定 b64_json / false，避免 UI 暴露没有归档用途的控制项。 |

catalog 使用 `CreatorInputField` 表达这些标量与 `image1…image14` 精确素材槽。生成次数仍是一个 Task；组图结果数量以实际归档结果结算，估算为不超过 `min(max_images, 15 - 输入参考数)` 的上界。图层拆解上界 17，不能把它当作确切费用；拆层 auto 尺寸不提供虚假的分辨率档位。

## 图层与交互编辑

建议一个稳定 adapter `ARK_SEEDREAM_IMAGE`，按 model 与 operation 编译 profile。操作为普通 `GENERATE`、`LAYER_DECOMPOSITION`、`INTERACTIVE_EDIT`，不从任意 URL 推导协议。普通 Pro/Flash 的 prompt 也可直接使用官方编辑标记；专门交互 profile 提供有界、严格校验的 `edit_regions` 字符串辅助输入，转换为 prompt 而非上游 raw body。

交互编辑 API **没有** `coordinate`、`bbox`、`layers` 等独立请求字段。官方流程是把参考图的点/框转换成 0–999 整数坐标，嵌入提示词的 `<point>x y</point>` / `<bbox>x1 y1 x2 y2</bbox>`。箭头可画在输入参考图中配合自然语言；文档没有独立 arrow 坐标标记，不发明这种字段。有限高级 JSON 为数组，每项只允许 `image`（1-based 输入图编号）、`type`（point/bbox）、`coordinates`（2/4 个 0–999 整数）、`instruction`（有界文字）。拒绝未知属性、反向或零面积框、未绑定图号、过长数组；服务器按已冻结槽的输入顺序生成官方标记。没有 JSON layers 入参，拆哪些层由可选 prompt 描述。

拆层响应每项包含 `z_index`；底图为 0，后续层从 1 递增。图层有 `name`、`description`、`bounding_box.absolute`（底图坐标系下的四个整数）、`bounding_box.normalized`（0–1000 四个整数）。图层文件可能已裁剪，定位信息是复原底图所必需的，必须逐项随 manifest 和资产 metadata 保存；不能只保存图片列表后丢弃 bbox。按 z_index 稳定排序，底图设 primary。严格检查索引唯一、底图存在、边界框在底图范围内；语义名称/描述有界保存，不采集供应商推理正文。

## 响应与失败语义

非流式响应包含 `model`、`created`、`data[]`、可选 `error`、`usage` 和工具列表。成功项的 `b64_json`、`size`、输出格式须与实际解码图片一致；组图中 `data[].error` 可能与成功项共存。审核失败后供应商继续其他图，内部 500 可终止后续图；保留已经成功的全部项，不为失败项或不足的数量自动重新生成。没有任何成功项为确定失败；已获得成功结果但结构/内容永久错误要 BLOCKED，不能重 POST。超过 compiled profile 上限、错误模型身份、重复图层序号或不完整层拆解响应都不能被悄悄接纳。

`usage` 仅白名单保存 `generated_images`、`input_images`、`output_tokens`、`total_tokens`、`tool_usage.web_search` 的非负数值。官方计费以成功生成图片数为准；工具配置/响应正文、额外 reason/reasoning 字段、凭据、原始错误消息不进入公开状态或日志。对未知用量字段丢弃而不是原样复制。

沿用能力版本、Connection 版本、mapping hash、请求 key、素材版本的冻结和 CAS。切换能力只清理旧协议字段与不兼容槽，保存失败保留草稿；旧 Task 不随 catalog 变更改写模型或重建请求。图片的通用 ratio/resolution/count 控件不应与原生 size/组图互相覆盖。

## 验收边界

本次使用本地 fake HTTP 与合成图片验证固定请求、全部结果解析、精确素材校验、组合约束、身份/错误/UNKNOWN 和 manifest 归档恢复；这些不能证明真实 Provider 已接通。未发付费调用，未部署；账号权限、真实模型下线时间、真实延迟/费用、HEIC/HEIF 等资产格式入口不由合成测试证明。
