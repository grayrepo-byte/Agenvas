# T23 ComfyUI 图生视频候选接入：部分进展

2026-09-23。此接入是**默认关闭的候选实现**，不是已通过真实模型验收的视频生成功能。固定工作流在 `backend/src/main/resources/comfyui/image-to-video-v1.json`，原始 JSON SHA-256 为 `29766587c728418dde7792c73adebae465e85b524c333686454061ac0491b409`。运行时审批版本还纳入四个管理员配置的模型文件 basename；变更后旧计划不能被当作新模板批准，旧请求也不会以新配置重新提交。

候选图使用 Wan 2.1 的 ComfyUI core 节点：`LoadImage`、`UNETLoader`、`CLIPLoader`、`VAELoader`、`CLIPVisionLoader`、`CLIPVisionEncode`、`CLIPTextEncode`、`WanImageToVideo`、`ModelSamplingSD3`、`KSampler`、`VAEDecode`、`CreateVideo`、`SaveVideo`。已批准且固定版本的 IMAGE Asset 经等比留边生成 PNG，上传后的服务端文件名同时进入 `CLIPVisionEncode.image` 和 `WanImageToVideo.start_image`。用户/Agent 不能传入任意节点、模型文件路径或输出路径。模板参考 [Comfy-Org 官方 Wan 图生视频工作流](https://github.com/Comfy-Org/workflow_templates/blob/main/templates/image_to_video_wan.json)；此仓库图是候选改写，不声称官方已验证它。

固定 16 FPS。审批仅允许 1000–5000 毫秒、250 毫秒步长；`length = durationMs / 250 * 4 + 1`。横屏映射 832×480，竖屏 480×832，方形 640×640。输出要求固定 `SaveVideo` 节点报告一个 MP4/H.264 视频；下载只经配置的同一 ComfyUI 服务，媒体归档仍验证实际 MP4 与首帧。当前未额外证明真实输出时长与申请值完全相等。

启用需要部署者显式设置 `AGENVAS_PROVIDER_MODE=comfyui`、精确 `AGENVAS_COMFYUI_ENDPOINT`、图像 checkpoint、`AGENVAS_COMFYUI_VIDEO_ENABLED=true`，以及 `AGENVAS_COMFYUI_VIDEO_DIFFUSION_MODEL`、`AGENVAS_COMFYUI_VIDEO_TEXT_ENCODER`、`AGENVAS_COMFYUI_VIDEO_VAE`、`AGENVAS_COMFYUI_VIDEO_CLIP_VISION` 四个已安装 `.safetensors` basename。仓库不附带模型权重，也未选定可发布的模型文件名、来源、哈希、许可证、硬件或 ComfyUI 兼容版本。默认 Mock 模式不要求以上配置；ComfyUI 图像模式也不会自动启用视频。

`ComfyUiVideoPostgresIT` 以真实 PostgreSQL、假 ComfyUI HTTP 服务和本地 FFmpeg 生成的 MP4 验证：审批前零视频提交、选定图片版本进入上传及固定图输入、已受理后只保存和轮询原 `prompt_id`、空历史不重提、固定任务资产 ID 归档、VIDEO 版本保存 `keyframeVersionId`，且 Provider attempt 只有一条。`ComfyUiVideoWorkflowTest`、`ComfyUiHistoryTest` 验证参数与响应边界。`./mvnw -q verify` 在 2026-09-23 通过。没有真实 GPU/模型调用；没有证明画面质量、节点/模型文件兼容、跨独立进程故障恢复，T23 真实 Provider 验收仍未勾选。

后续发布门禁：选定可再现的 ComfyUI 及 Wan 模型版本/许可证，真实关键帧图生视频测试，核对时长/画幅/分辨率与输出质量，多进程故障注入，并记录硬件、耗时、测试产物及限制。共享实例不调用全局 `/interrupt`。
