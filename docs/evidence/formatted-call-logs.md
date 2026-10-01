# 调用日志格式化显示验证

日期：2026-10-01。基于 `ef81e93` 的 `codex/formatted-call-logs`。本轮只涉及前端展示，无 API、生成类型或数据库迁移。

## 行为与文件

- `CallDebugDetails` 默认格式化并可切换原始内容，仅在打开详情模态框后按需获取正文，关闭清除 Query 缓存。
- `callLogFormatting` 解析已脱敏的 Chat Completions 请求 / 响应；`FormattedCallExchange` 展示实际模型、生成 ID、结束原因、流式标记、用量，以及 Prompt、Completion 和完整生成 JSON。没有值时显示“未记录”，不反推 Token 或费用。
- 消息浏览器提供角色及数量、搜索、消息选择、前后翻页、工具调用参数、复制和单条 JSON；多模态部分只作为文字 JSON 展示，不访问媒体 URL。
- 非 LLM JSON 缩进；非 JSON / Base64 / 过大的正文保留原始显示；每次追加 64 Ki 字符，复制包含整个已采集正文。原始是服务端已有的脱敏记录，不还原被排除的 header、凭证或私有推理。
- `Dialog` 增加可选 `className` 以支持宽详情与消息浏览器，保留原生 modal、焦点恢复和键盘关闭；嵌套 Esc / Tab / cancel 事件隔离，全部窗口关闭或路由卸载后恢复 body 滚动；新样式复用项目主题 token 与 Select。四语言目录同步。
- 规格第 24.1 节、开发清单及根目录 `design-qa.md` 已同步。

## 实际检查

执行以下定向测试：

```sh
cd frontend
./node_modules/.bin/vitest run \
  src/features/settings/callLogFormatting.test.ts \
  src/features/settings/FormattedCallExchange.test.tsx \
  src/features/settings/CallDebugDetails.test.tsx \
  src/features/settings/CallLogsPage.test.tsx \
  src/shared/ui/Dialog.test.tsx
```

5 个文件、28 项通过。覆盖实际/缺失/零用量、错误响应、工具调用及 malformed 参数、多模态正文、原始内容保留、长正文、复制失败、消息筛选/翻页/JSON、英文控件、焦点恢复、按需读取及清缓存、权限和列表回归。新增详情模态框不增加列表行、关闭恢复焦点、嵌套窗口 Esc 只关闭顶层、Tab 焦点隔离和整组窗口卸载恢复原始 overflow。

TypeScript `tsc --noEmit`、修改 TS/TSX 文件 ESLint（含测试）、`check-i18n.mjs`、`check-theme-colors.mjs`、`vite build` 与 `git diff --check` 通过。生产构建仍提示既有大 chunk（workspace/index），没有构建错误。

浏览器运行生产静态文件与本地 **Mock HTTP** 返回（临时只读 Python 服务，`127.0.0.1:15184`），不启动 Provider 或写真实数据库。数据行、登录名和内容均明确标注 Mock；Token / 费用是测试夹具，不是实际计费证据。

浏览器实测：打开详情模态框后默认格式化，列表 `tbody` 保持 1 行；Prompt / Completion 内容、消息角色筛选、工具参数搜索、复制并核对剪贴板、切换单条 JSON、切换完整原始请求/响应。1280×900 桌面及 390×844 手机断点已检查；手机 dialog 客户宽度和滚动宽度均为 372px，无内部横向溢出。打开 Prompt 后 Esc 仅关闭消息窗口、保留详情和 body 滚动锁，关闭详情恢复“查看调用详情”焦点及原始 body overflow。桌面详情 dialog 宽 1120px、高 868px，手机宽 374px、高 828px，标题与底部关闭按钮都在视口内；手机滚动宽度 / 客户宽度均为 372px。浏览器 warn / error 输出为空。

截图由浏览器 full-page 捕获后按实测 DOM 坐标裁切。由于 IAB 默认截图与 override 视口宽度不一致，使用完整页面捕获保留全部横向内容，再裁出可视区域；图片没有重绘或改变应用内容。

- [详情模态框（桌面）](assets/formatted-call-logs/details-modal-desktop.png)
- [详情模态框（手机）](assets/formatted-call-logs/details-modal-mobile.png)
- [此前列表展开版格式化详情](assets/formatted-call-logs/details-desktop.png)
- [桌面 Prompt](assets/formatted-call-logs/prompt-desktop.png)
- [手机 Prompt](assets/formatted-call-logs/prompt-mobile.png)

## 限制

全量测试、后端测试、真实 Provider 调用均未运行。格式化 LLM 视图针对当前 Chat Completions wire format；其他协议与被采集层排除的 SSE / 流式事件只提供可用原始正文，不声称补齐采集缺失。未实现逐消息 Token 图表、费用折扣拆分或 Provider 分段耗时，因为当前日志没有这些可靠数据。
