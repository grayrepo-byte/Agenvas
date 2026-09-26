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
