# OiiOii.ai 画布功能调研

- 调研日期：2026-09-28
- 调研对象：OiiOii.ai 当前官网、官方产品页、官方文章与实际 Web 画布 UI
- 调研目的：记录可用于画布产品比较的公开能力，不把竞品营销文案当作已验证实现

## 1. 证据口径

本文使用以下标签区分证据强度：

- **公开页面直证**：OiiOii 官方官网、FAQ、产品页或官方文章明确写出。
- **实际 UI 直证**：在已登录的免费账户中打开实际画布或资产页，读取当日可见控件与菜单；未执行付费生成。
- **合理推断**：由多个可见入口推断可能的产品行为，但没有完成端到端操作。
- **未验证**：官网未说明，或需要消耗额度、改变项目、邀请他人、发布内容才能确认。

限制：官方产品页带有营销性质；“支持”不等于已验证所有模型、套餐、输入组合和失败路径。本次没有提交生成任务，没有测试并发上限、取消、失败重试、费用扣除、真实多人协作、发布结果或下载文件，也没有验证移动端。

## 2. 总览

OiiOii 的公开定位不是纯节点工作流，而是“Agent 驱动的动画工作区 + 无限画布”。官网将其核心概括为 25+ 图片/视频模型、150+ 风格、并发批量生成、联动编辑、首尾帧与通用图片参考；实际画布则以 tldraw 无限画布承载图片、视频、音频、文字和 3D 相关节点，并在画布内同时提供 Agent 输入、资产库、任务列表和媒体编辑动作。[官方画布产品页](https://www.oiioii.ai/features/canvas)

与官网宣传相比，实际 UI 可直接确认的范围更窄：可以确认入口、节点、菜单和当前项目内容存在；不能仅凭入口确认所有操作都已对免费账户开放或已稳定完成端到端闭环。

## 3. 逐项功能与证据

### 3.1 节点与内容类型

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| 图片、视频、音频、文字节点 | 实际 UI 直证 | “Add...”菜单直接列出 `Image`、`Video`、`Audio`、`Text`；画布中也能看到图片、视频与可编辑 Markdown 文字卡片 | 已确认创建入口存在；未实际新建节点，避免改动用户项目 |
| 3D Director Desk | 实际 UI 直证 | 新增菜单包含 `3D Director Desk` | 未执行，无法确认输入输出和是否需要额外套餐 |
| AI 3D Previs（Beta） | 实际 UI 直证 | 新增菜单包含 `AI 3D Previs Beta` | 仅确认入口与 Beta 标记 |
| 上传节点/素材 | 实际 UI 直证 | 新增菜单包含 `Upload`，空图片/文字卡也显示 Upload 按钮 | 未上传文件 |
| 场景设计输出 | 实际 UI 直证 | 现有画布可见 `Scene Designer` 卡，包含主视图与多视图结果的 Replace/Preview 控件 | 这是项目中可见的设计结果，不代表通用“新增节点”菜单项 |
| 角色与场景资产 | 公开页面直证 + 实际 UI 直证 | 官网称角色、场景可保存为跨项目复用资产；资产页存在 Character、Scenes、Items、Collection、Other 分类 | [角色设计页](https://www.oiioii.ai/features/character-design)、[场景设计页](https://www.oiioii.ai/features/scene-design)；未验证跨账户/团队共享 |

### 3.2 生成方式与模型选择

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| 文字生成图片、文字/图片生成视频 | 公开页面直证 | 官方视频页明确支持 text-to-video、image-to-video；图片页列出多模型图片生成 | [视频生成页](https://www.oiioii.ai/features/video-generation)、[图片生成页](https://www.oiioii.ai/features/image-generation) |
| 视频到视频 | 公开页面直证 | FAQ 称上传视频后可在画布做风格迁移或扩展 | [官方 FAQ](https://www.oiioii.ai/faq)；未实际上传视频验证模型、时长与格式限制 |
| 多模型工作区 | 公开页面直证 | 官方画布页列出 9 个图片模型和 17 个视频模型，并宣称可在同一工作区使用 | [官方画布页](https://www.oiioii.ai/features/canvas)；模型清单可能随日期和套餐变化 |
| 自动选模与自动优化提示词 | 公开页面直证 | 官方称 Agent 可按镜头挑选渲染后端，图片/视频提示词会按模型自动优化 | [工作方式](https://www.oiioii.ai/how-it-works)、[视频生成页](https://www.oiioii.ai/features/video-generation) |
| Agent 对话驱动创作 | 实际 UI 直证 + 公开页面直证 | 画布左下有 `Oii Agent` 输入区，支持拖拽/粘贴图片；官网称从一句话生成脚本、角色、场景、分镜、视频与声音 | [工作方式](https://www.oiioii.ai/how-it-works)；未发起一次完整 Agent 流程 |
| 逐节点/逐镜头直接生成或重生成 | 公开页面直证 | FAQ 明确说可只重生成单个镜头；官方文章称可在画布新增、删除镜头，并单个或全部重生成 | [官方 FAQ](https://www.oiioii.ai/faq)、[官方分镜文章](https://articles.oiioii.ai/one-sentence-to-anime-storyboard-pipeline) |
| 分阶段故事生成 | 公开页面直证 | 可从创意或脚本开始，设置风格、长度、比例、对白语言和情绪；先审阅编辑脚本、角色、场景和分镜，再生成最终视频 | [故事动画页](https://www.oiioii.ai/en/story-anime) |
| 并发批量生成 | 公开页面直证 | 官网宣称图片与视频任务可并行，支持批量生成 | [官方画布页](https://www.oiioii.ai/features/canvas)、[视频生成页](https://www.oiioii.ai/features/video-generation)；未验证免费版并发数和调度策略 |
| 150+ 预设风格 | 公开页面直证 | 官方称风格同时用于图片和视频 | [官方画布页](https://www.oiioii.ai/features/canvas)；未逐一验证可用性 |

### 3.3 画布交互

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| 无限画布、缩放、平移、缩略图 | 实际 UI 直证 + 公开页面直证 | 画布基于 tldraw；可见选择/手形工具、缩放加减、100% 复位、Minimap；官方更新日志称画布可自由排列单个镜头 | [官方画布页](https://www.oiioii.ai/features/canvas)、[更新日志](https://www.oiioii.ai/changelog) |
| 多选、复制、粘贴、创建副本、删除、撤销/重做 | 实际 UI 直证 | 画布工具栏有 Multi-select；设置页列出上述快捷操作 | 仅确认 UI 与快捷键设置，未逐项执行 |
| 分组/解组、展开/收起组、组颜色 | 实际 UI 直证 | 画布设置的 Group 分区列出分组、解组、展开、收起和八种颜色动作 | 未创建组验证持久化 |
| 自由绘制/画笔标注 | 实际 UI 直证 | 工具栏有 Free drawing；图片动作中有 Brush Markup 和 Draw | 未区分两者的持久化与生成输入语义 |
| 概览/场景视图 | 实际 UI 直证 | 画布顶部提供 `Overview` 与 `Scene` 视图切换 | 未深入验证 Scene 视图的结构化规则 |
| 四格/九格分镜预览 | 公开页面直证 | 官方文章称可按镜头复杂度预览四格或九格分镜，并展开编辑 | [官方分镜文章](https://articles.oiioii.ai/one-sentence-to-anime-storyboard-pipeline) |
| 添加/删除镜头 | 公开页面直证 | 官方文章明确支持在画布直接增删镜头 | [官方分镜文章](https://articles.oiioii.ai/one-sentence-to-anime-storyboard-pipeline) |
| 调整镜头顺序、提示词、模型与参考图 | 公开页面直证 | 故事动画页明确允许展开分镜后逐镜头修改这些参数 | [故事动画页](https://www.oiioii.ai/en/story-anime) |
| 联动编辑 | 公开页面直证 | 官网称修改一处可让关联分镜/场景同步更新 | [官方画布页](https://www.oiioii.ai/features/canvas)；未验证依赖图、确认机制、失败回滚与影响范围 |

### 3.4 上下文引用与一致性

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| `@` 引用资源 | 实际 UI 直证 | 画布设置明确列出输入动作 `Reference resource @` | 未发送消息验证可引用哪些节点或资产 |
| 从所选节点创建图片/视频/音频节点 | 实际 UI 直证 | 设置页为三种媒体均显示“References the currently selected nodes” | 可以确认产品存在“所选节点作为输入”的交互设计；未确认引用是否冻结版本 |
| 拖拽/粘贴图片作为 Agent 输入 | 实际 UI 直证 | Agent 输入框占位文本明确提示拖拽/粘贴图片并尝试 Skills、Styles、Assets | 未上传隐私素材 |
| 首帧、尾帧参考 | 公开页面直证 | 官方称可分别提供视频开始与结束画面控制运动 | [视频生成页](https://www.oiioii.ai/features/video-generation) |
| 通用图片参考 | 公开页面直证 | 任意图片可作为风格或内容引导 | [官方画布页](https://www.oiioii.ai/features/canvas) |
| 多参考生成 | 公开页面直证 | 官方文章称可将场景图、角色图和分镜描述一起生成镜头 | [官方分镜文章](https://articles.oiioii.ai/one-sentence-to-anime-storyboard-pipeline) |
| 共享场景图/角色身份/场景元数据 | 公开页面直证 | 官方称角色外观、场景环境和时间/天气/道具元数据传递到下游镜头 | [工作方式](https://www.oiioii.ai/how-it-works) |
| 内部 scene graph | 公开页面直证 | 官方称 Agent 共享统一 scene graph，脚本修改会更新下游输出 | [工作方式](https://www.oiioii.ai/how-it-works)；公开资料没有给出用户可见的数据结构或冲突规则 |

### 3.5 版本、历史与非破坏性编辑

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| 单镜头完整历史与回退到早期 take | 公开页面直证 | 官方分镜文章明确写出“full history to return to an earlier take” | [官方分镜文章](https://articles.oiioii.ai/one-sentence-to-anime-storyboard-pipeline)；实际 UI 未定位到历史面板 |
| 非破坏性编辑 | 公开页面直证 | 官方称所有编辑非破坏，可重生成单个镜头并由 Editor 重新排布时间线 | [工作方式](https://www.oiioii.ai/how-it-works) |
| 重生成后自动重排时间线 | 公开页面直证 | 更新日志将“单镜头重生成后 Editor 自动 reflow timeline”列为 Canvas-based shot editor 能力 | [更新日志](https://www.oiioii.ai/changelog) |
| Replace 与 Preview | 实际 UI 直证 | 场景设计卡的主视图和多视图结果均显示 Replace、Preview | 仅确认替换/预览入口；不能据此证明具体版本保留策略 |
| 项目级版本历史、分支、快照 | 未验证 | 未在实际 UI 或官方功能页发现明确入口 | 不应把“镜头历史”推断为完整项目版本控制 |

### 3.6 媒体编辑能力

以下项目均为 **实际 UI 直证**：画布设置页把它们列为可绑定快捷键的动作；本次未执行，因此不能确认套餐限制、耗费额度、是否生成新版本或是否全部已开放。

- 图片：Smart Edit、Rev-Prompt、Character turnaround、Face turnaround、Props turnaround、Scene grid、Relight、Upscale 2x/4x、Crop、Rotate & Flip、Brush Markup、Keep Product、Keep Portrait、Outpaint、Remove、Angles、Depth Extraction、Draw。
- 视频：Video Reimagine、Video HD、Clip Retake、Trim Clip、Erase Subtitle、Rev-Prompt、Depth Extraction、Separate Audio。
- 音频：MV Creation。
- 官方 FAQ 另称支持视频风格迁移/扩展，官方工作方式页称 Editor 可处理转场、节奏、裁剪、镜头重排以及常见剪辑模式，Sound Engineer 可生成音乐和音效并做 ducking、按节拍匹配剪辑。[官方 FAQ](https://www.oiioii.ai/faq)、[工作方式](https://www.oiioii.ai/how-it-works)

### 3.7 任务状态、费用与执行反馈

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| 画布任务列表 | 实际 UI 直证 | 右下有 `Task List`，空态显示 `No tasks right now`；任务面板空时禁用 | 未提交任务，因此没有观察排队、运行、成功、失败、取消或重试状态 |
| 并行任务 | 公开页面直证 | 官网明确宣传 concurrent generation / batch render | [官方画布页](https://www.oiioii.ai/features/canvas) |
| Credits 与用量入口 | 实际 UI 直证 | 顶部显示剩余 FREE credits；项目 More 菜单包含 `Credits Usage` | 未查看账单明细，也未验证生成前费用预估 |
| 生成时间受模型/分辨率/镜头数/项目长度影响 | 公开页面直证 | FAQ 与工作方式页均明确说明 | [官方 FAQ](https://www.oiioii.ai/faq) |
| 取消、UNKNOWN、显式重试、恢复语义 | 未验证 | 公开页与本次空任务 UI 未给出可靠证据 | 不能假设存在 Agenvas 所要求的 UNKNOWN 与恢复语义 |

### 3.8 资产管理

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| 独立资产页 | 实际 UI 直证 | 顶级导航有 Assets；资产页有 My Assets、Actor Library、Trash | 已登录 UI 观察 |
| 资产分类、搜索、生成、上传、批量上传 | 实际 UI 直证 | 分类包括 Character、Scenes、Items、Collection、Other；可搜索并有 Generate、Upload、Batch Upload | 未执行上传与删除 |
| 画布内资产侧栏 | 实际 UI 直证 | 侧栏在 Canvas、Assets、Actors 间切换；Canvas 内容可按 Images、Videos、Audio、Text、Actors 过滤 | 已确认入口和过滤项 |
| 画布内资产生成/导入 | 实际 UI 直证 | Assets 子页有 Generate、Upload、Batch import，分类含 Characters、Scenes、Items、Collection、Favorites | 未执行 |
| 演员库筛选 | 实际 UI 直证 | Actors 子页提供搜索、Era、Region、More filters | 未验证演员资产来源与授权条件 |
| 角色/场景跨项目复用 | 公开页面直证 | 角色页和场景页均称可在个人资产库中跨项目复用 | [角色设计页](https://www.oiioii.ai/features/character-design)、[场景设计页](https://www.oiioii.ai/features/scene-design) |

### 3.9 分享、协作、发布与导出

| 功能 | 证据级别 | 验证结果 | 证据与限制 |
| --- | --- | --- | --- |
| Share 入口 | 实际 UI 直证 | 项目头部有 Share，点击时出现 `Generating share link...` 提示 | 可确认分享链接入口；未验证链接权限、失效、密码、访问者角色或是否允许编辑 |
| Publish 入口 | 实际 UI 直证 | 项目头部有 Publish 按钮 | 未发布，不能确认发布目标、可见范围与撤回方式 |
| 项目默认私有 | 公开页面直证 | FAQ 称项目默认仅账户本人可见 | [官方 FAQ](https://www.oiioii.ai/faq) |
| 实时多人协作、评论、编辑者角色 | 未验证 | 官方公开页面与本次 UI 没有提供足够证据 | “Share”不能自动等同于多人协同编辑 |
| 导出 MP4 与单项图片 | 公开页面直证 | FAQ 称成片可下载为 MP4，角色表和场景板可导出为图片 | [官方 FAQ](https://www.oiioii.ai/faq) |
| 导出项目源数据/迁移包 | 未验证 | 未发现公开说明 | 不应把媒体下载等同于项目备份或可迁移导出 |

## 4. 对比时最值得关注的已证实能力

1. **节点类型比纯图文视频画布更广**：公开 UI 已包含音频、3D Director Desk 与 Beta 版 AI 3D Previs。
2. **画布不是孤立节点堆放器**：它把 Agent 输入、所选节点引用、`@` 资源引用、首尾帧、通用参考、多参考分镜生成放在同一工作区。
3. **后处理入口密度高**：图片与视频动作覆盖反推提示词、局部/智能编辑、扩图、重打光、高清、补拍、裁剪、去字幕、音频分离和深度提取。
4. **资产复用是一等入口**：独立资产中心与画布侧栏同时存在，并区分角色、场景、物品、合集、演员库和收藏。
5. **公开宣称具备镜头级历史与非破坏编辑**：但实际 UI 未定位到历史面板，仍应把它视为“官方声明已支持”，而不是本次已完成的端到端验证。
6. **任务状态证据很弱**：只能确认 Task List、并发宣传和 credits 用量入口；取消、失败、UNKNOWN、显式重试与灾难恢复语义均未验证。
7. **协作证据也较弱**：可确认分享链接、发布入口和默认私有；不能确认实时协同、权限角色、评论或审核流。

## 5. Agenvas 图片后处理实施状态（2026-09-29）

本节是 Agenvas 的实现核对，不是对 OiiOii 的新增事实判断。当前只考虑图片：

| OiiOii 图片动作 | Agenvas 状态 | 执行位置 |
| --- | --- | --- |
| Smart Edit | 已接入持久任务和参数面板 | OpenAI / Google 图片能力 |
| Relight | 已接入持久任务和参数面板 | OpenAI / Google 图片能力；不降级为亮度滤镜 |
| Outpaint | 已接入目标画幅和补充说明 | OpenAI / Google 图片能力 |
| Depth Extraction | 已接真实模型适配器；Compose 镜像内置固定校验模型 | 本地 Depth Anything V2 Small INT8 ONNX |
| Upscale 2x/4x | 已接入 | 本地双三次插值；不宣称 AI 超分 |
| Crop | 已接入归一化裁剪范围 | 本地 |
| Rotate & Flip | 已接入 90° 旋转和水平镜像；后端也支持垂直镜像 | 本地 |
| Character/Face/Props turnaround（三视图） | 已拆分角色、脸部、道具三联图及场景 2×2 宫格；冻结类型、画幅与可选说明 | OpenAI / Google 图片能力 |
| 图层分离 | 已接主体层/背景层单独输出；主体层要求透明背景能力，不宣称输出 PSD | OpenAI / Google 图片能力 |
| 表情调整、Brush Markup、Remove、Angles、背景移除 | 已接结构化参数或文字说明；Brush Markup 为 AI 生成标注，不是手绘蒙版 | OpenAI / Google 图片能力 |
| Scene grid | 未实现；不属于本轮菜单中的七项图片处理 | — |
| Rev-Prompt | 未实现 | — |

这些操作都固定 CanvasItem 当前图片版本，结果创建新的不可变版本；父版本发生变化时，晚到结果只进入历史。视频去字幕、视频高清、补拍、音频分离等仍不在当前图片范围内。Rev-Prompt 仍未实现。架构决定见 [ADR 0015](../adr/0015-image-post-processing-local-first.md)。

## 6. 不应据此推出的结论

- 不能因为官网列出 25+ 模型，就断言所有模型在所有地区、套餐和时间点均可用。
- 不能因为存在 Replace、完整镜头历史和“非破坏性”宣传，就断言其底层版本模型、并发冲突和自动选用规则与 Agenvas 相同。
- 不能因为存在 Task List 和并发生成，就断言其任务具备可恢复租约、fencing、UNKNOWN 或幂等保障。
- 不能因为存在 Share/Publish，就断言其支持实时多人编辑或细粒度权限。
- 不能因为支持 MP4/图片下载，就断言其支持项目级数据备份和迁移。

## 7. 第一方来源索引

- [OiiOii AI Animation Canvas](https://www.oiioii.ai/features/canvas)
- [OiiOii AI Video Generation](https://www.oiioii.ai/features/video-generation)
- [OiiOii AI Image Generation](https://www.oiioii.ai/features/image-generation)
- [OiiOii AI Character Design](https://www.oiioii.ai/features/character-design)
- [OiiOii AI Scene Design](https://www.oiioii.ai/features/scene-design)
- [How OiiOii Works](https://www.oiioii.ai/how-it-works)
- [OiiOii FAQ](https://www.oiioii.ai/faq)
- [OiiOii Changelog](https://www.oiioii.ai/changelog)
- [OiiOii AI Story Animation Generator](https://www.oiioii.ai/en/story-anime)
- [AI Storyboard to Video: Inside OiiOii's Script-to-Screen Pipeline](https://articles.oiioii.ai/one-sentence-to-anime-storyboard-pipeline)
- [How to Create an Animated Video with AI in 5 Steps](https://www.oiioii.ai/blog/how-to-create-animated-video-ai)
- 实际 UI：2026-09-28 使用已登录免费账户检查 `oiioii.ai` 的画布与资产页；未记录项目专属 URL，避免把账户资源标识写入仓库。
