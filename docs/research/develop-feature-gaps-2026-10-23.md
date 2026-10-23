# 最新 develop 的功能补全建议

核对日期：2026-10-23。当前源码快照：`develop` / `96b4dc4`；本轮大画布编辑增量已进入 develop。本文把 develop 基线与本轮画布增量分开记录，不改变规格、ADR 或排期；不以研究报告宣称部署、全量测试或真实 Provider 验证。

## 结论与排序

角色一致性管理、可操作分镜制作台、导演直接落分镜和按镜修改、镜头媒体提案及审批/候选回流已经进入 develop。本轮画布增量补齐了大画布的命名/折叠分组、独立多选、组复制、普通复制以及会话内 50 步撤销/重做（见 [ADR 0059](../adr/0059-canvas-groups-and-edit-history.md)）；这些基础行为不再列为待补。后续价值集中在把已有制作能力连接为能保存、预览、修改、复用和交接的完整工程。

| 建议顺序 | 待补全的用户行为 | 当前已有基础 | 可检查的验收结果 |
| --- | --- | --- | --- |
| 1 | **可保存成片工程与整片预览** | 有镜头关联成片、片段剪辑配置、本地字幕/配音/音乐合成和 MP4 输出；任务保留合成来源 | 保存并关闭后重开，保留片段顺序、入出点、音频和字幕；替换第 3 镜后，预览实际混合声音和字幕的整片，再输出新版本 |
| 2 | **角色目录与分镜工作流直接衔接** | 有角色档案、多造型、视图、音色以及媒体节点显式应用；分镜可维护精确素材参考 | 在分镜中选择角色/造型/音色，准备节点时能预览并应用角色配置；更新角色提示受影响镜头，保留旧任务和已选结果 |
| 3 | **语音识别、字幕与对白校正** | 已有提音、裁音、声音活动窗口、显式剧情锚点和有界配音排布 | 从实际语音识别字幕，逐句校正、比较台词、区分说话人并可视调整时间；识别、音色相似度和口型分别验收 |
| 4 | **持久审片与修订闭环** | 已有双片同步比较、逐帧步进和会话内时间点备注 | 关闭重开后保留批注，批注绑定镜头和精确内容版本，可发起修改、标记解决，并看出旧版批注 |
| 5 | **制作流程模板与工程交付** | 有单节点媒体模板、风格、Skill、跨项目资源目录与个人资产库；导出包含角色/分镜的安全 JSON 清单 | 一组节点、关系、参数和可替换素材能保存为流程模板；项目可复制；含媒体工程包能在另一安装导入并恢复，不携带密钥或自动生成授权 |
| 6 | **大画布组织和操作历史** | develop 有框选、拖拽、对齐、节点复制和 `groupId` 字段；本轮画布增量已实现命名/折叠分组、独立多选、组复制、普通复制及会话内 50 步撤销/重做 | 已覆盖上述基础交互；后续明确补充删除/连线的撤销边界、跨刷新历史、从搜索结果直接定位和批量交付 |
| 7 | **参考视频到可编辑分镜方案** | 导演能读精确视频的有限帧/音频样本，并能写入分镜 | 从参考视频检查切镜、时长、动作、机位、节奏及声音，生成带来源时间区间的可编辑分镜；替换角色/产品后逐镜审核生成计划 |
| 8 | **3D 姿态、多机位和动态运镜** | 有本地三维摆位、物体变换、机位预设、PNG 拍摄、场景 JSON 导入/导出及图片视角操作 | 保存姿态与多个机位，编辑/预览镜头路径并导出有界参考视频；分项验证下游生成模型对构图与动作的遵从 |

排序是基于自托管、个人创作和持续修改成本的判断，不是竞品功能数量排名。前四项优先完成制作闭环；3D 动态能力、团队协作与社区按实际用户需求推进。

## 已有能力及边界

### 角色和分镜基础已补齐

当前分支已包含角色档案与不可变修订、多造型/视图/音色、节点角色关联、显式预览应用、独立复核和冻结来源，见 [ADR 0055](../adr/0055-character-consistency-management.md)。分镜制作台保存镜头顺序、说明、对白、时长、参考及选用结果，并支持生产状态、按阶段批量准备/生成与待复核；导演可读取和直接修改分镜，媒体提案冻结镜头身份并回流候选，见 [ADR 0056](../adr/0056-storyboard-production-workbench.md)、[ADR 0057](../adr/0057-director-storyboard-workbench-tools.md)、[ADR 0058](../adr/0058-shot-linked-media-approvals.md)。这不等于真实模型的角色、音色或口型质量已经通过本次验证。

剩余衔接主要是角色仍从媒体节点单独应用，分镜层尚无直接选择角色档案/造型并在准备节点时传递的完整入口。精确图片参考与结构化角色选择是两种能力，不能因前者存在就认为后者已完整。

现有分镜还可继续完善以下行为，而不必重建基础制作台：

- **准备节点时预览输入映射**：当前快捷准备把说明/对白填入提示词，`mediaInputs` 为空，能力和生成时长由用户在节点继续选择；分镜参考、预计时长与角色配置可增加显式映射预览，不能静默当作模型输入，见 [storyboardNodePreparation](../../frontend/src/features/canvas/storyboardNodePreparation.ts:20)。
- **历史对比与恢复**：后端已有不可变分镜修订和分页查询，前端可补修订浏览、差异和以新修订恢复，见 [StoryboardService](../../backend/src/main/java/dev/agenvas/storyboard/application/StoryboardService.java:55)。
- **大项目编辑效率**：制作台审批查询当前有 100 条上限；整板 CAS 会令不同镜头的并发编辑也产生冲突。后续可按实际规模补审批历史分页与更细编辑冲突处理，现有上限和保护机制不构成基础能力缺失，见 [StoryboardService](../../backend/src/main/java/dev/agenvas/storyboard/application/StoryboardService.java:87)、[分镜规格](../MVP-SPEC.md#620-分镜制作台2026-10-23)。

### 成片、语音和审片

当前分镜的 [Composition](../../backend/src/main/java/dev/agenvas/storyboard/domain/Storyboard.java:20) 是结果关联与复核标记，不是独立、可重开的剪辑工程。剪辑弹窗用组件会话状态维护配置，提交现有本地合成任务；片段预览不等于应用了字幕、配音及音乐后的整片预览。当前有界合成为 **60 秒、12 镜、30 句配音、100 条字幕**，见 [API 合约](../../contracts/openapi.yaml:2808)、[VideoEditDialog](../../frontend/src/features/canvas/VideoEditDialog.tsx:30)、[VideoCompositionService](../../backend/src/main/java/dev/agenvas/task/application/VideoCompositionService.java:213)。先完善工程与预览，再按性能验收决定扩大限制。最小验收可采用六镜、字幕、配音和音乐的工程：保存、关闭重开、替换第 3 镜、播放整片并输出。

声音活动检测、显式锚点和保守配音排布已经存在，不能写成“没有任何时间对齐”。这些能力仍不等于 ASR、说话人识别、对白逐字校正或口型同步。审片备注当前由组件临时保存，需补后端持久化与版本/镜头关联，见 [VideoCompositionPlanner](../../backend/src/main/java/dev/agenvas/provider/application/VideoCompositionPlanner.java:63)、[VideoRangeEditor](../../frontend/src/features/canvas/VideoRangeEditor.tsx:28)、[VideoReviewControls](../../frontend/src/features/canvas/VideoReviewControls.tsx:26)、[VideoComparisonDialog](../../frontend/src/features/canvas/VideoComparisonDialog.tsx)、[ADR 0049](../adr/0049-intelligent-dubbing-composition.md)。

### 复用、工程包和画布效率

- 现有 [MediaTemplate](../../backend/src/main/java/dev/agenvas/mediatemplate/domain/MediaTemplate.java) 是图片/视频提示词与有序参考图片预设；第三方视频模板还可描述多种参考输入及前置图片阶段，但不自动执行整条生成流水线。它们不是包含多个节点、引用关系、可替换槽位和能力参数的制作流程模板，见 [模板设计](../media-templates-design.md)。ComfyUI/RunningHub 发布能力也不能代替项目层流程模板。
- 跨项目媒体结果目录和个人资产库已经可以复用素材，见 [API 合约的 resources/library 接口](../../contracts/openapi.yaml)、[LibraryService](../../backend/src/main/java/dev/agenvas/library/application/LibraryService.java)。剩余重点是复制整个制作结构及可恢复交付，不能泛称“没有跨项目复用”。
- develop 基线导出 schema 10 包含安全项目内容、画布、草稿、角色、分镜及分镜修订；本轮画布增量升为 schema 11 并加入分组元数据。媒体仍只有元数据，不携带媒体字节，也没有配套工程导入/项目复制接口；它仍是安全 JSON 清单，不是独立可恢复的含媒体工程包，见 [ProjectExportManifestService](../../backend/src/main/java/dev/agenvas/export/application/ProjectExportManifestService.java)、[导出控制器](../../backend/src/main/java/dev/agenvas/export/api/ProjectExportManifestController.java)、[ProjectController](../../backend/src/main/java/dev/agenvas/project/api/ProjectController.java)。
- `groupId` 的持久字段与布局命令已在本轮画布增量上接入用户可操作的命名/折叠分组、独立多选、组复制和普通复制；编辑历史提供会话内 50 步撤销/重做，并以 [ADR 0059](../adr/0059-canvas-groups-and-edit-history.md) 限定边界。Chrome 专项覆盖 100 个合成文本节点、两条连线的分组折叠/内部边隐藏与外部路径保留；前端组件和回归证据记录在 [开发清单](../DEVELOPMENT-CHECKLIST.md) 对应条目。该范围仍不包含删除节点或连线的撤销、跨刷新恢复历史；资源抽屉已有名称搜索，后续可补从搜索结果直接定位和批量交付。见 [ProjectWorkspacePage](../../frontend/src/features/canvas/ProjectWorkspacePage.tsx)、[canvasStore](../../frontend/src/features/canvas/canvasStore.ts)、[canvasInteraction](../../frontend/src/features/canvas/canvasInteraction.ts)。

### 参考视频与 3D

导演的 `read_media_sample` 已能查看一个授权精确视频/音频版本的最多 4 个帧时间点和/或 15 秒音频样本，且当前也能写入结构化分镜。尚未发现面向参考片的自动切镜、覆盖检查、动作/机位/节奏结构化分析、带时间区间批量导入分镜的专门闭环；有限采样不证明连续动作或每次切镜都看全，音频是否可用还取决于实际文字模型能力。见 [AgentMediaSampleService](../../backend/src/main/java/dev/agenvas/llm/application/AgentMediaSampleService.java)、[ToolRegistry](../../backend/src/main/java/dev/agenvas/llm/application/ToolRegistry.java)。

当前 3D 场景 schema v2 只保存单一拍摄相机和最多 32 个程序化人偶/几何对象，支持位置、朝向、三轴比例、颜色与焦距等参数。工作台的导演视角/拍摄视角切换与机位预设不是多个可保存拍摄机位；目前没有骨骼姿态、动作时间线、镜头路径或视频导出。静态拍摄保存和视角生成入口已经存在，不能把整个 3D 能力列为缺失。见 [directorScene](../../frontend/src/features/canvas/directorScene.ts)、[DirectorWorkbench](../../frontend/src/features/canvas/DirectorWorkbench.tsx)、[ADR 0051](../adr/0051-local-3d-director-workbench.md)。

## 竞品一手证据复核

本次只读取公开官方来源，未登录、付费、调用生成接口或体验真实输出；公开文档证明的是描述的产品行为，不证明质量、可靠性、成本或角色一致性。此前三份报告保留来源入口和历史快照，见 [Topview 调研](topview-capabilities-2026-10-23.md)、[LibTV 调研](libtv-capabilities-2026-10-23.md)、[原差距分析](topview-libtv-gap-analysis-2026-10-23.md)。

| 对照方向 | 本次可读一手证据 | 使用边界 |
| --- | --- | --- |
| 分组与复用 | Topview 文档描述多选后 Group/Ungroup、自动整理、图层定位及 Save to Shared Assets。[组织画布](https://www.topview.ai/academy/organize-canvas) | 证明素材分组与跨项目素材复用；不证明通用参数化制作流程模板或可移植工程包 |
| 操作历史 | Topview 文档列出 Ctrl + Z 与 Ctrl + Shift + Z。[画布基础](https://www.topview.ai/academy/what-is-canvas) | 未实测撤销覆盖范围、跨刷新保存及外部费用语义 |
| 空间预演 | Topview 文档描述姿态、角色路径、动作、多个机位、相机运动和参考视频导出。[空间控制](https://www.topview.ai/academy/spatial-control) | 是具体操作文档；下游生成遵从和性能未经本次验证 |
| 参考视频改编 | Topview 指南描述短参考视频/产品图输入、先审阅调整逐镜提示词、逐镜重生成和时间线导出。[Video Agent](https://www.topview.ai/guides/video-agent) | 属于产品营销视频工作流；“更高质量/更一致”为官方宣传，未实测 |
| LibTV 空间方向 | LibTV 专页宣传 Agent 搭景、站位与手绘轨迹运镜。[3D-BOX](https://www.liblib.tv/3d-box) | 只能证明官方公开表述，不能由此推断姿态、工程格式、输出规格或完整可用性 |

Topview Academy 的直接页面打开在本次读取时只返回标题，搜索工具能返回同一官方 URL 的正文；据此核对公开说明，未把缓存读取视为产品实测。LibTV 其他复杂功能缺少可读官方细节，不写成“两家均已完整具备”。

## 本轮验证范围

- 已读取 `develop` `96b4dc4` 源码、OpenAPI、现行 ADR 和历史研究，并把本轮画布增量单独核对；按用户实际操作闭环区分已实现与待补。
- 本轮证据包括 Chrome 100 节点专项、45 项组件测试、18 项新增回归、既有 136 项画布回归、事件 53 项，以及 PostgreSQL 新增 11 项、复制 4 项、迁移 1 项和相关专项复验；这些是本轮实现的定向证据，不等于全量测试、部署或真实 Provider 验证。
- 待补范围保留为：删除/连线撤销、跨刷新历史、搜索直接定位和批量交付；未把组命名、折叠、组复制或基础 undo/redo 重复列为缺口。另见 [开发清单](../DEVELOPMENT-CHECKLIST.md) 与 [ADR 0059](../adr/0059-canvas-groups-and-edit-history.md)。
- 本文记录 develop 基线、本轮画布实现和剩余建议；具体应用、合约、迁移与验收以规格、ADR 和开发清单为准。本轮画布增量已随本次合并进入 develop。
