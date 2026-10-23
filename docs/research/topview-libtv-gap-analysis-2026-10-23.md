# Agenvas 与 Topview、LibTV 的功能差距

核对日期：2026-10-23。本文是产品研究与后续建议，不改变现行规格、ADR 或开发排期。

## 范围与证据

- 竞品依据可公开读取的官方产品页、帮助文档及官方 Skill；详细来源分别见 [Topview 调研](topview-capabilities-2026-10-23.md)、[LibTV 调研](libtv-capabilities-2026-10-23.md)。未登录竞品、未进行付费生成，也未实测其画质、可靠性或一致性。
- Agenvas 依据当前工作区源码、API 合约、规格及开发清单，包含尚未提交的导演剧本拆解改动；源码存在不代表已部署。此次没有运行应用或测试，既有验收记录与此次验证分别表述。
- 下文“缺少”限定为未发现内建、可由用户直接使用的完整功能。可通过 ComfyUI、RunningHub 或模型提示词实现部分步骤，不等于对应产品流程已完成。
- 优先级属于基于 Agenvas 自托管、个人创作与低成本定位的判断，不是竞品功能数量排名。LibTV 不可读帮助中心中的细节不从第三方介绍推导。

## 判断

Agenvas 已有画布、多模态生成、导演对话、Skill/MCP、资产库、模板、参考输入、图片处理、3D 构图及本地视频后期。下一步价值主要来自把这些工具连接为可编辑、可追踪、可复用的制作流程。

最值得优先推进的是：**可操作分镜 → 角色与镜头关联 → 可保存成片工程和整片预览 → 语音字幕校对**。随后完善整套流程复用、画布效率和参考视频拆解。3D 动态运镜、团队协作及社区应按实际用户需求安排。

## 竞品对照

| 方向 | Topview 的可读一手证据 | LibTV 的可读一手证据 | Agenvas 的对应位置 |
| --- | --- | --- | --- |
| 剧本与镜头组织 | Drama Studio 描述大纲、集、角色、场景、剧本、分镜与视频版本，V2 为 Beta。[官方介绍](https://www.topview.ai/drama-studio) | TV Director 宣传原创、改编、剧本编辑及导演/分镜提示词辅助；精细镜头数据未核实。[官方介绍](https://www.liblib.tv/ai-video/rival?sourceid=040154) | 已有导演完整文字流程，下一步可增加直接操作分镜的界面。 |
| 成片编辑 | 同项目 Canvas/Timeline，整片播放、裁切、排序、分音轨、整片/片段/ZIP 导出有操作文档。[Timeline](https://www.topview.ai/academy/timeline) | 官网有智能剪辑入口；多轨、工程持续编辑、时长等细节未核实。[官网](https://www.liblib.tv/) | 已有有界后期；主要完善工程保存与整片预览。 |
| 角色与声音 | 官方练习包含角色资产、角色声音及逐镜 @ 引用，要求检查外观与声音一致性。[教程](https://www.topview.ai/academy/team-collab) | 官网出现角色造型室入口，跨镜头绑定与声音复用未核实。[官网](https://www.liblib.tv/) | 已有分类资产和精确参考，尚缺稳定的角色/造型/镜头关联。 |
| 画布效率与复用 | 分组、图层、共享素材和撤销/重做有文档；未证实通用可执行工作流模板。[组织画布](https://www.topview.ai/academy/organize-canvas)、[基础操作](https://www.topview.ai/academy/what-is-canvas) | 有精选画布及作品展示；完整工作流复制与打组细节未核实。[官网](https://www.liblib.tv/) | 可补用户可操作分组、画布级撤销及流程复用；不能据此宣称两家均已有通用模板。 |
| 空间预演 | 3D Builder 文档描述姿态、路径、多机位、截图和视频输出。[空间控制](https://www.topview.ai/academy/spatial-control) | 3D-BOX 专页宣传 Agent 搭景、站位调度和手绘轨迹运镜。[专页](https://www.liblib.tv/3d-box) | 已有静态 3D 构图与截图，可按需要增加姿态和动态运镜。 |
| 外部自动化 | 官方 Skill 介绍图片、视频、Avatar、TTS 等入口；未核实全部 Canvas 领域接口。[Skill](https://www.topview.ai/skill) | 官方客户端代码可证会话、上传、增量查询、批量下载和回画布。[官方仓库](https://github.com/libtv-labs/libtv-skills) | 已有内置导演、Skill 和 MCP，不应重复列为基础能力缺失。 |

Topview 的公开操作文档较完整，适合逐项核对交互能力；LibTV 此次主要可确认产品路径与外部 Agent 协议。二者的角色一致性、输出质量和可靠性均没有本次实测，不能据宣传作胜负判断。

## 当前能力与缺口

| 方向 | Agenvas 现状 | 值得完善的行为 | 本地依据 |
| --- | --- | --- | --- |
| 剧本到分镜 | 导演默认指令已扩展完整文字流程，全部成果仍为普通 TEXT；SC/SHOT 是文档标识 | 可编辑分镜表与镜头卡，直观看台词、时长、参考、关键帧、视频、进度；局部重做与修改影响提示 | [ADR 0054](../adr/0054-director-script-to-storyboard-workflow.md)、[V34](../../backend/src/main/resources/db/migration/V34__director_storyboard_workflow.sql) |
| 批量生产 | 已有媒体批次提案、审批、任务恢复与结果续接；并非只有逐卡片运行 | 用镜头生产面板表达待准备、待审批、生成中、可选用、待复核；按镜头批量操作与明确费用 | [ToolRegistry](../../backend/src/main/java/dev/agenvas/llm/application/ToolRegistry.java)、[默认导演流程](../../backend/src/main/resources/db/migration/V34__director_storyboard_workflow.sql) |
| 角色、场景与道具 | 已有资产分类、精确版本参考、三视图与文字连续性设定 | 同一角色的多视图、服装/表情变体、音色与出场镜头关联；场景机位和道具状态可追踪 | [LibraryEntry](../../backend/src/main/java/dev/agenvas/library/domain/LibraryEntry.java)、[资产库 ADR](../adr/0026-personal-asset-library.md)、[媒体节点操作](../../frontend/src/features/canvas/MediaCanvasCard.tsx) |
| 成片编辑 | 已有列表式入出点、排序、变速、淡黑、原声、配音、音乐、手填定时字幕及 MP4 输出 | 成片草稿保存/重开；应用字幕与音频的整片预览；可视排序、波形和对齐；按需求扩大时长和镜头数 | [VideoEditDialog](../../frontend/src/features/canvas/VideoEditDialog.tsx)、[VideoCompositionService](../../backend/src/main/java/dev/agenvas/task/application/VideoCompositionService.java)、[ADR 0050](../adr/0050-video-postproduction-and-media-samples.md) |
| 工程持久化 | 合成配方固定在 Task/结果来源；当前界面预览来源片段 | 明确的可编辑工程及修订；换第 3 镜后可以再次预览和输出，而不需重新拼装全部配置 | [VideoRangeEditor](../../frontend/src/features/canvas/VideoRangeEditor.tsx)、[ADR 0049](../adr/0049-intelligent-dubbing-composition.md) |
| 语音与字幕 | 已有提音、裁音、声音活动分析、参考音色和配音合成；字幕依赖用户/Agent 提供文本及时间 | 语音识别、自动字幕与校正、台词比对、说话人区分、配音时间对齐；独立评估口型能力 | [ADR 0049](../adr/0049-intelligent-dubbing-composition.md)、[ADR 0052](../adr/0052-audio-trimming-for-voice-references.md)、[ADR 0053](../adr/0053-builtin-agent-local-media-workflow.md) |
| 审片与修订 | 已有双片同步、步进及时间点备注，备注是组件内临时状态 | 保存时间点批注，关联镜头与内容版本；从批注发起修改并标记已解决 | [VideoReviewControls](../../frontend/src/features/canvas/VideoReviewControls.tsx)、[VideoComparisonDialog](../../frontend/src/features/canvas/VideoComparisonDialog.tsx) |
| 参考视频拆解 | 已有图片提示词反推、视频帧与音频样本读取底座 | 导入视频后拆镜头、动作、机位、节奏和声音，产出可编辑分镜与复用提示词 | [AgentMediaSampleService](../../backend/src/main/java/dev/agenvas/llm/application/AgentMediaSampleService.java)、[MediaCanvasCard](../../frontend/src/features/canvas/MediaCanvasCard.tsx) |
| 完整流程复用 | 已有资产、提示词和参考图模板、风格、Skill，以及发布的 ComfyUI/RunningHub 能力 | 保存一组节点、引用、可替换槽位、能力要求和参数为制作模板；预览后复制到新项目 | [规格 §6.14–6.17](../MVP-SPEC.md#614-个人资产库2026-10-01)、[媒体模板设计](../media-templates-design.md) |
| 项目交付 | 已有成片输出、单资源下载及脱敏 JSON 清单；清单不是携带媒体的可恢复工程包 | 素材打包、项目复制、含媒体工程包导入/导出；字幕文件与剪辑软件交接按需求增加 | [导出服务](../../backend/src/main/java/dev/agenvas/export/application/ProjectExportManifestService.java)、[规格 §16.4](../MVP-SPEC.md#164-项目导出清单)、[API 合约](../../contracts/openapi.yaml) |
| 画布效率 | 已有拖拽、框选、连线、整理和节点复制；groupId 有模型字段；标注/蒙版已有局部撤销 | 用户可命名、折叠的分镜组/区域；画布级撤销重做；组复制、搜索定位与批量下载 | [ProjectWorkspacePage](../../frontend/src/features/canvas/ProjectWorkspacePage.tsx)、[canvasStore](../../frontend/src/features/canvas/canvasStore.ts)、[canvasInteraction](../../frontend/src/features/canvas/canvasInteraction.ts) |
| 3D 导演台 | 已有程序化人偶/几何体摆位、物体变换、拍摄机位与截图回画布 | 多机位、骨骼姿态、模型/全景导入、镜头轨迹和参考视频，分项验证下游模型遵从效果 | [ADR 0051](../adr/0051-local-3d-director-workbench.md)、[3D 调研](3d-director-workbench.md) |

当前本地合成限制为 60 秒、12 镜、30 句字幕。应先完善工程与预览，再通过实际性能验证决定放宽范围；不要把限制变化本身当成编辑体验完善。

## 建议顺序与验收示例

### 首先：把分镜变成能操作的制作界面

以一份剧本、两个角色、六至八个镜头为例，用户能在一个界面编辑台词/时长，选择参考和结果，查看每镜状态，审批明确批次，定位失败或待复核镜头。修改第 3 镜台词时，显示其相关配音、视频和成片需要复核，保留其他镜头的已付费结果；后续生成依然需要原有显式授权。

这会扩展 ADR 0054 当前“普通 TEXT，不新增镜头身份”的设计。属于需单独确认并记录的新产品决策，本文不把它描述为已确定架构，也不要求立即引入执行 DAG。

### 其次：完成角色关联与成片编辑

角色资产可包含多个固定版本和明确用途，镜头可选择角色/造型/音色并追踪使用范围。更新资产应由用户预览影响并应用，不能静默改写已冻结输入或历史结果；结构化关联也不保证生成模型绝对保持角色一致。

成片工程可以保存后重开，播放时实际呈现本次选用的片段、字幕和音频，调整某镜后再次预览、输出。优先解决整片可见和反复修改，再按真实需求引入更自由的多轨编辑。

### 接着：减少声音返工并扩大复用

增加 ASR 与字幕校正、台词比对、时间对齐及持久审片批注。声音活动检测只能判断活动区间，不能替代识字、辨人或口型；将声音相似度、台词准确性、字幕可读性和逐帧口型作为不同验收项。

随后交付分镜组、画布级撤销/重做和流程模板。例如把“角色参考 → 关键帧 → 视频 → 配音 → 成片”存为可替换素材的模板，并能复制为独立项目。模板保存与应用不自动取得模型调用授权。

### 按定位安排的后续方向

- 参考视频拆解与改编：对广告、短剧、MV 用户价值较高；应优先产出可检查的镜头拆解，而不是直接承诺一键复刻质量。
- 3D 动态能力：在已有构图基础上增加姿态、多机位和运镜，待分镜生产流程足以承接这些输出后推进。
- 团队与分享：先评估只读预览、工程包和素材交接，再评估多账号权限、协作编辑和批注流程。当前单管理员定位见 [README](../../README.md)。
- 新手成功路径：沿用已有连接配置与诊断，补充可复用示例工程、能力兼容提示和清楚的预计费用；优先验证一套低成本的完整创作流程。
- 社区、积分商城、会员和投放闭环属于新的经营方向；是否建设取决于用户定位，不因竞品具备而自动进入当前优先事项。

## 真实验证的边界

开发清单已经记录真实内置 Agent 的 30 秒成片闭环：在提供台词时间轴的条件下，完成提音、裁剪、三个 10 秒 H3 镜头、审批、归档与本地合成；这不是纯 Mock 能力。见 [开发清单“内置 Agent 音色参考与成片闭环”](../DEVELOPMENT-CHECKLIST.md#2026-10-23-内置-agent-音色参考与成片闭环待验收)。

该记录同时明确：尚未证明自主听音、音色相似度、台词准确性与逐帧口型；文字模型音频输入曾返回不支持错误。不能把已有合成闭环说成没有实现，也不能据视频可解码、音轨非零或引用了同一音频就宣称声音和表演质量已经通过。

本次只做资料和源码对照。要比较两家竞品与 Agenvas 的实际创作效果，需要另用相同剧本、素材和预算进行真实任务验收；此处没有性能、画质或成本胜负结论。
