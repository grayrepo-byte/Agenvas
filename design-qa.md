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
