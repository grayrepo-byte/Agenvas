# 万相当前主力生成 API 接入核实

核实日期：2026-10-11。资料均来自阿里云百炼官方文档；未发起真实生成、付费调用或部署。本文记录本轮编译目录，不声称覆盖万相全部历史 API。

## 发布目录与请求

| 稳定适配器 | 模型 | 操作 | 创建路径 |
| --- | --- | --- | --- |
| `WAN_IMAGE` | `wan2.7-image`、`wan2.7-image-pro` | `GENERATE` | `/api/v1/services/aigc/image-generation/generation` |
| `WAN_VIDEO` | `wan3.0-video`、`wan3.0-video-prime` | `GENERATE` | `/api/v1/services/aigc/video-generation/video-synthesis` |

两类使用 POST JSON、`Authorization: Bearer <区域API Key>` 和 `X-DashScope-Async: enable`。本应用选择图片异步接口以保留可恢复的任务身份，未发布图片同步入口。创建返回 `output.task_id/task_status`；随后只使用 `GET /api/v1/tasks/{task_id}`。任务及结果 URL 的文档有效期为 24 小时。[图片协议](https://help.aliyun.com/zh/model-studio/wan-image-generation-and-editing-api-reference)、[视频协议](https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference)。

## 区域、凭据与地址

连接填写 HTTPS 根地址 `https://{WorkspaceId}.{region}.maas.aliyuncs.com`，不填写 API 路径。WorkspaceId 来自百炼业务空间的 API Host，普通示例是 `llm-xxx`。图片本轮只准入其 API 页面明确列出的北京 `cn-beijing` 和新加坡 `ap-southeast-1`；视频另准入东京 `ap-northeast-1`、法兰克福 `eu-central-1`、弗吉尼亚 `us-east-1`、香港 `cn-hongkong`。模型开通情况和 Key 必须属于同一区域。

旧 DashScope 根域仍用于存量兼容，但官方注明自 2026-09-30 不再支持新特性；本轮只发布 Workspace 专属地址，避免推断旧地址支持新模型全部参数。试用域名、任意子域、非默认端口、用户信息、查询串、片段和 API 子路径均不准入。[区域与接入域名](https://help.aliyun.com/zh/model-studio/regions)。

API Key 由现有服务端密钥模块加密保存。受理任务固定连接版本、能力版本和模型；后续密钥轮换不改变历史尝试。Key 不进入媒体 URL、参数、Prompt、公开错误或日志。

## 图片参数与素材

输入为单个 `user` 消息，其 `content` 按数值槽序排列图片对象，末尾为文本对象。文本上限 5000 字符，最多 9 张原图。支持 JPEG/PNG/BMP/WebP，PNG 禁止 alpha 通道；文件 ≤20 MiB，每边 240–8000，比例 1/8–8。应用素材以精确版本 UUID 绑定，校验实际原字节及完整解码后发送 data URI，无法由创建者填写外部 URL。

原生 `parameters`：`size`、`n`、`enable_sequential`、`thinking_mode`、`color_palette`、`bbox_list`、`watermark`、`seed`。`size` 接受 1K/2K 或 `WIDTH*HEIGHT`；pro 仅无图、非组图允许 4K。自定义像素面积下限 768²，上限普通 2048²，pro 无图非组图 4096²，比例 1/8–8。预设尺寸按末张输入图比例输出，无图为方图。

普通 `n=1..4`，组图 `n=1..12` 表示最大数量，实际结果可少于上限；应用将一次请求的全部图片归档。`thinking_mode` 仅无图非组图发出。seed 为 0..2147483647。配色 3–10 组 HEX 与百分比，百分比精确两位且合计 100.00%，组图不可配置。框选每张至多两个绝对像素矩形，需完整四坐标并位于原图内；序列 `bbox_list` 与实际传入图片一一对应。

创建者表单将配色有限展开为 `color_N_hex/color_N_ratio`；百分比数值序列化为 `NN.NN%`。矩形有限展开为 `image_N_bbox_B_x1/y1/x2/y2`，构造请求时还原原生嵌套数组。稀疏槽按数值序紧凑发送，提示词引用相同顺序的 `图1` 等。它们是原生协议字段，不含图节点、任意 JSON 或 provider URL。

成功查询为 `output.choices[].message.content[]`，支持 `text/image`；仅归档图片，避免保存上游文本或思考内容。每张身份固定为 choice/content 数组位置，usage 仅保存安全计数；结果全部来自同一原始 task_id。[图片 API](https://help.aliyun.com/zh/model-studio/wan-image-generation-and-editing-api-reference)。

## 视频参数与素材

原生字段为 `input.prompt/media` 与 `parameters.resolution/ratio/duration/audio/seed/prompt_extend/watermark`。至少一个非空提示词或素材；文本上限 20000 字符。分辨率 480P/720P/1080P，比例 adaptive/21:9/16:9/4:3/1:1/3:4/9:16。普通时长 2–30 秒；表单 `smart_duration=true` 对应原生 duration=-1。audio 默认 true，prompt_extend 默认 true，watermark 默认 false；seed 为 -1 或 0..2147483647。

`first_frame` 与可选 `last_frame` 只允许彼此组合，禁止与参考素材混用。参考模式可自由组合 image≤10、video≤5、audio≤5；分别按紧凑顺序使用 `图N/视频N/音频N` 指代。视频每段 1–15 秒且总计≤15秒，音频相同；普通指定时长时，输入视频总秒数+输出秒数≤30。视频 MP4/MOV≤100 MiB、各边240–4096、比例1/8–8、帧率≥16；音频 WAV/MP3≤15 MiB。图片约束同本页上一节。

图片使用 data URI；视频及音频原生要求 URL，由已冻结的服务端中转配置签名精确素材。预检不上传、不签名；提交与上传均不持有数据库事务。实际字节、摘要、格式、尺寸、帧率、时长和总时长需提交前校验。

官方另有 file/link 输入，但当前画布没有文档资产或受控网页来源契约，因此本轮不暴露这两项，也不以任意 creator URL 替代素材权限。旧 2.x、角色动画/换人、涂鸦等历史生成 API 暂不加入本轮目录。

成功查询返回一个 `output.video_url`；`usage.input_video_duration/output_video_duration/duration` 分别为输入、输出、合计秒数。万相3的视频计费依据输入参考视频与成功输出时长之和；应用估算固定输入秒数，归档后按实际输出秒数结算。[视频 API](https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference)、[官方模型计费](https://help.aliyun.com/en/model-studio/model-pricing)。

## 失败、恢复与下载边界

原生状态为 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELED/UNKNOWN。最后一项表示任务不存在或失效，并非本地“受理不确定”。明确 4xx 拒绝或已核实的拒绝 code 才结束为 Rejected；创建断连、5xx、格式错误、缺 task_id 等保留本地 UNKNOWN。HTTP 客户端禁止代理、重定向、连接重试及 Retry-After 503 重发。查询重试、下载恢复均只能沿原 task_id，不产生新 POST。[错误码](https://help.aliyun.com/zh/model-studio/error-code)。

结果下载仅准入官方示例已明确给出的精确主机 `dashscope-result-bj.oss-cn-beijing.aliyuncs.com` 与 `dashscope-result-sh.oss-accelerate.aliyuncs.com`，HTTPS 443、无凭据、无重定向，应用现有 DNS 安全规则持续生效。签名查询串可保留，不公开或记录 URL。图片有100 MiB、视频500 MiB下载上限并核验 MIME/magic，归档完整解码。收到过期签名时，仅重新查询原任务、按原结果身份取新 URL；未返回新链接或任务已失效则 BLOCKED。[视频官方返回示例](https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference)、[官方文生视频返回示例](https://help.aliyun.com/en/model-studio/text-to-video-guide)。

新加坡及其他区域若实际返回上述主机之外的新官方结果桶，本轮严格策略会阻断归档，须取得官方或真实受控调用证据后扩展精确 allowlist，不能扩大到整个 OSS 域。本轮单元测试使用本地合成 HTTP；尚未证明任一区域真实模型可调用、所有极限组合、签名有效期内刷新行为或真实输出数量。
