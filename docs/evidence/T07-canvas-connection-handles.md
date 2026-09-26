# T07 画布连接点收敛

日期：2026-09-26

## 行为变化

- 连接点声明集中到 `CanvasHandle`：`artifact-output`（出口）、`artifact-input` 与 `agent-input`（落点）、`agent-output`（Agent 输出组锚点）四者的位置、方向和图标只声明一次，卡片之间不再可能分叉。
- 画布静止时只在选中卡片的右侧绘制出口连接点：18px 圆点、内嵌 11px 方向图标、悬停放大到 22px 并转为强调色实心。未选中卡片、全部左侧落点与 Agent 输出组锚点都不绘制，消除双向端口带来的方向歧义。
- 连接点分两层，几何与可见外观解耦。外层是 React Flow 的几何盒，中心固定在卡片边框上，尺寸压到 4px：React Flow 的连线端点按盒子外边算（`getEdgePosition` 对 `Position.Right` 取 `handle.x + handle.width`），盒子一旦放大，线的两端就会各内缩半个盒子，静止关系线看起来与卡片断开。内层是可见圆点，内边固定在「卡片边框 + 4px」，因此悬停放大只向外长。圆点必须整体在卡片外：卡片在 DOM 中排在连接点之后会盖住边框内的一侧，选中外圈 `box-shadow: 0 0 0 2px` 又压在边框外 2px 上，圆点骑在边框上时只剩一条月牙。
- 出口的命中区域用 `::after` 只向卡片外扩展，覆盖整个可见圆点；向内不越界，因此选中卡片的边框以内仍然是拖动卡片、框选的区域，不会误触连线手势。图标保持 `pointer-events: none`。
- 左侧落点仍是 React Flow 的受控落点，只是不可见且不接收指针：命中区域不再用 `::after` 扩大，落点由 `connectionRadius`（20 → 80 流坐标单位，随缩放保持同等手感）的距离判定成立。手势进入范围时落点才浮现，松手后回到隐藏，因此不可见端口不会抢走卡片内容或画布框选的指针。
- 拖拽合法性反馈复用写入路径的两条判定：新增 `isCanvasConnectionValid` 分别调用 `inputConnectionUpdate` 与 `semanticConnectionRevision`，通过 `isValidConnection` 驱动「合法落点实心强调 / 非法落点与连接线转红」。已引用版本的重复拖放沿用提交路径的 no-op 成功语义，不判为非法。
- 单击连接点建立连线关闭（`connectOnClick={false}`）：落点不可见也不可点，单击建连会进入没有可见落点的死路，建立关系只剩从右侧出口拖出的手势。
- 图标统一 `pointer-events: none`：指针命中与 React Flow 的落点判定必须落在连接点本身，而不是其中的 svg。

## 涉及文件

- `frontend/src/features/canvas/CanvasHandle.tsx`（新增）：连接点声明与渲染；可见圆点包在内层 `.canvas-handle-dot`，外层只留 React Flow 的几何盒。
- `frontend/src/styles.css`：`.canvas-handle` 显隐、命中区域、悬停与连接态、合法/非法配色。
- `frontend/src/features/canvas/canvasRelations.ts` 及测试：新增 `isCanvasConnectionValid`，并把两个既有判定的入参类型放宽到 `Connection | Edge`（只读四个端点字段，行为不变）。
- `frontend/src/features/canvas/ProjectWorkspacePage.tsx`：改用 `CanvasHandle`、`connectionRadius`、`isValidConnection`、`connectOnClick={false}`，并把「选择与对齐」面板里的手势说明改为「选中后从右侧连接点拖出、靠近可用落点时落点才浮现」。
- `frontend/src/features/canvas/AgentChatCard.tsx` 与 `AgentChatCard.css`：改用 `CanvasHandle`，删除按卡片覆写的连接点背景色。
- `frontend/e2e/manual-storyboard-browser.mjs`：手势改为「先点选源卡片，再从可见出口拖到目标」，并断言落点在静止时隐藏、手势中浮现且被判定为合法。
- `docs/MVP-SPEC.md`、`docs/DEVELOPMENT-CHECKLIST.md`、`design-qa.md`：同步交互语义。

没有修改后端、API 合约、数据库迁移或依赖。

## 实际检查

以下命令在 `frontend` 执行；没有运行全量测试。

| 检查 | 结果 |
| --- | --- |
| `corepack pnpm exec vitest run src/features/canvas` | 24 个文件、159 项通过；含新增的 `isCanvasConnectionValid` 三项断言 |
| `corepack pnpm exec tsc --noEmit` | 通过 |
| `corepack pnpm exec eslint . --max-warnings=0` | 通过，零 warning |
| `git diff --check` | 通过 |

真实浏览器核对（临时预览页 + Chrome `--headless=new` 1440×900 + CDP 真实指针事件，预览页按 `ArtifactCardFrame` 的 `#262626` 表面与选中外圈 `box-shadow: 0 0 0 2px #fff` 复现真实几何，核对后删除）：16/16 项通过，覆盖

- 未选中时出口 `opacity: 0`、`pointer-events: none`，圆点位置 `elementFromPoint` 不返回连接点；左侧落点与 Agent 锚点同为隐藏。
- 点选卡片后圆点整体落在卡片右侧之外（间隙 4 流坐标 px），与选中外圈有可见余量；按 18px 渲染（与画布缩放无关）；11px 图标完整落在圆点内；抓圆点命中 `artifact-output`；卡片边框处不再是命中区；几何盒中心仍精确落在边框上（`boxCenterX === cardRight`）。
- 悬停后圆点放大到 22px 且内边仍不压卡片。
- 拖拽中出现连接线；靠近目标落点时该落点在其卡片左侧之外浮现（`opacity: 1`、带 `valid`）；移到非法目标时转 `rgb(255, 77, 79)`、无 `valid`；非法松手不提交，合法松手提交一条 `source=image/sourceHandle=artifact-output/target=character/targetHandle=artifact-input` 的连线。
- 静止关系线的左右端点与两端卡片边框的内缩各为 2.5 流坐标 px（等于几何盒的一半），即线贴到边框、只被 2px 选中外圈遮住。

## 未验证限制

浏览器核对使用的是一次性预览页，渲染真实的 `CanvasHandle`、真实 `styles.css` 与真实 React Flow，并按真实卡片表面复现了选中外圈，但没有登录、没有后端数据，也没有在完整工作区里跑一遍语义引用与 Agent 绑定写入。完整应用中的端到端手势仍需登录后核对，`frontend/e2e/manual-storyboard-browser.mjs` 已按新手势改写但本轮未运行（需要独立实例与 `AGENVAS_E2E_URL`、`AGENVAS_E2E_BOOTSTRAP_SECRET`）。本轮未调用真实媒体或模型 Provider。

几何修正（圆点移出卡片、几何盒缩到 4px）之前构建过一次本地 Compose 镜像，该镜像不含本次修正；修正后只重跑了前端定向检查与浏览器核对，镜像需重新构建才能反映。
