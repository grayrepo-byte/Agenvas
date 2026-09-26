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

按用户要求停止旧 Vite，使用当前 Next.js 在 `127.0.0.1:5173` 启动开发前端。Chrome“日常”实例使用已有 `127.0.0.1` 登录会话成功打开项目画布，完成选中图片和视口平移截图：节点无白色缩放点，图片完整显示、选中外圈贴合圆角，工具栏随视口平移且位于节点上方。Docker 的 8088 服务保持运行。当前基础截图验证已完成，其他图片比例、节点尺寸拖拽、锁定与窄屏回归仍未验证；节点靠近画布上边缘时工具栏会被裁切，当前未增加贴边避让。

Node-selection visual result: partial — selected image and viewport pan verified in local Next.js preview.

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
