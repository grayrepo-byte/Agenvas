# 画布图稿实现的视觉验收

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-340be306-96f4-45fd-9ecc-ed9b7aea9f29.png`
- source dimensions: 1736 × 944 px；黑色桌面画布、空媒体/已生成图片/扩展菜单/Prompt 编辑器。
- implementation route: `http://localhost:5173/projects/<projectId>`。
- implementation screenshot path: unavailable。
- viewport / implementation CSS size / density normalization: 未取得浏览器截图，未验证。
- full-view / focused region comparison evidence: 未取得同视口实现截图，未进行并排比较。

## 已实施的对应关系

纯媒体表面、空态图标/上传、缩略图与打开原文件、选中浮动工具栏、扩展菜单、Prompt 输入、模型菜单、参数摘要和粉色运行按钮。已有 API 不支持的视频上传、图片扩展能力与每草稿尺寸/画质覆盖，采用明确的生成入口、禁用标记或只读参数，而不伪装执行。

## 视觉验收阻断

内置浏览器访问 `http://localhost:5173` 和 `http://127.0.0.1:5173` 均报 `net::ERR_BLOCKED_BY_CLIENT`；备用 Chrome 同样受阻。尝试原生 Chrome 窗口返回 `Computer Use permissions are not granted`。未绕过浏览器限制，未使用其他浏览器控制技术。

字体与字号/换行、间距及工具栏边缘避让、颜色对比、实际缩略图清晰度、文案排布这五项视觉表面都仍需浏览器核对。菜单、输入、运行、版本显示的组件测试不等于浏览器视觉验收。浏览器控制台也未检查。

## Comparison history

尚无可完成的视觉比较迭代，无截图支持的通过结论。

## Implementation checklist

- 打开运行中的本地工作区，在 1736×944 和最小支持宽度 1280px 下检查空/有图、选择、菜单和生成状态。
- 截取实现与原图的相同区域并排比较，修正 P0/P1/P2 差异后重新截取。
- 核对工具栏靠近画布边缘、长模型名称、失败/UNKNOWN 和减少动态效果。
- 检查浏览器控制台与真实指针操作。

final result: blocked


## Agent 聊天卡片增量（2026-09-26）

- visual source: https://www.beautifului.dev/ 的黑色 Chat / Approval Card / Task Rows；已实际读取其 MIT 源码，并通过浏览器查看黑色 Chat 面板。
- 实现组件：`AgentChatCard`、`AgentRunConversation`、`AgentChatPrimitives` 与四个 `presentation="chat"` 审批/恢复面板。
- 新卡片默认 460 × 600，最小 360 × 420；旧卡片的前端尺寸投影保证输入区与可滚动对话区可用。头部、上下文入口和输入区固定，对话内容独立滚动。
- 持久消息、提交时的业务动作摘要、真实任务状态和审批操作来自 API；不采用示例中的虚构思考文字或计时自动完成效果。
- 本次内置浏览器访问 `http://localhost:5173` 再次返回 `net::ERR_BLOCKED_BY_CLIENT`，未取得实现截图。文字/图标对齐、长审批内容滚动、实际缩放尺寸和控制台仍未完成浏览器验收；不以组件测试替代视觉结论。
- 实际验收需覆盖空对话、发送→运行前确认、真实公开回复、计划审批、失败/UNKNOWN、查看历史后返回最新任务，以及从360px宽度调整尺寸。

Agent chat visual result: blocked

## 持久会话增量（2026-09-26）

- 同一张 Agent 卡片按会话分组多轮消息，提供新建、分页会话列表与切换；刷新恢复服务端保存的当前会话。
- 每个会话保留独立输入草稿；切换会话不会取消正在运行的任务，卡片提供返回运行会话入口处理原审批。
- 本次再次通过内置浏览器访问 `http://localhost:5173`，仍返回 `net::ERR_BLOCKED_BY_CLIENT`；未获取实现截图，未完成真实浏览器与控制台验收。
- 浏览器补验应覆盖连续发送、刷新恢复、新会话空记忆、旧会话续聊、长记录加载，以及提交延迟期间切换会话。

Conversation visual result: blocked

## 其他媒体与内容节点增量（2026-09-26）

- 视觉基准：原始 1736×944 图稿与现有 MediaCanvasCard；Beautiful UI 的 ContextCards、SelectionActions、PromptBar、ChatComposer、TaskRows MIT 源码已实际读取并保留归属。
- 文字、角色、场景、镜头统一黑色 14px 圆角内容表面、选中工具栏、可滚动正文、类型/版本/引用标签；底部编辑器约 680×275px，固定头尾、内部滚动。已有卡片尺寸保留。
- 视频沿用纯媒体表面，首帧弹层以真实归档缩略图展示精确版本；点击后才加载视频，等待与播放错误独立展示，支持重试及返回封面。
- 本次已确认本地 5173 端口监听，再通过内置浏览器访问 `http://localhost:5173`，仍返回 `net::ERR_BLOCKED_BY_CLIENT`。未取得实现截图；未检查真实视频解码、控制台或指针交互。
- 待浏览器补验：1736×944 与 1280px 宽度下六类 Artifact、长正文滚动、顶部操作与底部关闭按钮、缩放/最小尺寸、首帧历史版本弹层、真实播放失败重试、冲突后草稿和键盘焦点。不能以组件测试代替这些视觉结论。

Other nodes visual result: blocked

## 画布外页面增量（2026-09-26）

- 视觉基准：[Beautiful UI](https://www.beautifului.dev/) 黑色主题；本轮实际打开并查看 Sidebar 示例，读取 SidebarNav / ContextCards / RecordsTable 源码；像素加载动画直接复用已改编的 LoadingState。共享模块保留 MIT 归属。
- 实现范围：`/login`、`/setup`、`/projects`、`/settings/providers`（含 `/settings/llm` 别名）、`/settings/media` 与 `/settings/general`。深色分层面板、细边框、紧凑状态行和粉色主操作沿用画布视觉体系。
- 桌面使用 224px 可折叠侧栏；640px 以下改为顶部导航。项目双列卡片与设置表单随视口收为单列。共享键盘焦点样式、跳转正文链接与减少动态效果规则。
- 本次确认本地 5173 端口监听后，内置浏览器打开 `http://localhost:5173/login` 仍报 `net::ERR_BLOCKED_BY_CLIENT`。未取得实现截图，未进行像素对比、真实键盘/指针或控制台验收。
- 待补验：桌面及窄屏登录/初始化、侧栏折叠、长项目名和分页、能力参数展开、保存冲突和重试、系统状态长文案、改密等待态，以及页面跳转后的焦点与滚动。组件测试与静态构建不替代这些视觉检查。

Outside-canvas visual result: blocked

## 节点选中与图片比例修正（2026-09-26）

- 根据用户补充，工具栏绑定对应节点上方；图片完整显示，节点按图片比例调整自身尺寸，不采用 cover 裁切。
- 移除 Artifact 与 Agent 未选中的外边框；选中使用贴合卡片圆角且不占内部空间的外圈。缩放边线和八个控制点透明，保留边缘与角落的拖动热区及缩放光标，不影响连线端点；预览继承卡片圆角。
- 本轮内置浏览器打开 `http://localhost:5173/projects` 仍返回 `net::ERR_BLOCKED_BY_CLIENT`，未获得实际画布截图。React Flow 坐标回归与布局单元测试不能替代截图验收。
- 进一步排查：失败标签停留在 `about:blank`，空白页截图成功，本地端口仍在监听。可确认页面导航被拦截，尚无截图权限不足的证据；浏览器及应用日志未提供具体拦截原因。
- 待补验：横图、竖图和方图切换后的节点尺寸，缩略图完整性及边缘贴合，拖动/缩放/对齐/刷新后的比例，视口缩放与节点贴边时的工具栏，以及选中/未选中和锁定节点的外观。

Node-selection visual result: blocked

### 访问地址更正

当前运行的 Docker Web 入口为 `http://localhost:8088`，5173 为迁移前遗留 Vite 服务。按用户建议使用 Chrome 访问 8088 后，已成功加载并截图 Agenvas 登录页；先前访问错误端口的失败不能用于判断整个本地项目无法访问。截图权限正常，未修改浏览器权限。画布视觉检查尚待完成登录，并确认运行前端包含当前工作区改动；登录页截图不作为节点交互验收证据。

### 本地前端重新启动后的实测

按用户要求停止旧 Vite，使用当时的 Next.js dev server 在 `127.0.0.1:5173` 启动开发前端（该构建于同日回退到 Vite，见 [ADR 0011](docs/adr/0011-revert-to-vite.md)；本节结论属 UI 层，不受构建器影响）。Chrome“日常”实例使用已有 `127.0.0.1` 登录会话成功打开项目画布，完成选中图片和视口平移截图：节点无白色缩放点，图片完整显示、选中外圈贴合圆角，工具栏随视口平移且位于节点上方。Docker 的 8088 服务保持运行。当前基础截图验证已完成，其他图片比例、节点尺寸拖拽、锁定与窄屏回归仍未验证；节点靠近画布上边缘时工具栏会被裁切，当前未增加贴边避让。

Node-selection visual result: partial — selected image and viewport pan verified in local dev preview.

## 调用日志与历史提示迁移（2026-09-26）

- 复用现有 Beautiful UI 黑色侧栏、面板、状态标签和像素加载器，增加 `/settings/calls`；调用日志入口只放在黑色侧栏导航，画布不放任何入口。
- 使用独立 Mock 后端、PostgreSQL 和当前静态构建，在 Chrome `localhost:15173` 实测。截图确认日志页深色层级、过滤表单、调用表格和展开详情可读；日志 5 条（4 条真实 Mock 调用加 1 条明确的历史 fixture）。
- 实际操作通过：展开调用时间/响应/耗时/Trace ID，筛选 UNKNOWN，展开历史提交账本，前往仍含 UNKNOWN 的项目确认无常驻黄条、无空审批横幅，再从侧栏导航回到当前项目过滤的日志。控制台 warning/error 为空。
- 历史缺失信息显示“未记录”，不伪造追踪值；未在浏览器触发新尝试或真实 Provider，未验收窄屏与大量记录。用户 Docker 部署未变。

Call-audit visual result: desktop workflow verified in isolated Mock environment.

## 审批归档位置与调用日志入口归位（2026-09-26）

- 用户确认：审批提示按 Beautiful UI 的 Approval Card 只放在 Agent 对话框内，画布不再承载审批；调用日志入口只放在菜单栏（侧栏导航），画布不放入口。
- 收口上一轮遗留：`PlanApprovalPanel` 在无待审计划时不再渲染空审批卡；`BlockedRunNotice` 与 `UnknownTaskAttemptPanel` 去掉已无调用方的 `presentation="panel"` 分支，只保留对话内呈现；`AgentChatCard` 的“返回运行会话”用例补上 PENDING 计划夹具，断言审批卡片出现在对话内且未确认时主按钮禁用。
- 定向验证：`AgentChatCard`、`BlockedRunNotice`、`UnknownTaskAttemptPanel`、`AgentRunConversation`、`CallLogsPage`、`PlanApprovalPanel`、`KeyframeSelectionPanel`、`ProjectWorkspacePage`、`ProjectWorkspaceImageLayout`、`MediaCanvasCard`、`ArtifactCardFrame`、`imageNodeLayout`、`App`、`PageShell` 共 14 个文件、98 项通过；ESLint、TypeScript 通过。未运行全量测试。
- 未取得浏览器截图：审批卡在真实滚动区内的长内容、360px 宽度下的换行与按钮换行仍未做视觉验收；组件测试不替代该结论。

Approval placement visual result: blocked

## 调用日志改为只读（2026-09-26）

- 用户指出日志里的“待核对”没有可处理的地方，且认为不需要处理。核实确认：该状态是查询时对写回失败调用行的投影，全部历史记录会永久显示；LLM 调用记录没有关联任务 ID，展开后没有任何入口。
- 决定：日志页保持纯只读审计，调用结果与关联任务的状态标签均改为“未知”，详情只保留关联任务的当前状态与“前往项目”链接；UNKNOWN 核对与显式风险新尝试只在所属 Agent 对话和媒体卡片编辑区进行。管理端系统诊断页的同一状态标签与说明文案同步改为“未知”。
- 代价：卡片移出画布期间不再有全局 UNKNOWN 入口，用户明确接受。定向 `CallLogsPage` 11 项测试、改动文件 ESLint 与 `tsc --noEmit` 通过；本轮未做浏览器视觉验收。

Read-only call-audit result: pending — component tests only.

## 画布连接点收敛（2026-09-26）

- 视觉基准：沿用现有深色画布与既有卡片表面，没有引入新的视觉来源；出口沿用既有强调色 `--accent`，非法落点与非法连接线新增 `#ff4d4f`（此前的 `#ffb4b4` 与强调色同为粉色，实测难以区分）。
- 实现组件：新增 `CanvasHandle` 与 `styles.css` 的 `.canvas-handle` 段；出口只在选中卡片右侧显示，左侧落点与 Agent 输出锚点静止不绘制。
- 本次以临时预览页在 `--headless=new` Chrome、1440×900 窗口下用真实 CDP 指针事件核对：静止显隐、出口命中（图标不抢指针）、悬停放大、拖拽中合法/非法落点与连接线配色、松手提交与拒绝，共 17 项断言全部通过并逐态截图；预览页在核对后删除，截图为过程产物未入库。
- 未覆盖：完整工作区（登录 + 后端数据）内的同一手势、真实卡片尺寸下的圆点观感、1280px 窄宽度、减少动态效果，以及 Docker 部署（`http://localhost:8088`）仍提供旧构建产物。本轮未在真实画布内截图。

Canvas handle visual result: partial — 预览页真实指针核对通过，完整工作区与部署未验收。

## 连接点几何修正（2026-09-27）

- 用户反馈：连接点太靠近节点，选中高亮时圆点有一半被卡片挡住，关系线看起来与节点断开。
- 复现与定位：卡片（`#262626` + 选中时 `box-shadow: 0 0 0 2px #fff`）在 DOM 中排在连接点之后，骑在边框上的 18px 圆点被卡片表面吃掉内侧 9px、被外圈再切掉 2px；同时 React Flow 的连线端点按连接点盒子的外边计算（`Position.Right` 取 `x + width`），18px 盒子把静止关系线的两端各内缩 9px。
- 修正：连接点分两层——外层几何盒缩到 4px（端点回到边框附近）、可见圆点整体移到卡片外并留 4px 间隙，命中区域用 `::after` 只向卡片外扩展。规格见 `docs/MVP-SPEC.md`，实测记录见 `docs/evidence/T07-canvas-connection-handles.md`。
- 浏览器核对：临时预览页按真实卡片表面复现选中外圈，16/16 项断言通过，含「圆点整体在卡片外」「抓圆点命中连接点」「边框处不再抢卡片拖动」「静止关系线两端各内缩 2.5 流坐标 px」。预览页核对后删除，未入库。
- 未覆盖：完整工作区（登录 + 后端数据）内的同一观感、真实卡片尺寸下的比例、1280px 窄宽度，以及本次修正尚未重新构建的本地镜像。

Handle geometry result: partial — 预览页真实指针与几何核对通过，完整工作区未验收。

## 画布键盘删除（2026-09-27）

- 用户确认的交互：选中卡片按 Delete 或退格移除卡片，选中关系线按 Delete 或退格删除该关系。
- 关系线是投影，因此删除落到应用服务上；绿线（Agent 输出组）与必填场景引用没有可删的关系记录，投影层标记为不可选中、不可删除。
- 为让受控关系线能被选中，选中态改为页面本地状态并写回投影（React Flow 对受控 `edges` 不会自行应用选中变更）；关系线配色从内联样式移到 CSS 类，选中态才能加粗提亮（白色 3.5px、虚线转实线）。
- 浏览器核对：临时预览页用真实投影数据渲染三类关系线，11/11 项断言通过——类型着色与虚线、输出组线 `inactive`、点击线进入选中态、退格删除交付的边、点击节点选中并 Delete 删除且级联线被剔除、输出组线不可删、文本域内退格不触发删除。预览页核对后删除，未入库。
- 未覆盖：浏览器核对没有后端，写入请求体只由组件测试覆盖；完整工作区里的真实 Agent PATCH 与产物修订、1280px 窄宽度，以及尚未重新构建的本地镜像。

Delete keys result: partial — 预览页真实指针与按键核对通过，完整工作区未验收。

## 卡片级落点与双击不缩放（2026-09-27）

- 用户反馈两点：双击画布会缩放；连线必须对准连接点，希望落在卡片上就能连。
- 修正：`zoomOnDoubleClick={false}`，双击只打开添加菜单；落点改为按指针下的卡片判定，指针所在卡片显示高亮外圈（合法强调色、非法红色），源卡片自身不作为落点，连接点距离判定保留。
- 浏览器核对：临时预览页 15/15 项通过，含双击前后缩放矩阵一致、拖到卡片中部（距连接点 153px）松手即连上、不可连卡片红色且不提交、无版本卡片不成为落点、手势结束清除高亮；截图逐态看过。预览页核对后删除，未入库。
- 未覆盖：完整工作区（登录 + 后端数据）内的同一手势与观感、1280px 窄宽度、真实后端写入，以及尚未重新构建的本地镜像。

Card drop target result: partial — 预览页真实指针核对通过，完整工作区未验收。

## 取消选中同步（2026-09-27）

- 用户反馈：点击画布或移动画布后，被选中的卡片没有取消选中。
- 定位：React Flow 通过 `select` 变更同步受控节点的选中态（点空白、框选、点关系线都会发出），页面只处理了位置与尺寸，因此画布侧已取消而应用侧 `selectedIds` 残留；平移/缩放不发 select 变更，需在视口移动时显式清除。
- 修正：`handleNodesChange` 接受 select 变更并同步选中；`onMoveStart` 在用户发起的视口移动时清除节点与关系线选中，程序化移动（`event` 为 null）不清除。
- 浏览器核对：6 项中 5 项通过（选中发 select:true、点空白发 select:false 且卡片回到未选中、中键平移发带事件的 onMoveStart 且视口变化）；未通过的一项是小地图拖动（事件为 null），已记录在证据文件里。
- 未覆盖：完整工作区里的真实点击/平移观感与底部编辑器退出、1280px 窄宽度，以及尚未重新构建的本地镜像。

Selection clearing result: partial — RF 契约与 jsdom 页面测试通过，小地图路径未覆盖。

## 关系线去掉文字（2026-09-27）

- 用户反馈：取消连线上的文字说明（例如“输入”）。
- 修正：三条投影分支都不再设置 `label`；关系类型只靠颜色与线型区分，原本只由文字表达的“绑定指向历史版本”改用虚线蓝线表达；「选择与对齐」面板说明同步。
- 浏览器核对：5/5 项通过——四条关系线渲染、线上无任何文字元素、当前版本输入实线蓝线、历史版本输入虚线蓝线、素材引用灰色虚线；截图比对确认。预览页核对后删除，未入库。
- 未覆盖：完整工作区内的观感、1280px 窄宽度，以及尚未重新构建的本地镜像。

Edge label removal result: partial — 预览页真实渲染核对通过，完整工作区未验收。

## 选中状态回写简化（2026-09-27）

- 用户反馈：点击节点选择会偶发不生效。
- 已确认：原 onNodeClick 会覆盖追加选择；现在修饰键点击交给增量 select 变更处理，并移除 onSelectionChange 的整量回写。
- 原因待验证：过期回写造成偶发失败仍是假设，现有 mock 测试未复现原始时序。
- 解释修正：受控 selected 会同步到 nodeLookup，onSelectionChange 能观察到新增选择；多选分支不立即修改内部标记，不代表应用回传受控 nodes 后也不更新。
- 本次复核：相关三个测试文件共 9 项通过；真实 React Flow 临时状态同步测试 1 项通过，测试已移除。默认 Cmd（macOS）/Ctrl（其他系统）用于点击追加，Shift 用于框选。
- 未覆盖：完整工作区的真实点击、追加选择、框选，以及原始偶发失败的回归复现。

Selection writeback result: partial — 实现方向合理，偶发问题根因与完整工作区行为仍待验证。

## Prompt 图片引用内联（2026-09-28）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-b38eb9bb-3574-4324-b72b-5fdf77d728e6.png`；对照问题图为 `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-f414b0ad-e441-4c8b-888b-b5633d8d6842.png`。
- implementation source: `frontend/src/features/canvas/MediaDraftEditor.tsx` 与 `MediaDraftEditor.css`。
- implementation comparison capture: Codex 内置浏览器在 `http://localhost:5173/prompt-mention-preview.html` 打开临时核对页，用实际 `PromptMentionEditor` 捕获“已有内联标签 + 输入 @ 后菜单”和“选择后新增内联标签”两种状态；过程页核对后移除，截图以内联工具输出保留，未写入仓库。
- viewport: 1280 × 720 CSS px；焦点编辑器 680px 宽，和现有媒体编辑器桌面宽度一致。
- console errors checked: warning/error 为空。

### Focused comparison evidence

- 标签位置：`@Image 1` 与普通提示词处于同一可编辑文本流，不再存在独立 tag 行；与目标图红框语义一致。
- 菜单行为：输入 `@` 后，菜单锚定光标下方，显示当前图片输入的缩略图和 `Image 1`；选择后原 `@` 被不可拆分 token 替换。
- 视觉层级：token 使用紧凑圆角、真实缩略图位和图片输入的稳定颜色；菜单采用深色浮层、选中行和标题分隔，与目标图保持相同层级。
- 数据行为：浏览器可访问树确认 token 属于“图片提示词”文本框；选择前后输入框 Value 均包含 `@Image 1`，菜单关闭后标签仍在原位。
- 有意差异：沿用 Agenvas 现有 680px 编辑器、粉色强调色和既有工具栏，不复制参考产品品牌、模型名称或顶部辅助入口。

### Findings

未发现影响本次目标的 P0/P1/P2 差异。临时 fixture 的缩略图是仅用于核对布局的内联图形；生产组件继续读取已归档真实 Asset 缩略图，不引入演示资产。

final result: passed

## 图片智能编辑蒙版与参考图（2026-09-30）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-c33615fa-7315-4f39-9690-1cccbc2e5e79.png`（600 × 672 px）。
- implementation source: `frontend/src/features/canvas/SmartEditDialog.tsx`、`MediaCanvasCard.tsx` 与 `frontend/src/styles.css`。
- implementation comparison capture: 用户已登录的 Chrome 在真实 Compose 项目 `/projects/7416dce1-9e6b-4508-a1c4-1ded65ff868d` 中以内联截图捕获，未写入仓库；参考图与实际截图在同一验收上下文中逐项对照。
- viewport / density: Chrome 标准窗口 2560 × 1307 px 捕获，默认缩放与 density。
- state: 一张画布图片进入智能编辑，选中 `Image 2` 参考图，填写“把背景替换成海边，保留人物姿态与服装细节”，并在图片上绘制涂抹蒙版；未点击发送。
- primary interactions tested: 打开/退出编辑器、涂抹、框选、添加/擦除模式切换、笔刷尺寸、撤销/重做、画布固定版本引用、提示词输入和发送可用态。
- console checked: Chrome 扩展调试通道因请求头策略加载失败而不可用，未取得 console 记录；Chrome 可访问树、屏幕交互和 Compose 日志未见可见错误，不将其表述为 console 零错误。

### Full-view comparison evidence

参考与实现均采用画布上方居中的胶囊工具栏、居中的当前图片、画布下方的大圆角提示词编辑器。实现保留 Agenvas 的深灰表面、细边框、14–18px 圆角和粉色主操作色；背景继续显示暗化后的真实节点关系，以保留编辑对象来源，不复制参考产品品牌或模型名称。

### Focused region comparison evidence

- 字体与排版：工具栏使用紧凑 12–13px 文案；涂抹/框选的当前态以粉色胶囊标示，提示词与状态说明保持现有编辑器层级。
- 间距与布局：工具栏悬浮于图片上方，图片在可用工作区等比 contain；底部编辑器完整容纳引用、上传、已选缩略图、提示词、模型与发送按钮，未与图片或节点工具栏重叠。
- 颜色与 token：选区使用半透明高饱和粉色，既能看清范围，也保留底图判断；撤销/重做禁用态和深色边框沿用产品 token。
- 图片与蒙版质量：编辑预览直接读取当前 CanvasItem 固定 Asset；蒙版画布最长边限制为 1024 以控制内存，提交时生成二值 PNG，选中区域为透明编辑区。
- 文案与内容：引用候选来自其他 CanvasItem 的固定不可变版本及资源库默认版本，按选择顺序显示为 Image 2、Image 3；上传参考图会归档为资源，不使用临时 URL 冒充输入。

### Findings and comparison history

第一次 Chrome 捕获仍展示旧通用“修改说明”面板，确认是浏览器未刷新生产 bundle；刷新后新全屏编辑器正确接管入口。随后引用弹层显示空态，定位为仅查询 Artifact 默认版本，无法覆盖“一个节点固定一个媒体结果”的现行模型；改为合并 CanvasItem 固定版本与资源库默认版本并去重，复测显示 5 张可选参考图。最终未发现剩余 P0/P1/P2 视觉或交互差异。

final result: passed

## 三视图细分入口与参数面板（2026-09-30）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-2229b757-1432-4070-a2ec-5b6425407c0e.png`（752 × 496 px）。
- implementation source: `frontend/src/features/canvas/MediaCanvasCard.tsx` 与 `frontend/src/styles.css`。
- implementation screenshot path: 用户已登录 Chrome 的内联 CUA 捕获；工具未提供可持久化的截图文件路径。参考图与实现截图在同一验收上下文中逐区核对。
- viewport / density: Chrome 捕获约 2560 × 1131 px，默认 device scale；参考图按原始尺寸查看。
- state: 图片节点扩展菜单展开、三视图二级菜单展开；随后分别核对“脸部三视图”面板和切换到“场景宫格图”后的面板。
- primary interactions tested: 指针移入后点击三视图仍保持二级菜单；选择脸部三视图打开面板；四类单选卡均可见；切换场景宫格图后标题同步更新，推荐比例从 16:9 自动切换为 1:1；未点击“开始处理”，因此没有触发真实 Provider 费用。
- console errors checked: 本轮未单独读取 Chrome 控制台，不声明控制台无错误。

### Full-view comparison evidence

实现保留 Agenvas 图片节点既有圆角、深色表面、粉色强调色和紧凑工具栏，只把参考图中的三视图分流结构移植到现有“扩展”菜单。二级菜单在主菜单右侧展开，四个选项顺序、信息层级和目标图一致；面板继续使用项目现有页面级 Portal，未被下方 Prompt 编辑器遮挡。

### Focused region comparison evidence

- 字体与排版：一级和二级菜单均沿用图片节点现有 12px 菜单字号与图标尺寸；面板标题随选中类型变化，四类说明使用次级文字层级。
- 间距与布局：二级菜单保持紧凑纵向列表，右侧对齐一级入口；参数面板使用四张等宽单选卡，在窄视口可回流，不侵入画布节点内容。
- 颜色与 token：菜单和面板复用现有深灰表面、细灰描边、白色主文字与粉色选中态，没有引入新的孤立色值。
- 图像质量与资产：本次只新增信息架构和参数选择，没有生成或替换图片资产；图标复用现有 Phosphor 图标集。
- 文案与内容：四类明确为“角色三视图、脸部三视图、道具三视图、场景宫格图”，面板补充各自输出结构；参考图中的费用数字未照搬，因为 Agenvas 的费用预检由提交前能力检查统一承担。

### Findings and comparison history

首次 Chrome 实测发现 P1 交互问题：指针移入已打开二级菜单后再点击一级入口，会因 toggle 逻辑立即关闭菜单。改为点击始终打开，并增加 `pointerEnter + click` 回归测试；二次实测确认四类入口、面板选中态和比例联动均可用。最终未发现剩余 P0/P1/P2 视觉或交互差异。

final result: passed

## 媒体单结果节点与派生线（2026-09-30）

- implementation source: `frontend/src/features/canvas/ProjectWorkspacePage.tsx`、`MediaCanvasCard.tsx`、`ArtifactVersionHistory.tsx` 与 `canvasRelations.ts`。
- live environment: 用户已登录的 Chrome，当前 Compose 项目 `7416dce1-9e6b-4508-a1c4-1ded65ff868d`，server/web/PostgreSQL 均 healthy。
- state: 选中已有图片并执行本地“水平镜像”，来源节点和结果节点同屏显示。
- primary interactions tested: 打开扩展菜单、运行水平镜像、等待任务完成、选中结果节点、打开卡片详情。

### Browser evidence

执行前可访问树包含 8 个节点和 5 条连线；执行后新增独立图片节点 `909cea25-f84a-4dfb-92db-b7dac0abf118`，并新增从来源节点 `77cfd3e7-163d-4a9e-8d43-94d26fba508e` 指向结果节点的派生线。来源节点仍引用 Asset `367d8fc5-8270-3dd5-b7ff-f8f673bd686e`，结果完成后引用新 Asset `8615c9a8-e7f3-3f72-bc52-872f145b2e2a`，因此没有原位覆盖。

结果节点显示与来源相同的 Prompt 编辑器、模型选择、尺寸画质、智能编辑、深度提取、扩展、重新生成、复制、详情和下载能力。详情标题为“图片 · 已有结果”，没有媒体版本列表或版本切换入口。DOM 核对派生线带有 `relation-edge--derivation`、`selectable` 和 `tabindex=0`；删除与重连的持久化语义由 `ImageOperationDerivationPostgresIT` 验证，未在用户现有项目中删除数据。

final result: passed

## 图片后处理派生节点（2026-09-29）

- implementation source: `frontend/src/features/canvas/canvasRelations.ts`、`MediaCanvasCard.tsx`、`styles.css`，以及后端图片处理受理链路。
- browser / viewport: 用户已登录的 Chrome 日常配置，当前 `http://localhost:8088/projects/7416dce1-9e6b-4508-a1c4-1ded65ff868d` 项目页。
- state: 来源图片节点已选中，扩展菜单展开；执行“水平镜像”本地操作。
- primary interactions tested: 点击一次水平镜像，等待本地 Task 完成，并重新读取 Chrome 可访问树。

### Findings

操作前画布有 7 个节点和 4 条线；操作完成后出现第 8 个图片节点与第 5 条线，新增线精确连接来源节点和结果节点。来源节点仍指向原 Asset，结果节点指向新 Asset；没有发生原节点图片覆盖。派生线为粉色实线、不可选择、不可单独删除，视觉上与灰色虚线图片输入及绿色 Agent 输出保持区分。

final result: passed

## 视频节点输入模式与比例（2026-09-29）

- source visual truth paths: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-e53e4689-c87c-498b-841b-4c717eefcb45.png`（1000 × 780 px）与 `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-4f69a4bd-28dd-4047-8db8-7e57f18b2070.png`（611 × 512 px）。
- implementation source: `frontend/src/features/canvas/MediaDraftEditor.tsx` 与 `MediaDraftEditor.css`。
- implementation comparison capture: 使用已登录 Chrome 在真实项目 `/projects/7416dce1-9e6b-4508-a1c4-1ded65ff868d` 验收；为避免把当前工作区迁移或草稿写入用户数据库，前端连接当前源码后端与生产库克隆。截图以内联工具证据保留，未写入仓库；验收后恢复原服务并删除临时数据库。
- viewport / density: Chrome 2560 × 1131 px，浏览器默认 density；为完整展示节点与编辑器，把 React Flow 画布缩放至适配视图。
- states: 无图片时“文生视频”选中，“全能参考 / 首尾帧”置灰；加入首张资源图片后自动切换“全能参考”，此时“文生视频”置灰、“首尾帧”可用；比例由“自动”切换并保存为“9:16”。
- primary interactions tested: 打开模式菜单、从资源库勾选精确图片版本、添加第一张图片、自动模式切换、打开参数面板、选择 9:16、自动保存与工具栏摘要联动、Esc 关闭参数弹层并恢复触发按钮焦点。
- console checked: Chrome warning/error 为空。

### Comparison status

- 字体与排版：真实画布中的 11–12px 标签、图标与辅助文案延续图片节点层级；模式名称、用途说明和禁用原因可以同时辨认。
- 间距与布局：模式入口位于底部工具栏；模式菜单为紧凑纵向列表，比例面板为横向四等分圆角网格。2560px 桌面视口下弹层完整显示且未被卡片或底部编辑器裁切。
- 颜色与 token：复用现有深色表面、灰色禁用态、粉色选中态与边框 token；“全能参考”和 9:16 的选中态均有清晰高对比反馈，没有移植参考产品品牌配色。
- 图像质量与资产：没有新增或替换生产图像资产。
- 文案与内容：实现“文生视频 / 全能参考 / 首尾帧”及明确禁用原因；无图和有图两种状态的引导文案与实际可用性一致。
- 可访问性：模式和比例入口在辅助树中分别暴露为 pop-up button / button，禁用选项有真实 disabled 状态；Esc 关闭比例弹层后焦点回到“尺寸与画质”按钮。未在本轮执行屏幕阅读器语音输出与完整 Tab 顺序遍历。

### Findings

Chrome 实测确认需求中的三条核心行为全部成立：无图默认“文生视频”并禁用图片模式；首图加入后自动改为“全能参考”；比例 9:16 保存后工具栏摘要同步。弹层圆角、深色层级、粉色强调和禁用灰阶与图片节点风格一致，未发现影响本次目标的 P0/P1/P2 差异。

本轮没有运行视频生成任务或真实 Provider 调用，因此结果只证明输入模式、能力约束、草稿保存和比例交互，不证明最终视频质量或第三方能力接通。

final result: passed

## AI 打光专用面板（2026-09-29）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-9d86cdd6-f1ed-4231-afdf-6e0743ce75d8.png`（629 × 470 px）。
- implementation source: `frontend/src/features/canvas/RelightPanel.tsx`、`MediaCanvasCard.tsx` 与 `frontend/src/styles.css`。
- implementation screenshot path: Codex 内置浏览器捕获，页面为 `http://localhost:5174/projects/00405cde-13b5-4728-9ab2-64b766bdfb3e`；捕获以内联工具证据保留，未写入仓库。
- viewport / density: 1440 × 900 与 1280 × 900 CSS px，浏览器默认 density；面板目标宽 590px，来源图按 629 × 470 归一化比较。
- state: 默认黄金时刻（+10 / 3200K）与月光预设（-24 / 8200K）；隔离环境无云端密钥，因此能力选择显示明确空态、提交禁用。
- primary interactions tested: 打开/关闭层级、六预设选择、预设联动亮度与色温、当前图片即时预览、1280px 适配；组件测试覆盖补充描述与完整 RELIGHT 请求体。
- console errors checked: warning/error 为空。

### Full-view / focused comparison evidence

- 字体与内容：保留设计稿的“打光”“预设风格”“亮度”“色温”和描述占位文案，沿用 Agenvas 中文 UI 字体；能力选择是为了满足真实 OpenAI/Google 契约而保留的有意差异。
- 间距与布局：桌面维持左侧 220px 预览与双滑杆、右侧两列六预设与描述框、右下圆形粉色提交按钮。面板使用页面级 Portal，位于画布中央且不受卡片缩放裁切。
- 颜色与 token：深灰分层、细边框、圆角、白色滑块、暖冷色温轨道和粉色主操作与设计稿及现有画布一致。
- 图片质量：预览与所有预设都使用卡片当前归档图片，不内置演示占位；CSS 滤镜仅为选项预览，提交后由 AI 生成真实新版本。
- 交互可见性：左侧光圈可点击设置归一化光源位置；选择预设会同步滑杆与预览，AI 能力缺失时提交不可用且原因可见。

### Findings and comparison history

首次 1440px 捕获发现 P1：面板虽然声明 fixed，但 React Flow 工具栏的变换祖先使它落入节点坐标系，上半部越出视口并被裁切。修正为 `createPortal(..., document.body)`，同时为 Portal 内按钮补充独立样式。1440px 与 1280px 复测均完整显示，月光预设状态正确，未发现剩余 P0/P1/P2 差异。

真实 OpenAI/Google 付费生成仍未执行；本次视觉验收只证明面板与请求契约，不证明第三方最终打光质量。

final result: passed

## 图片后处理工具栏与重打光/深度能力（2026-09-29）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-3c3e2732-a92b-49e9-b8b3-0f773cd5277b.png`（587 × 546 px）；重点为选中图片顶部操作条、深度提取入口与纵向编辑菜单。
- implementation source: `frontend/src/features/canvas/MediaCanvasCard.tsx`、`ArtifactCardFrame.tsx`、`ArtifactCardFrame.css` 与 `frontend/src/styles.css`。
- implementation comparison capture: Codex 内置浏览器打开隔离 QA 项目 `http://localhost:5174/projects/6a128fb8-de2d-47fe-9c76-6913f0cb2646`，使用当前工作区前端、当前后端和独立 PostgreSQL；截图以内联工具输出保留，临时服务与数据库在验收后清理。
- viewport / density: 1440 × 900 与最小支持桌面宽度 1280 × 900 CSS px，浏览器默认 density。
- states: 有图卡片选中工具栏、完整扩展菜单、AI 重新打光与图层分离能力面板、真实本地深度任务完成后的新版本。
- console checked: warning/error 为空。

### Full-view / focused comparison evidence

- 顶部工具条保持参考图的深色胶囊结构；智能编辑与深度提取是一级入口，其余能力收进单一“扩展”菜单。Agenvas 仍保留重新生成、复制、详情与下载等已有卡片动作。
- 菜单采用参考图的窄深色纵向列表和左图标结构，并补充右侧能力来源标签：三视图、图层分离、表情调整、重新打光、画笔标注、移除背景、AI 扩图、局部擦除和视角调整标为 `AI`；放大、裁剪、旋转、镜像标为 `本地`。七个原占位入口现均可打开真实参数面板，不再显示 `后续`。
- “AI 重新打光”面板明确写明“使用 OpenAI / Google 图片能力”，提供能力选择与灯光描述；隔离环境没有云端连接时提交按钮保持禁用。
- 图层分离面板在 1440 × 900 下完整显示主体层（透明背景）/背景层选择、能力选择和可选说明；无透明输出能力时明确提示配置支持透明背景的 OpenAI / Google 能力，提交按钮禁用。浏览器 warning/error 为空。
- 深度提取在浏览器中从 Mock 源图片发起真实持久任务，服务端加载 Depth Anything V2 Small ONNX，完成后归档新 Asset/ArtifactVersion 并自动切换卡片显示；最终灰度深度图在画布中可见。
- 1280px 和 1440px 下菜单、面板、卡片和底部 Prompt 编辑器均可读，工具栏不越出视口。

### Findings and comparison history

首次 1440px 捕获发现一个 P2：扩展菜单的最后两项虽存在于辅助树，但被更高层级的底部 Prompt 编辑器盖住。实现改为只在菜单或处理面板打开时，把对应 React Flow 顶部工具栏从默认层级提升到 `1003`；复测 13 项完整可见，常态工具栏层级不变。最终未发现剩余 P0/P1/P2 差异。

云端 OpenAI/Google 未做真实付费调用；重打光验收覆盖能力边界、输入面板和禁用空态，不把 Mock/截图当成 Provider 接通证据。

final result: passed

## 图片框选裁剪（2026-09-29）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-48d894c8-8b05-47ae-a83b-0a8122aa3898.png`（403 × 676 px）；功能目标是直接在原图上框选，而不是输入左、上、宽、高百分比。
- implementation source: `frontend/src/features/canvas/CropPanel.tsx`、`CropPanel.css` 与 `MediaCanvasCard.tsx`。
- implementation comparison capture: 已登录 Chrome 打开真实项目 `/projects/7416dce1-9e6b-4508-a1c4-1ded65ff868d`，从图片节点“扩展 → 裁剪”进入；截图以内联工具证据保留，未写入仓库。
- viewport / density: Chrome 2560 × 1131 px，浏览器默认 density；参考图为局部功能裁切，因此按裁剪图片、选框和底部工具条三个内容区域比较，不比较画布位置与素材内容。
- states: 默认原始比例；切换 9:16 后的竖向选框；键盘方向键缩小右下角后的焦点态。
- primary interactions tested: 打开裁剪工作区、整框移动（组件指针测试）、八个边角手柄呈现、比例切换、键盘微调、取消/确定入口与归一化裁剪参数提交。
- console checked: Chrome warning/error 为空。

### Full-view and focused comparison evidence

- 字体与排版：底部工具条继续使用 Agenvas 中文 UI 字体与 13px 控件层级；“取消 / 原始比例 / 确定”的信息结构与参考一致。
- 间距与布局：原图居中，框选区域直接覆盖图片；外围画布使用模糊暗化，底部胶囊工具条与图片保持 16px 间距，不被节点编辑器或 React Flow 工具栏覆盖。
- 颜色与 token：白色边框和手柄提供高对比命中提示，未选区域使用半透明黑色遮罩，确认操作沿用产品粉色主操作 token。
- 图片质量：裁剪预览直接读取当前 CanvasItem 已归档原图内容 URL，使用 `object-fit: contain`，不生成截图替身、不替换生产素材。
- 文案与内容：提供原始比例、自由、1:1、4:3、3:4、16:9 和 9:16；确定后仍提交既有归一化 `x / y / width / height` 契约。
- 可访问性：裁剪工作区为命名 dialog；移动区与八个手柄均是具名 button，方向键可微调；Esc 和“取消”关闭，比例为具名 select。

### Findings and comparison history

首次实现直接采用参考图的框选模型，替换原有四个百分比数字输入。Chrome 对照确认默认横向原图与 9:16 竖向选框都完整可见；比例切换后几何从 638 × 426 px 调整为 239 × 426 px，键盘微调后变为 232 × 412 px。未发现影响本次目标的 P0/P1/P2 差异。

本轮未点击真实项目中的“确定”，避免创建图片处理 Task；归一化请求体和受理路径由组件测试覆盖，因此视觉验收不声称后端实际裁剪结果已生成。

final result: passed

## 图片生成原子参数面板（2026-09-29）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-4bf65822-2165-43d5-b96d-d888ea8df76b.png`（1148 × 560 px）。
- implementation source: `frontend/src/features/canvas/MediaDraftEditor.tsx`、`MediaDraftEditor.css`、`ProjectWorkspacePage.tsx` 与 `frontend/src/styles.css`。
- implementation comparison capture: 已登录 Chrome 在真实项目 `/projects/7416dce1-9e6b-4508-a1c4-1ded65ff868d` 打开图片卡片；另用临时同屏页把参考图与真实项目 iframe 放入同一张捕获，验收后通过补丁删除临时页并恢复 Vite 文件访问配置。截图以内联工具输出保留，未写入仓库。
- viewport / density: 默认桌面视口 2560 × 1194 CSS px；另以浏览器视口能力实测 1280 × 900 CSS px 并在结束前恢复默认设置。
- state: 默认 `AUTO / 1K / medium / 1 / 新节点关闭`；交互态为 `9:16 / 2K / low / 4 / 新节点开启`，摘要同步为 `9:16 · 2K · 低 · 4 张`。测试后已恢复默认草稿。
- primary interactions tested: 展开/关闭参数面板，选择比例、分辨率、画质、生成数量，切换新节点输出，自动保存与工具栏摘要联动；透明背景开关的提交由组件测试覆盖，本轮浏览器未改变该值。
- console checked: 本次组件没有 warning/error；页面存在一条既有 React Flow `nodeTypes/edgeTypes` 对象未 memoize 的警告，与本次面板无关，已如实保留。

### Full-view comparison evidence

同屏捕获确认两侧均采用深色浮层、横向比例图标、分段式分辨率/画质/数量和右侧开关。实现沿用 Agenvas 中文文案、粉色强调色与现有 680px 编辑器，不复制参考产品品牌、模型名称或英文标签。能力不支持的选项不会显示，属于真实 Provider 契约差异。

### Focused region comparison evidence

- 字体与排版：标题和分组标签采用现有 11–12px 层级；当前值使用高对比白色和中等字重，非当前值降级为灰色。
- 间距与布局：参数层宽 540px；比例为 9 列紧凑图标，分辨率、画质、数量为等分段控件。最终高度上限 520px，在默认与 1280px 桌面视口中均完整显示六组参数，无需滚动才能找到“生成时新建节点”。
- 颜色与 token：浮层、分段背景、选中块、禁用态和粉色开关复用既有媒体编辑器 token；透明背景关闭态与参考一致。
- 文案与内容：参考图的 Scale / Resolution / Quality / Generation Count / Open New Node 分别映射为比例、分辨率、画质、生成数量和生成时新建节点；新增副文案明确每个结果使用独立工作分支。
- 能力真实性：OpenAI 展示全部当前映射选项；Google/ComfyUI 不展示未映射画质、透明背景或尺寸，界面不会伪造 Provider 支持。

### Findings and comparison history

首次真实浏览器捕获发现两个 P2：通用 420px 弹层高度使底部两组需要滚动，且图片输入“+”按钮因较高层级穿透到参数面板。参数层改为 `min(520px, 70vh)` 并提高自身层级后解决。随后 1280px 实测发现卡片顶部操作工具栏与编辑器浮层同为 React Flow `z-index: 1001`，会覆盖比例行；为编辑器 NodeToolbar 增加专用类并提升到 1002，复测确认重叠区域由参数层完整覆盖。最终未发现剩余 P0/P1/P2 差异。

final result: passed

## 图片输入来源菜单与画布候选过滤（2026-09-28）

- source visual truth paths: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-86442d18-b424-4796-bef2-d74f5c62f85c.png`（294 × 379 px）与 `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-7f3436ff-2adf-421f-8a26-e326322ca18c.png`（784 × 684 px）。
- implementation source: `frontend/src/features/canvas/MediaDraftEditor.tsx` 与 `MediaDraftEditor.css`。
- implementation comparison capture: 使用已登录 Chrome 在独立 Vite 预览 `http://localhost:5174/projects/<projectId>` 中核对；验收后恢复原 `5173` 页面并停止临时服务。截图以内联工具输出保留，未写入仓库。
- viewport / density: 2560 × 1125 CSS px，浏览器默认 density；来源菜单实测约 220 × 168 CSS px。
- primary interactions tested: 点击/键盘打开来源菜单、Esc 关闭并恢复焦点、进入画布图片选择、候选渲染与当前卡片过滤；组件测试补充覆盖鼠标 hover 打开。
- console errors checked: warning/error 为空。

### Focused comparison evidence

- 来源层级：鼠标覆盖、键盘聚焦或单击“+”均可打开紧凑深色菜单，四个入口依次为“从设备上传”“从资源库选择”“从画布选择”“绘制引用图”，与参考图的结构一致。
- 可用能力：设备上传、资源库与画布来源接入真实流程；P0 尚无绘制/局部重绘能力，因此“绘制引用图”明确显示“暂未接入”并禁用，不伪造可用操作。
- 画布过滤：实测当前选中图片卡片 id 为 `77cfd3e7-163d-4a9e-8d43-94d26fba508e`，弹层只显示另外两张图片卡片的当前归档版本；组件测试同时断言排除当前 `canvasItemId` 和非图片卡片。同一 Artifact 的其他卡片仍按独立 CanvasItem 保留为候选。
- 弹层几何：首次实测发现来源菜单被后声明的通用 popover 样式压缩为约 20px 高；提高专用选择器优先级后，菜单恢复为约 220 × 168px，四行完整可见且未被缩略图滚动区裁剪。
- 键盘与焦点：在来源菜单或画布选择弹层按 Esc 后，弹层关闭、来源菜单不反弹，焦点回到“添加图片输入”按钮。

### Findings

未发现剩余 P0/P1/P2 差异。与参考图唯一有意差异是绘制入口的禁用说明，它准确反映当前产品能力边界。

final result: passed

## 媒体图片选择弹层与空态文案（2026-09-28）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-eb67149b-f2bc-4e29-9324-61259650ccd5.png`（735 × 311 px）。
- implementation source: `frontend/src/features/canvas/MediaDraftEditor.tsx` 与 `MediaDraftEditor.css`。
- implementation screenshot path: Codex 内置浏览器的同屏内联捕获，临时验证页不持久化，因此无独立文件路径。
- viewport / density: 768 × 720 CSS px，默认 device scale；参考图和实际 680px 宽媒体编辑器在同一张 full-page 捕获中上下对照。
- state: 无图空态和点击“+”后的图片版本选择弹层。
- primary interactions tested: 单击添加、弹层自动聚焦、Esc 关闭并恢复焦点；浏览器 warning/error 为空。

### Full-view comparison evidence

对照捕获确认：“+”保留在 Prompt 下方原位，用户标记要取消的右侧“添加图片作为精确版本输入”已不再显示，提示词占位与底部工具栏未被推动。

### Focused region comparison evidence

- 字体与排版：删除多余的 11px 空态说明，不改变 Prompt、占位文字或工具栏字号。
- 间距与布局：46 × 46px 添加按钮保持原定位；横向滚动收窄到独立缩略图列表，按钮及弹层不在滚动裁剪区内。
- 颜色与 token：按钮边框、深色表面、粉色键盘焦点及弹层层级均复用现有 token。
- 图像质量与资产：本次只修正布局和弹层可见性，没有新增或替换任何生产图片资产。
- 文案与内容：只删除用户指定的右侧说明；选择弹层内仍保留精确版本和能力上限说明。

### Findings and comparison history

首次实测为 P0：点击后 `aria-expanded=true` 且弹层已存在于可访问树，但横向 `overflow-x: auto` 容器将向上展开的弹层完全裁掉。修复后只让缩略图列表横向滚动；二次捕获确认弹层在编辑器上方完整可见、可聚焦、可用 Esc 关闭。同屏空态对照未发现剩余 P0/P1/P2 差异。

final result: passed

## 媒体输入纯缩略图栏（2026-09-28）

- source visual truth paths: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-a02c9c6a-b830-42a1-92f0-292f9f099e74.png`（934 × 661 px）与 `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-7921615a-6a70-4b5c-8268-5b09b67e06bc.png`（826 × 394 px）。
- implementation source: `frontend/src/features/canvas/MediaDraftEditor.tsx` 与 `MediaDraftEditor.css`。
- implementation screenshot path: Codex 内置浏览器的同屏比较捕获，过程预览页未持久化；参考图和实际 `MediaReferenceThumbnail` 在同一张 1280 × 720 浏览器截图内。
- viewport / density: 1280 × 720 CSS px，默认 device scale；实际编辑器 576.94px 宽，缩略图与添加按钮均为 46 × 46 CSS px。
- state: 8 张图片静止态、第二张连线图片关闭按钮聚焦态，以及关闭后剩余 7 张重新连续编号。
- primary interactions tested: 聚焦显示关闭按钮；连线来源的关闭按钮辅助说明为“取消引入并断开画布连线”；点击关闭后缩略图消失并重新编号；真实浏览器从序号 1 拖到序号 5 后，可访问树与顺序输出均变为 `2,3,4,5,1,6,7,8`。
- console errors checked: warning/error 为空。

### Full-view comparison evidence

参考图与实际组件同屏比较。两者均为添加按钮后跟紧凑、等宽的纯图片队列；实际组件不再显示图片名、版本说明或左右移动按钮，编辑器信息密度与目标红框一致。

### Focused region comparison evidence

- 字体与排版：图片栏只保留 10px 高对比序号；没有额外标题或版本文字，符合目标。
- 间距与布局：46px 方形缩略图、8px 间距、10px 圆角；在 576px 编辑器中可横向滚动，添加按钮固定在左侧。
- 颜色与 token：默认细灰边框；悬停/键盘聚焦使用该精确输入的稳定颜色，关闭按钮为深色底白色 X，悬停进入危险红色。
- 图片质量：生产组件继续读取归档 Asset 内容地址并使用 `object-fit: cover`；同屏 fixture 直接复用了用户提供的真实截图素材，没有新增占位资产。
- 文案与内容：可见区域没有图片描述与操作文字；辅助技术仍能读取图片版本、序号和“断开画布连线”的操作后果。

### Findings and comparison history

首次同屏比较未发现 P0/P1/P2。关闭按钮聚焦态清晰显示在右上角且不遮挡左上角序号；点击后的连续编号通过浏览器可访问树核对。补充真实指针拖拽与 12 张图片横向溢出验证：576px 可视宽度对应 694px 滚动宽度，`overflow-x: auto`，滚动条隐藏且添加按钮保持 sticky。未保留 P3 项。

final result: passed

## CanvasItem 卡片标题原位编辑（2026-09-27）

- source visual truth path: `/var/folders/7l/h9bn2gjd57sfkx0v0nmp1bkw0000gn/T/codex-clipboard-ce4a834f-25cb-4028-9c57-1cf561fa93d6.png`
- implementation source: `frontend/src/features/canvas/CanvasItemTitleEditor.tsx` 与 `ArtifactCardFrame.css`
- implementation comparison capture: Codex 内置浏览器在 `http://127.0.0.1:4173/` 打开临时并排核对页后以内联截图捕获；过程页核对后移除，未保留独立截图文件。
- viewport: 932 × 896 CSS px；并排区域各 361 × 110 CSS px。
- source pixels / normalization: 源文件实际 354 × 108 px，在核对页等比显示为 361 × 110 CSS px；浏览器 device scale 使用默认值。实现区域为 361 × 110 CSS px。
- state: 标题输入已聚焦并全选，内容均为 `Failed selfies nine grid`。
- primary interactions tested: 浏览器中 F2 进入编辑、Esc 取消并恢复标题、再次 F2 进入；双击、Enter/失焦保存、失败保留和空标题由组件测试覆盖。浏览器过程未向旧 Docker 后端提交画布命令。
- console errors checked: 内置浏览器 warning/error 为空。

### Full-view comparison evidence

参考图与实际组件在同一浏览器截图中并排显示。实现沿用 Agenvas 现有标题锚点，不引入参考产品左侧图片图标；这与用户给出的图一现有卡片结构一致。输入框实际测得 173 × 22 CSS px，参考图约 171 × 20 px，属于 2px 以内的非实质差异。

### Focused region comparison evidence

- 字体与排版：沿用卡片标题 11px 字号、18px 行高和常规字重；长标题单行显示，输入宽度随内容增长且受卡片宽度约束。
- 间距与布局：输入框保持标题原锚点，距卡片表面约 5px；没有推动或缩放卡片内容。
- 颜色与 token：实际聚焦边框 `rgb(209, 120, 255)`，外圈 `rgb(169, 76, 255)`，背景 `rgb(35, 35, 35)`，文字 `rgb(237, 237, 237)`，与参考的紫色描边深色输入态一致。
- 图片质量与资产：本次只改变可编辑 UI 文本，不替换、裁切或生成卡片媒体资产；参考图中的缩略图不属于本次实现范围。
- 文案与内容：参考标题原样用于核对；提示文案只通过 `title`/辅助文本提供，不占用可见标题区域。

### Findings

无可执行的 P0/P1/P2 差异。参考图左侧图片图标不移植，属于保持 Agenvas 现有卡片标题结构的有意差异；输入框高度相差约 2px，保留为可接受的 P3 细节。

### Comparison history

首次并排比较即未发现 P0/P1/P2；未因视觉问题修改实现。浏览器自动化的双击动作未可靠触发原生 `dblclick`，因此双击行为以 Testing Library 的真实事件序列回归为准，浏览器视觉态通过同一公开键盘入口 F2 进入。

### Implementation checklist

- [x] 双击与 F2 进入原位编辑，输入自动聚焦并全选。
- [x] Enter/失焦保存，Esc 取消。
- [x] 空标题与失败/冲突保留草稿并显示错误态。
- [x] 紫色紧凑描边、深色背景、单行宽度约束与参考一致。
- [x] 键盘输入不触发画布删除快捷键。

final result: passed
