# ComfyUI image-v1 候选模板（尚未真实验证）

此文件是开发中的固定图像工作流说明，不是通过真实 ComfyUI/模型验收的发布清单。模板为 `backend/src/main/resources/comfyui/image-v1.json`，原始 JSON SHA-256：`6dc3bb9db9337dd9720cb8a3426fe618c4b890a1ce331f3fbd5b52522d7f5187`。运行时 `workflowVersion` 再纳入管理员配置的 checkpoint 文件名，因此更换模型会使待审批计划过期。

候选图仅使用 ComfyUI core 节点：`LoadImage`、`CheckpointLoaderSimple`、两个 `CLIPTextEncode`、`VAEEncode`、`KSampler`、`VAEDecode`、`SaveImage`；无需 Custom Node。连接固定为 `LoadImage → VAEEncode → KSampler.latent_image → VAEDecode → SaveImage`，参考图不是只写入 Prompt。服务端从同项目已钉住的 `SHOT.selectedImageVersionId` 读取受保护 Asset，等比留边归一化后上传；无参考图时上传同画幅空白图且 `denoise=1.0`。有参考图时使用 `denoise=0.65`。模型只可提出 prompt/negativePrompt，不能修改节点、连接、模型文件名、输出路径或 endpoint。

启用候选接入需要部署者配置 `AGENVAS_PROVIDER_MODE=comfyui`、精确 `AGENVAS_COMFYUI_ENDPOINT` 和已安装 checkpoint 的安全 basename `AGENVAS_COMFYUI_IMAGE_CHECKPOINT`。默认仍为 Mock，不需要 ComfyUI。checkpoint 模型的确切名称、来源、哈希、许可证和适用硬件由实际选型确认；**目前未选择或下载模型，也未记录真实兼容的 ComfyUI 版本**，不得把这份候选模板写成已验证能力。管理员应仅将可信 ComfyUI 实例暴露给后端；HTTP 路由不接受用户 URL。

当前证据：`ComfyUiImageWorkflowTest` 验证固定节点和输入覆盖；`ComfyUiImagePostgresIT` 用假 HTTP 服务与真实 PostgreSQL 验证图片审批、真实参考 Asset 上传、工作流映射、原 prompt_id 查询、空历史等待、归档，以及竞争 Worker 的单外部槽位。它不能证明真实模型存在或输出符合预期。发布门禁仍需在选定模型和真实 ComfyUI 上冻结兼容版本、完成至少一次真实参考图生图，记录结果、模型与节点许可证；视频 `image-to-video-v1` 另行验证。

核心节点与 API 形状参考 [ComfyUI 官方 API 示例](https://github.com/Comfy-Org/ComfyUI/blob/master/script_examples/basic_api_example.py) 和 [官方路由说明](https://docs.comfy.org/development/comfyui-server/comms_routes)。本仓库的 img2img 连接为候选改写，不冒充官方提供的已验证模板。
