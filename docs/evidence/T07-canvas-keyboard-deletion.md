# T07 画布键盘删除

日期：2026-09-27

## 行为变化

- 选中卡片后按 Delete 或 Backspace 移除该卡片；选中关系线后按 Delete 或 Backspace 删除该关系。两个键共用同一处理路径（`deleteKeyCode={["Backspace", "Delete"]}`）。
- 关系线是投影而不是存储的边，所以删除落到业务写入上，与卡片上的同名动作走同一批应用服务：蓝线（Artifact→Agent 输入绑定）走 `updateAgent` 移除该条 binding（新增 `removeInputBinding`），灰虚线（素材引用）走 `reviseArtifact` 生成不含该引用的新版本（复用既有的 `removeReference`）。
- 只有可编辑的关系能删。绿线（Agent 输出组）由 `agent.outputGroupId` 与卡片的 `groupId` 推导，没有关系记录，因此投影时标记 `deletable: false, selectable: false`：它既不进入选中态，也不参与删除。必填场景引用只能替换不能删除，解析函数对它返回 `null`。
- 移除卡片不写关系。React Flow 会把与被删卡片相连的边一起交上来（`getConnectedEdges`），但被移除的只是展示卡片，Artifact 与它参与的绑定、引用仍然有效，因此级联来的关系线在写库前被过滤掉。
- 删除不先改本地。`onBeforeDelete` 发起业务写入后返回 `false`，让 React Flow 放弃自己的本地删除：卡片或关系线只在服务端投影确认后才消失，写入失败时画布保持原样，不会留下幽灵元素。
- 受控边的选中态是本地的：React Flow 对受控 `edges` 不会自己应用选中变更，只在 `onEdgesChange` 里上报，因此页面新增 `selectedEdgeIds` 并把 `selected` 写回投影。这是这条线唯一由前端拥有的部分。
- 关系线的颜色与线型从内联 `style` 移到 CSS 类（`relation-edge--input-binding` / `--agent-output` / `--reference`），因为内联样式会压过选中态；选中态在 CSS 里提亮加粗（白色 3.5px，虚线转实线）。
- 输入期间的按键不会误触：React Flow 的按键处理在输入框、文本域、可编辑元素内直接忽略（`isInputDOMNode`）。

## 涉及文件

- `frontend/src/features/canvas/canvasRelations.ts` 及测试：投影改用 `className` 并标记输出组线不可选/不可删；新增导出类型 `ArtifactInputReference`、`CanvasRelationRemoval` 与解析函数 `canvasRelationRemoval(items, edge)`。
- `frontend/src/features/canvas/ProjectWorkspacePage.tsx`：`deleteKeyCode`、`onBeforeDelete`（写入并返回 `false`）、`selectedEdgeIds` + `onEdgesChange`、`removeInputBinding` mutation，以及「选择与对齐」面板的删除说明。
- `frontend/src/styles.css`：关系线与选中态的 CSS 类。
- `frontend/src/features/canvas/CanvasKeyboardDeletion.test.tsx`（新增）：真实 React Flow 下的按键删除。
- `frontend/src/features/canvas/CanvasRelationDeletion.test.tsx`（新增）：关系线删除的路由与拒绝。
- `docs/MVP-SPEC.md`、`docs/DEVELOPMENT-CHECKLIST.md`、`design-qa.md`：同步交互语义。

没有修改后端、API 合约、数据库迁移或依赖：删除复用既有的 canvas 命令、Agent 更新与产物修订接口。

## 实际检查

以下命令在 `frontend` 执行；没有运行全量测试。

| 检查 | 结果 |
| --- | --- |
| `corepack pnpm exec vitest run src/features/canvas` | 26 个文件、169 项通过（本次新增 10 项） |
| `corepack pnpm exec tsc --noEmit` | 通过 |
| `corepack pnpm exec eslint . --max-warnings=0` | 通过，零 warning |
| `git diff --check` | 通过 |

真实浏览器核对（临时预览页用真实投影数据渲染三类关系线，Chrome `--headless=new` 1440×900 + CDP 真实指针与按键事件，核对后删除预览文件）：11/11 项通过

- 三类关系线按类型着色（`#2563eb` / `#64748b` / `#059669`），素材引用线保持虚线，输出组线带 `inactive`（不参与选中）。
- 点击输入绑定线进入选中态，选中态为白色 3.5px；按退格触发一次删除，交付集合恰为该条边、不含节点。
- 点击 Agent 卡片进入选中态；按 Delete 触发一次删除，集合为该卡片且级联关系线已被剔除。
- 点击输出组线后仍不进入选中态，按退格不会删掉它。
- 焦点在文本域内时按退格不触发画布删除（前后删除次数不变）。

## 未验证限制

浏览器核对用的是没有后端的预览页：写入侧只记录 `onBeforeDelete` 交付的元素，没有真正发出请求；真实的 Agent PATCH 与产物修订请求体由组件测试（`CanvasRelationDeletion.test.tsx`）与单元测试覆盖，未在浏览器里对真实服务端跑过。`frontend/e2e/manual-storyboard-browser.mjs` 未运行（需要独立实例与引导密钥），本轮未调用真实媒体或模型 Provider，本地 Compose 镜像未重新构建。
