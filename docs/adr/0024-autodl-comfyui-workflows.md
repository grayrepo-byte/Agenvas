# AutoDL ComfyUI 工作流固定协议适配

2026-10-01，用户要求接入 AutoDL ComfyUI 工作流，并明确指出参考资源实际需要 base64，不能依赖文档中的 URL 输入。此决定局部修订 [ADR 0002](0002-fixed-media-adapters-before-workflow-platforms.md) 暂不接入工作流平台的范围：新增 `AUTODL` 连接与 `AUTODL_COMFY_VIDEO` 固定 Java 适配器，共用既有 CanvasItem 草稿、版本化媒体能力和持久 Task，不引入脚本、任意 ComfyUI 图、远程工作流发现或 Agent 媒体工具。

使用受审查的 H3 工作流参数声明：同一提交/查询协议，通过版本化 `workflowId`、`videoResolution`、可选 `seed` 区分工作流；每个声明固定输入模式、必填参考、参考数量、时长、种子支持与精确分辨率枚举。首尾帧使用 `first_frame` / `last_frame`，其他参考使用分别从零编号的 `ref_image_N` / `ref_audio_N`。管理员可收紧上限，不能扩大工作流范围；资源按项目权限读取已冻结版本的归档字节，以带 MIME 的 base64 data URL 提交。

提交前记录尝试检查点，拿到 `task_id` 后持久保存并异步查询原 ID。响应丢失保持 UNKNOWN，只允许用户显式新尝试；下载失败或链接过期只查询原任务并恢复归档，不再次生成。结果仅从已确认的官方对象存储主机与 `/comfyui/outputs/` 下载，校验 DNS、MIME、字节上限并禁止重定向，下载不携带 Token。视频保留原音轨，由既有媒体解码与归档流程创建不可变 Asset/ArtifactVersion；不裁剪供应商返回的时长。

当前支持 14 个显式时长的 H3 工作流。自动按音频决定时长的单图对口型、需要视频参考的动作迁移、使用不同音频参数的 IndexTTS 尚未实现，后续扩展须有相应参数与媒体契约，不能套用 H3 声明声称兼容。V65 增加平台检查约束，既有数据保留，前后端同版本升级；验证与配置见 [AutoDL 接入说明](../autodl-comfyui.md)。
