# T07 图片编辑栏比例与节点联动

日期：2026-09-30

## 实际行为

图片编辑栏选择明确的生成比例时，画布节点立即保持长边并调整另一边，空节点和已有结果节点均适用。当前未保存比例优先于已保存草稿，已保存草稿比例优先于结果像素比例；保存失败时保留编辑器中的比例和节点预览。AUTO 或未设置比例时回退到当前显示图片的原始比例，无图片则保留现有布局。已有结果完整显示，不拉伸、不裁切，不更改图片字节或选用版本。

比例仍通过既有媒体草稿自动保存及 CAS 持久化，刷新从草稿恢复，不额外提交布局命令。临时比例按项目和节点隔离；没有未保存输入时卸载即可清理，有未保存输入时保留到保存成功，失败则保留以供恢复。持久数据仍由 TanStack Query 管理。

## 涉及文件

- `MediaDraftEditor.tsx`、`canvasStore.ts`：向画布共享编辑器临时比例。
- `useImageNodeRatios.ts`、`imageNodeLayout.ts`、`mediaDisplay.ts`：比例优先级、尺寸投影与展示约定。
- `ProjectWorkspaceImageLayout.test.tsx`、`imageNodeLayout.test.ts`：即时联动、空态、失败保留、保存刷新、AUTO 回退及全部明确比例。
- `docs/MVP-SPEC.md`、`docs/DEVELOPMENT-CHECKLIST.md`、ADR 0005：同步交互规则。无 API、后端或数据库迁移。

## 实际检查

在 `frontend` 目录执行：

- `./node_modules/.bin/vitest run src/features/canvas/ProjectWorkspaceImageLayout.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/canvas/imageNodeLayout.test.ts src/features/canvas/MediaCanvasCard.test.tsx`：4 个文件、105 项通过。修改前新增的两项回归均失败，分别证明点击比例不改变结果节点、已保存草稿比例不改变占位节点；修改后通过。
- `./node_modules/.bin/tsc --noEmit`：通过。
- `./node_modules/.bin/vitest run src/features/canvas/canvasStore.test.ts`：3 项通过；合计 108 项定向测试通过。
- 修改文件的 `./node_modules/.bin/eslint ... --max-warnings=0`：通过。
- `git diff --check`：通过。

## 未验证范围

测试使用 MSW 模拟 HTTP，画布投影测试替换 React Flow 的指针及 DOM 几何，不是浏览器视觉验收。真实浏览器操作、真实 Provider 调用、全量测试和生产构建未运行。


## 关闭编辑器保存回归（同日补充）

上一轮漏测了自动保存延迟期间关闭编辑器的路径：650 毫秒计时器在卸载时取消，临时比例又被清除，造成回弹。关闭工作编辑器现在立即提交未保存输入，保存期间保留节点比例；已发出的保存请求被复用，若期间发生了新编辑，则等待原请求完成后使用返回版本号保存最新输入。后台保存失败时保留完整草稿，重新打开恢复提示词、参数和错误，不自动重试冲突。后台保存尚未完成时重新打开显示等待，避免重复提交。

涉及 `MediaDraftEditor.tsx`、`canvasStore.ts`、新增 `mediaDraftCloseSave.ts`，以及编辑器和画布投影测试；规格、ADR 0005 和清单同步。没有 API 或数据库变更。旧卸载清理行为的定向复现测试实际失败：选择 9:16 后点击画布，预期节点 168.75×300，实际回到 300×15；启用修复后同一路径通过。

本次实际检查：

- `./node_modules/.bin/vitest run src/features/canvas/ProjectWorkspaceImageLayout.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/canvas/canvasStore.test.ts src/features/canvas/imageNodeLayout.test.ts src/features/canvas/MediaCanvasCard.test.tsx src/features/canvas/ProjectWorkspacePage.test.tsx`：6 个文件、133 项通过。新增覆盖点击画布关闭、关闭按钮、已有请求去重、后续比例的 CAS 串行保存、立即重开等待，以及关闭保存失败后的完整输入恢复和显式重试。
- `./node_modules/.bin/tsc --noEmit`：通过。
- 本次修改的前端文件 `./node_modules/.bin/eslint ... --max-warnings=0`：通过。
- `git diff --check`：通过。

真实浏览器视觉验收、全量测试、生产构建和真实 Provider 调用未运行。本地恢复输入只存在于当前应用会话；成功保存后的数据由服务端恢复，保存失败后强制刷新页面仍不能承诺恢复尚未持久化的输入。
