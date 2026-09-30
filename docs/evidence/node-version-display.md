# 节点版本显示修正

日期：2026-09-30。产品规则见 [ADR 0017](../adr/0017-operation-specific-media-versioning.md)。

原因：媒体版本选择器直接显示 ArtifactVersion.versionNo，它是同一 Artifact 下所有节点共享的审计编号；按钮原先无条件显示，历史只在菜单打开时读取。现改为读取该节点自身历史，按审计编号升序计算从 v1 开始的连续展示编号，菜单最新结果优先。零/单版本隐藏按钮，第二个结果到达后显示；首次读取失败保留明确错误与重试。选用仍使用不可变版本 ID 和节点 expectedVersion，不修改审计记录、API 或数据库结构。

实现涉及 MediaVersionPicker.tsx、对应组件回归测试、MediaCanvasCard 的菜单关闭测试与公共 MSW 历史空态处理。同步 CONTEXT.md、MVP-SPEC.md、DEVELOPMENT-CHECKLIST.md、ADR 0017。

实际运行：

- 修复前定向执行 `pnpm test --run src/features/canvas/MediaVersionPicker.test.tsx -t 'numbers a derived|only one result'`，复现派生节点显示 v8、单版本按钮未隐藏；随后以已加载单版本历史重跑该断言，明确失败在按钮仍存在。
- 修复后 `pnpm test --run src/features/canvas/MediaVersionPicker.test.tsx src/features/canvas/MediaCanvasCard.test.tsx`：60 项通过。其中版本选择器 8 项覆盖审计编号有间隔时显示 v1/v2、真实版本 ID 与 CAS、单版本与空历史隐藏、追加第二版后显示、读取重试、冲突保留选择及 Escape。
- `pnpm typecheck`、修改文件的 `pnpm exec eslint ... --max-warnings=0`、`pnpm build`、`git diff --check` 通过。
- 使用 Chrome（没有使用内置浏览器）访问 5173 的独立验证环境：原图与画笔标注派生节点均只有一个结果，选中派生节点时版本按钮数量为零。复制该结果创建独立验证节点，通过明确标注的 Mock image 生成追加第二个结果，按钮出现，列表为 v2/v1。数据库只读查询确认该节点的两条审计编号实际为 2、4。选回旧图片后显示“版本 v1”，提示词仍为本轮测试草稿。

截图：[单版本派生节点](node-version-display/single-derived-chrome.png)、[多版本按节点编号](node-version-display/multiple-local-chrome.png)、[版本菜单细节](node-version-display/version-menu-chrome.png)。

限制：本轮仅修改前端显示，无 API 或迁移改动；未运行后端或全量测试。Chrome 实测为图片与 Mock 生成，未调用真实 Provider，未单独实测视频（图片/视频使用同一个版本选择组件）。未更新 8088 的部署镜像。
