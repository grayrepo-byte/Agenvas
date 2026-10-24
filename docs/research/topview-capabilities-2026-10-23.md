# Topview 产品能力核对

核对日期：2026-10-23。范围是 `topview.ai` 产品及其 `docs.topview.ai` 文档；依据第一方公开页面、Canvas Academy 与官方指南。本次未登录、未付费生成、未执行其 API，也未亲测生成质量。下文的“文档确认”表示官方已公开具体操作或接口，不代表本次实际验收通过。Agenvas 的现状由另行代码核查确认，本文件不根据竞品宣传直接判断本项目缺失。

## 可确认的产品闭环

Topview 把多场景视频创作组织为“创意 → 故事 → 资产 → 分镜 → 局部修订 → 成片”。画布可并置参考、角色、场景、分镜、图片和视频变体，Agent 负责规划并组织资产。这是官方产品定位；其独特价值应按创作闭环评估，而不是只统计模型数量。[AI Canvas](https://www.topview.ai/canvas)

| 能力 | 第一方证据与具体行为 | 判断边界 |
| --- | --- | --- |
| Agent 批量落画布 | Agent 流式返回角色、产品、分镜、图片、视频、音频、音乐等卡片；答复完成后批量插入画布，资产先于依赖它的分镜及媒体；显示 Pending / Applied，自动插入失败可手动重试。[Academy：Agent 创建节点](https://www.topview.ai/academy/agent-create-nodes) | 已公开操作流程；不据此推断其审批、安全或失败恢复语义。 |
| 结构化节点和可复用资产 | 官方列出 Image、Video、Audio、Text、Asset、Story Scene、Whiteboard、3D Builder、3D World、Group；Asset 包含角色、风格、物件、环境、产品，Story Scene 包含镜头描述和分镜图。[Academy：节点类型](https://www.topview.ai/academy/what-is-node) | 角色/场景是结构化创作资产，不只是普通图片名称。 |
| 脚本到分镜 | 可从剧本、场景描述、梗概或创意生成有序分镜；可编辑描述、局部重新生成、下载画格或共享 board 链接。[Storyboard Generator](https://www.topview.ai/story-board) | 公开产品页；参考数量等限制与画布入口可能不同，不视为同一接口。 |
| 图到分镜及局部改图 | 图片节点可开分镜面板，设置故事、关键时刻、画幅、目标时长与参考图；还提供局部重绘、表达控制、重打光、裁切、旋转、放大及涂画编辑。多数操作产出新节点。[Academy：图片生成和编辑](https://www.topview.ai/academy/image-gen-edit) | 模型可用性影响具体入口；不能保证模型遵从角色或风格。 |
| 多集剧情制作 | Drama Studio 以 Agent 加结构化工作台维护大纲、集、角色、地点、道具、剧本和 beat sheet；可从分镜进入带提示词、引用、视频版本、延续、后处理与导出的制作区。[Drama Studio](https://www.topview.ai/drama-studio) | 页面出现 V2 Beta；声音/字幕选项取决于制作路径与模型。宣传的连续性改善未经本次实测。 |
| 剧本/小说导入与阶段评审 | 可从想法、剧本或小说开始，选择单集或系列；角色、地点、道具可编辑，Project Board 可复用图/视频/音频；官方明确需要用户逐步确认方向和产出。[AI Drama App](https://www.topview.ai/ai-drama-app) | 不应概括为“输入小说后无人审核自动生成整部剧”。 |
| 参考视频复刻 | Video Agent V2 接收产品图、参考图/短视频和制作要求，先生成可审阅的各镜头提示词；支持仅插入或插入并生成、添加场景、逐镜修改和重生成，最后进入时间线导出。[官方 Video Agent 指南](https://www.topview.ai/guides/video-agent) | 属于产品广告工作流；“更高质量/更一致”是官方声称。旧指南的输入时长不外推至全部入口。 |
| 剪辑成片 | Canvas 与 Timeline 同项目切换；可多选视频送入时间线、拖入图/视频，调顺序、切割、音量、速度、缩放、旋转、淡入淡出、静音及音频分离；可导出整片、所有片段、选区和 ZIP。[Academy：Timeline](https://www.topview.ai/academy/timeline) | 这是具体成片操作文档；没有本次编码、性能、分辨率验收。 |
| 角色外观与声音复用 | Academy 的官方练习包括创建角色资产、设定角色声音、四个镜头分别 @ 引用该角色、检查分镜外观一致性，再生成视频检查声音一致性。[Academy：团队创作](https://www.topview.ai/academy/team-collab) | 证明有跨镜头引用和声音设置工作流，不能证明所有输出身份零漂移。 |
| 配音、克隆、口型与字幕 | Avatar 可选公共/自定义克隆声音，输入文字或导入音频驱动口型；可加停顿、调整读音、选择字幕样式，部分模式接受动作提示。[官方 AI Avatar 指南](https://www.topview.ai/guides/ai-avatar) | 数字人口播能力与普通视频生成中的音画同步是不同路径。 |
| 独立 TTS | 可按语言、年龄、口音选声音，将脚本转为音频。[官方 AI Audio 指南](https://www.topview.ai/guides/ai-audio) | 不把有音频节点等同于完整对白、字幕或混音能力。 |
| 团队与共享资产 | 画布显示他人光标、头像、名称并同步节点变化；有节点锁、占用提示和接管会话；共享资产可进入别的画布。[Academy：团队创作](https://www.topview.ai/academy/team-collab) | 同页明确当前一次仅一人编辑，因此不能按营销页的“多人实时编辑”推断任意并发编辑。 |
| 审阅协作 | AI Board 官方指南提供 viewer/editor 分享权限、素材星级、评论、评论生成待办并解决，以及按类型/工具/评分筛选。[官方 AI Board 指南](https://www.topview.ai/guides/ai-board) | Board 与新 Canvas 页面描述不同，不能直接认定这些审阅功能全部存在于每个工作区。 |
| 跨项目素材复用 | 可分组、自动排列、图层定位、前后层级，并将素材存入 Shared Assets 用于其他项目。[Academy：组织画布](https://www.topview.ai/academy/organize-canvas) | 素材复用已有文档；完整工作流模板参数化、导入导出和模板市场未由此证明。 |
| 画布撤销/重做 | 画布文档列出 Ctrl + Z 撤销、Ctrl + Shift + Z 重做，以及多选、平移、缩放与快捷键表。[Academy：画布基础](https://www.topview.ai/academy/what-is-canvas) | 已有操作入口文档；撤销范围、历史持久化与异步生成能否撤销未经本次验证。 |
| 商品模板与多角度出图 | 产品摄影/试穿可从模板或 board 选素材；图片工具指南描述批量生成多个角度，随后选择首尾帧生成视频。[官方 AI Image 指南](https://www.topview.ai/guides/ai-image) | 适合参考电商垂直场景，不等同于通用可执行工作流模板。 |
| 空间导演 | 3D Builder 可手动摆人物/道具/摄影机，支持姿态、动作、路径、机位和运镜；可截图、渲染视频、剪选多个机位后交给 AI Video。3D World 是独立的 AI 场景生成路径。[Academy：空间控制](https://www.topview.ai/academy/spatial-control) | 官方明确两种 3D 工具数据不互通；3D World 只能截图进入下游，不能 @、加入 Agent 或保存共享资产。 |
| 可编辑涂画参考 | 白板支持笔、箭头、形状、文字、图像、画幅和撤销；源对象自动保存，导出 PNG 成为可引用图片节点。[Academy：Whiteboard](https://www.topview.ai/academy/whiteboard) | 官方明确不支持多人同时画图；“导出”与源稿保存是两件事。 |

## 接口证据与公开宣传的区分

公开 API 文档有数字人口播的“提交任务 → 查询预览 → 导出高清口型合成结果”，以及 Avatar、Voice 查询接口。[Avatar Marketing API 起步](https://docs.topview.ai/reference/getting-started-1)；视频 Avatar 文档允许已有模板或传入自己的视频，输入脚本/音频并查询合成任务。[Video Avatar API 起步](https://docs.topview.ai/reference/getting-started-2)。这能支持“某些能力存在可编程接口”的结论，不能推出 Canvas、Drama 全部领域对象都有公共 API。

官方还发布面向外部 Agent 的 Skill，包含视频、图片、Avatar、TTS、声音查询/克隆，结果回到 Board 管理。[Topview Skill](https://www.topview.ai/skill)。本次只读取介绍，没有安装或执行；外部 Agent 集成与产品内 Agent 应分开比较。

批量广告变体、跨平台格式和快速 A/B 创意生产出现在官方广告用例页，但未在本次确认对应任务模型、批次上限或导出参数。[Advertising 用例](https://www.topview.ai/use-cases/advertising)。不采用官网的收益、转化率、生产速度、价格比例或“最强”等营销描述作为能力验收证据。

## 域名与证据限制

- 检索中存在 [topview.pro](https://www.topview.pro/en)，其页面采用 TopView 名称、相似文案及桌面 App 下载入口。本次未找到 `topview.ai` 官方链接为其背书，因此不把 `.pro` 视为已经证实的官方域名，不用其下载、模板、模型列表或桌面能力填充比较结果。
- `topview.ai` 的旧 AI Board 指南、新 Canvas Academy 与 Drama 页面可能描述不同入口或迭代阶段；发生冲突时优先引用更具体的操作限制，并保留入口名称。
- 产品可用性、订阅权限、错误恢复、真实输出质量、角色/声音一致性、项目导出可移植性、自托管和隐私处理均未亲测。公开页面不能证明其内部实现、数据持久化或并发一致性。
- 本次不判断商标归属，不验证网页模型清单真伪，也不采用其第三方模型规格作为该模型官方规格。

## 可用于 Agenvas 差距分析的方向

以下是对上述证据的产品判断，需与 Agenvas 的当前代码逐项交叉确认后排序：

1. **从素材生成到可交付成片**：比较时间线、音轨、裁切、镜头顺序/时长、字幕、声音与导出是否形成完整链路；仅能生成多个视频节点还不能替代这条闭环。
2. **从通用节点到结构化剧本/镜头**：比较分镜能否保存稳定顺序、镜头说明、对白、参考资产和选用版本，是否可局部修订并进入成片。可先做短片切片，再决定是否需要多集剧情系统。
3. **从引用图片到可复用角色/场景资产**：比较角色外观、服装、声音及场景的显式记录、跨镜头引用与跨项目复用；一致性要分别验证工作流和真实模型输出。
4. **从参考素材到复刻方案**：比较参考视频分析能否产出可编辑的镜头、节奏和提示词计划，再替换为自有商品/角色并逐镜生成；保留人工审核生成计划的环节。
5. **把现有生成能力包装为创作动作**：比较局部重绘、表情、视角、放大、首尾帧等能力是否在节点附近可发现并保留源版本；无需仅为对齐竞品重复增加 Provider。
6. **复用与交付评审**：共享素材库、可复用模板、只读分享、评论待办、角色权限是不同层次，分别确认用户场景；多人同步不自动等于多人同时编辑。
7. **空间导演按需求扩展**：若 Agenvas 已有基础 3D 导演台，继续比较多机位、姿态/动作、路径、视频参考导出；不因竞品有 AI 3D World 就直接引入额外远程依赖。

本次只新增研究文档，未改应用、合约或迁移；测试未运行。
