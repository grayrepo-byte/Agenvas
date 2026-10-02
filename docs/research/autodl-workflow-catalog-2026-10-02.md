# AutoDL 官方工作流目录与分辨率核对

获取日期：2026-10-02（Asia/Shanghai）。本次仅匿名读取官方页面、页面 JavaScript 与公开目录/详情接口，没有读取凭证或调用提交生成、查询用户任务、取消等接口。

## 官方可发现的元数据

官方 [ComfyUI 目录](https://www.autodl.art/large-model/comfyui) 的 [接口模块](https://www.autodl.art/assets/comfyui.eb7c008a.js) 明确使用以下查询接口；[目录页面模块](https://www.autodl.art/assets/index.75c2f493.js) 给出分页参数。本次匿名请求均返回 `code: Success`。

- `POST https://www.autodl.art/api/v1/comfyui/workflows`，JSON 为 `{"page_size":100,"page_index":1}`。这是目录查询，返回 `data.list`、`result_total`、`max_page` 等；本次共有 **17** 条。
- `GET https://www.autodl.art/api/v1/comfyui/workflows/{workflowId}`。例如 [z0901 详情](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_z0901) 包含 `uuid`、`name`、`version`、`input_rules`、`input_example`、`output_example`、`price_type` 与 `billing_config`。
- `input_rules` 按业务参数名称列出类型、必填标记、默认值、数值/长度上下限及媒体 MIME 类型；`resolution.type` 为 `enum`，其 `options[].label` 是提交给 AutoDL 的精确枚举文本，`options[].values` 是供应商图内映射。后者不应作为客户端可以执行的图、脚本或请求模板。

例：z0901 的 `duration` 为整数 1–15，`prompt` 为必填字符串；`resolution.options` 中有 `1088p横(1920*1088)`、`1440p横(2560*1440)` 等。z0903 的 `ref_image_0` 与 `ref_audio_0` 均为必填媒体字段，剩余参考字段可选。[z0901 详情](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_z0901)、[z0903 详情](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_z0903)。

## 本地声明是否遗漏分辨率

将获取时的 `backend/src/main/resources/providers/autodl-h3-workflows.json` 中 **14** 条工作流的全部 `resolutions`，与对应官方详情的全部 `input_rules.resolution.options[].label` 作集合比较，**14 条均相同，没有发现遗漏或多出的精确枚举**。这只证明获取时的公开输入契约一致，不代表未来供应商不会调整，也不代表逐档付费生成已验证。

| 工作流 ID | 官方档位 | 精确枚举数量 | 比例范围 |
|---|---|---:|---|
| minimax_h3_b99_001 / b99_002 / b99_003_12s（均带 minimax_h3_ 前缀） | 736p | 每条 3 | 横、竖、1:1 |
| minimax_h3_image_audio_to_video_v2 | 480p、768p、1080p | 6 | 横、竖 |
| minimax_h3_image_audio_to_video_v2_15s | 480p、768p | 4 | 横、竖 |
| minimax_h3_lightx2v / lightx2v_no_pic / lightx2v_v5_15s（均带 minimax_h3_ 前缀） | 480p、768p | 每条 6 | 横、竖、1:1 |
| minimax_h3_lightx2v_v5 | 480p、768p、1080p | 9 | 横、竖、1:1 |
| minimax_h3_z0901 / z0902 / z0903（均带 minimax_h3_ 前缀） | 480p、768p、1088p、1440p | 每条 8 | 横、竖 |
| minimax_h3_zm_u08 / zm_u24（均带 minimax_h3_ 前缀） | 480p、768p | 每条 6 | 横、竖、1:1 |

各系列不能合并成一套通用分辨率：例如 [b99_001](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_b99_001) 仅列 736p，而 [z0901](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_z0901) 列出 1440p；[v2](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_image_audio_to_video_v2) 的 1080p 不在 [v2_15s](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_image_audio_to_video_v2_15s) 的枚举中。枚举有 1080p 与 1088p 的真实差别，应保留原值。

## 目录另外三条工作流

以下 ID 在官方目录中存在，但不在核对时的 14 条本地声明中；既有 `docs/autodl-comfyui.md` 已将它们列为未支持，不能据此声称已接通。

| 工作流 ID | 从官方输入/输出示例可确认的差别 |
|---|---|
| [minimax_h3_image_audio_to_video](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_image_audio_to_video) | 没有 `prompt` 和 `duration` 参数；使用 `audio_duration`、必填图片/音频；有 480p/768p/1080p 横竖枚举。 |
| [wan2.2animate-v4-motion_retargeting](https://www.autodl.art/api/v1/comfyui/workflows/wan2.2animate-v4-motion_retargeting) | 必填 `ref_video` 与 `ref_image`；没有 `prompt` 和 `duration`；分辨率 label 为 `464*832px(竖版)` / `832*464px(横版)`，对应 `values` 的短边却为 468，不能仅凭 label 保证实际输出像素。 |
| [indextts2-v1](https://www.autodl.art/api/v1/comfyui/workflows/indextts2-v1) | 输出示例为音频；必填 `prompt_simple`（音频）、`prompt_text`（文字）、`emo_control_method`，另有情绪向量/参考等参数；没有视频分辨率字段。 |

## 新工作流无需更新代码的可行边界

以下为研究判断，不代表入口已经实现：官方目录和 `input_rules` 足以支持管理员只读发现、导入并确认一个受约束的输入声明，继而保存为能力版本。对遵守现有视频提交协议、所需参数可由画布已有文字/时长/图片/音频/分辨率/种子映射表达的新工作流，工作流 ID 与枚举可成为配置数据，无需继续复制前后端固定列表。

发现结果仍需检验输出用途、参数类型、必填字段、媒体槽位和尺寸/比例映射，并在任务受理时冻结声明。对于视频参考、音频自动时长、音频输出或新必填业务参数，当前协议能力是否足够须单独判断；不能只允许输入陌生 ID 就宣称所有官方工作流可用。当前详情的输出是示例，没有发现可据以保证未来输出类型的强类型输出 Schema。

官方 `billing_config` 包含分辨率价格和 `off_peak_price`，可证明供应商存在按档位与峰谷变化的价格；本研究未核验实际账单，也未将原始金额整数直接换算成应用价格。[z0901 详情](https://www.autodl.art/api/v1/comfyui/workflows/minimax_h3_z0901)、[官方详情页面模块](https://www.autodl.art/assets/detail.5046e239.js)。

## 获取与验证限制

浏览工具访问官方页面超时，改用 `curl` 和 Python 标准库读取同一官方域名成功。原始公开响应临时保存在 `/tmp/agenvas-autodl-catalog.json`、`/tmp/agenvas-autodl-{workflowId}.json`；临时文件不是持久发布接口或项目依赖。

只核对公开元数据及本地声明，没有运行功能测试、没有真实付费生成、没有验证当前生产页面发布了哪些档位。目录/详情来自网站正在使用的第一方 API，未发现其稳定性承诺；若提供运行时发现入口，应有超时、响应大小/条数限制、明确错误及管理员确认，不能让目录变动改写既有任务的冻结契约。
