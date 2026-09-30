# AutoDL ComfyUI 接入

在「设置 → 媒体模型」添加 **AutoDL · ComfyUI 工作流** 连接，填写 AutoDL「令牌管理」中分组为 **ComfyUI** 的 Token。普通大模型 Token 可能返回 HTTP 403；服务端发送原始 `Authorization` Token，不添加 `Bearer`。端点固定为 `https://autodl.art`，Key 只在服务端加密保存，不进入任务输入或项目导出。

在连接上发布能力，选择 **ComfyUI 工作流 · AutoDL H3**、具体工作流、输出分辨率档位和可选种子。每个工作流独立发布能力，可设为视频默认能力。画布卡片选择能力、填写提示词和时长，并按输入模式提供参考，然后显式运行。

- 分辨率档位结合卡片比例映射到工作流的精确枚举；自动比例在受理时采用项目比例。最终枚举、工作流 ID 和种子写入冻结媒体输入，后续项目、草稿或能力变化不改变任务。部分工作流不支持方形；不支持的组合阻止运行。
- 全能参考中，图片和音频分别按当前顺序映射到 `ref_image_0...`、`ref_audio_0...`；首尾帧使用 `first_frame` / `last_frame`，两帧均需提供。读取精确 ArtifactVersion 的归档 Asset，不读取之后的新版本。
- 所有参考发送 `data:<MIME>;base64,<bytes>`，不发送私有资源 URL。应用输入上限为每个资源 15 MiB、合计 60 MiB；图片接受 PNG/JPEG/WebP，音频接受 MP3/WAV/FLAC。当前应用上传只支持 MP3/WAV/OGG，AutoDL 会拒绝 OGG；不宣称新增了 FLAC 上传。
- 已受理任务持久等待，按原 `task_id` 轮询。UNKNOWN 不自动重发，显式重试创建独立任务与用量预留。排队取消不提交；已外部受理请求的后续结果遵守既有晚到结果归档与选择保护，不承诺外部停止或退款。
- 结果成功后尽快下载，保留音轨并通过实际解码校验归档。网络/归档失败恢复原任务，临时地址失效可再次查询原 ID 刷新地址；没有可用新地址时显式阻塞。不能用重新提交恢复下载。
- 价格由管理员按 VIDEO 或 SECOND 配置估算；缺失显示未知。AutoDL 存在分辨率及峰谷价差，应用不将管理员估算当作供应商实际账单。

## 当前工作流范围

固定声明位于 `backend/src/main/resources/providers/autodl-h3-workflows.json`；前端 `frontend/src/shared/autodlWorkflows.ts` 保持相同字段。新工作流需更新两处声明并测试，不在运行时执行供应商返回的图或代码。

| 工作流 ID | 模式 | 图片上限 | 音频上限 | 时长（秒） |
|---|---|---:|---:|---:|
| minimax_h3_z0901 | 纯文本 | 0 | 0 | 1–15 |
| minimax_h3_z0902 | 全能参考 | 6 | 0 | 1–15 |
| minimax_h3_z0903 | 全能参考 | 6 | 3 | 1–15 |
| minimax_h3_zm_u08 | 全能参考 | 9 | 3 | 1–15 |
| minimax_h3_zm_u24 | 全能参考 | 9 | 3 | 1–15 |
| minimax_h3_b99_001 | 纯文本 | 0 | 0 | 1–15 |
| minimax_h3_b99_002 | 首尾帧 | 2 | 0 | 1–15 |
| minimax_h3_b99_003_12s | 全能参考 | 9 | 0 | 1–12 |
| minimax_h3_image_audio_to_video_v2 | 全能参考 | 9 | 3 | 1–10 |
| minimax_h3_image_audio_to_video_v2_15s | 全能参考 | 9 | 3 | 1–15 |
| minimax_h3_lightx2v | 首尾帧 | 2 | 0 | 1–15 |
| minimax_h3_lightx2v_no_pic | 纯文本 | 0 | 0 | 1–15 |
| minimax_h3_lightx2v_v5 | 全能参考 | 9 | 0 | 1–10 |
| minimax_h3_lightx2v_v5_15s | 全能参考 | 9 | 0 | 1–15 |

必填数量与精确枚举以固定声明为准；例如 `z0903` 至少 1 图 + 1 音频，`z0902` 至少 1 图，部分 v2 参考字段均可选但应用全能参考模式仍至少需要一个媒体输入。数量上限不代表所有槽位必填。

未支持：`minimax_h3_image_audio_to_video` 的自动音频时长、`wan2.2animate-v4-motion_retargeting` 的视频参考、`indextts2-v1` 的音色情绪参数，也不新增图片输出适配器或通用工作流模板编辑器。

## 升级与验证

新增 Flyway **V65** 仅扩展平台检查约束，保留现有连接、任务、版本和资源。jOOQ 源码通过隔离 PostgreSQL 17 重新生成；OpenAPI 新增 AUTODL 平台与工作流设置，TS 按合约生成。部署前备份数据库与文件卷，并同时更新前后端；没有旧协议兼容层或自动替换既有默认能力。

官方来源：[API 文档](https://autodl.art/docs/comfyui_api/)、[工作流目录](https://www.autodl.art/large-model/comfyui)。文档描述 URL 输入，但本实现按用户确认的实际限制只使用 base64。真实 API、本地协议测试和 PostgreSQL 集成验证分别记录于 [验证证据](evidence/autodl-comfyui-2026-10-01.md)。
