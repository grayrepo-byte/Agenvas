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
