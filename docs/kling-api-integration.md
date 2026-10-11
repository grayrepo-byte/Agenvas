# 可灵图片与视频生成 API 接入说明

官方资料核实日期：2026-10-11。用户确认范围：图片、视频生成的全部当前模型与参数。字段、组合限制及文档冲突的逐项依据见[图片研究](research/kling-full-image-protocol.md)和[视频研究](research/kling-full-video-protocol.md)。资料核实和合成测试不等于真实付费验收。

## 连接与目录

创建 KLING 连接，按部署地区选择 `https://api-singapore.klingai.com` 或 `https://api-beijing.klingai.com` 并填写单个 API Key。服务端使用 Bearer 认证、加密存储和版本化掩码，只允许这两个官方根路径。旧 AK/SK JWT 不能调用新视频协议。[国际认证](https://kling.ai/document-api/api/get-started/authentication)、[国内认证](https://www.klingai.com/document-api/api/get-started/authentication)

发布 KLING_IMAGE 或 KLING_VIDEO，选择模型与操作。服务端 GET `/api/v1/settings/kling/profiles` 返回编译目录，发布 settings.klingInputs 为只读派生字段；管理员可设动态默认值、协议内更窄限制及价格。

| 媒体 | 当前模型 | 操作 |
| --- | --- | --- |
| 图片 | kling-v3、kling-v2-1 | 普通生成；2.1 另有多图参考 |
| 图片 | kling-v3-omni、kling-image-o1 | Omni 生成/编辑；3.0 Omni 支持连续出图 |
| 图片 | 无模型字段 | 扩图、主体多视图补全 |
| 视频 | kling-3.0-turbo、kling-3.0、kling-2.6、kling-2.5-turbo | 文生、图生，按素材选择原生路径 |
| 视频 | kling-3.0-omni、kling-o1 | Omni 参考、编辑与生成 |
| 视频 | kling-3.0、kling-2.6 | 动作控制 |

共十五种模型/操作组合。当前官方能力图没有列出的历史模型不作为可发布目录。元素/声音库管理、数字人、口型、音效、账单和任务列表管理不在确认范围内。旧固定 _3 能力保留恢复，新发布使用完整目录。

## 参数与素材

模型图片生成使用 `/v1/images/...` 与 model_name；扩图使用 `/v1/images/editing/expand`，主体多视图补全使用 `/v1/general/ai-multi-shot`，两者不传模型。参数涵盖 Prompt、负面词、图片/主体参考、参考类型/fidelity、分辨率、画幅、n、连续结果类型/数量、水印；扩图另有四边比例与面积约束。各字段只在对应模型/操作声明并校验，普通 3.0 不能使用 Omni 的 4k。

视频 URL 包含模型名，例如用户提供的 [3.0 Turbo 图生文档](https://www.klingai.com/document-api/api/video/3-0-turbo/image-to-video)。按模型构造 contents/settings/options：Prompt、首尾帧、参考图片、参考/编辑视频、主体/声音 ID、时长、画幅、分辨率、音轨、多镜头、动作朝向、水印。2.5/2.6 普通生成只支持 5/10 秒，3.0 普通生成支持 3–15 秒；动作控制不填写固定输出时长。

草稿 dynamicValues 保存标量，元素/声音原生列表由有限 ID/类型/别名字段构造。图片协议的 int64 元素 ID 以十进制字符串保存，新视频协议的元素 ID 保留原生不透明字符串。外部任务 ID 由应用填写，状态按原 ID 轮询，不开放创建者自定义回调或媒体 URL。

素材槽选择本项目授权的精确版本，上传、媒体库和画布连线复用既有控件。图片发送 PNG/JPEG 原始 Base64，图片接口上限十进制 10 MB，视频图片上限 50 MB，宽高至少 300 px、比例 0.4–2.5。视频参考用原对象签名或固定中继；模型独立的尺寸、时长、格式、FPS 和数量组合见研究记录。网络准备及供应商请求不持有数据库事务。

## 任务、结果与升级

受理固定模型、操作、有效参数、价格、槽、素材和连接版本；结构化提示词标签按原生槽别名渲染。图片 n=1..9/连续出图一次请求归档多个结果；自动数量预留费用未知，归档按实际数量估算。动作控制按实际探测时长估算。估算使用管理员价格，不声称实际账单已知。

图片成功状态为 succeed，新视频为 succeeded，逐项核对原任务身份。创建受理不明保持 UNKNOWN，轮询、归档、重启和 URL 过期不能触发新 POST。持久结果清单保留序号/身份；过期只刷新同一原 ID/输出。水印缺失、身份错误或结果地址不允许时显式阻断。

结果允许 HTTPS 默认端口的 h1.inkwai.com 和严格边界的 *.klingai.com，执行 DNS 检查、禁止重定向、无 Authorization、大小/MIME/魔数及归档解码/视频探测。实际完整 CDN 范围仍需真实调用核验。

前后端一起升级并执行 V19/V20，不修改既有任务和媒体版本。实际检查见[开发清单](DEVELOPMENT-CHECKLIST.md)。真实付费调用、真实中继参考、全部模型组合/账单验收、全量测试、浏览器端到端和部署均未运行。
