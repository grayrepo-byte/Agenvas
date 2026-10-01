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

## 2026-09-27 卡片级落点与双击不缩放

按用户反馈定位并修正两个问题：

- **双击空白画布会缩画布。** React Flow 的 `zoomOnDoubleClick` 默认为 true，d3 的 dblclick 缩放与应用用于打开添加菜单的 `onDoubleClickCapture` 同时生效。改为 `zoomOnDoubleClick={false}` 后双击只开菜单；添加菜单本身仍由应用自己的双击处理打开。
- **连线必须对准左侧连接点。** 落点原来只按「指针到连接点的距离」（`connectionRadius` 80 流坐标单位）判定，大卡片中部离左侧连接点很远。现在手势期间用 `document.elementFromPoint` 命中指针下的卡片，`canvasTargetHandleId` 给出该卡片的落点 handle、`isCanvasConnectionValid` 判定合法性，松手时若 React Flow 没有解析到连接点就按这张卡片提交；指针所在的卡片由节点组件渲染高亮层（`canvas-connection-halo`，合法为强调色、非法为红色）。源卡片自身不作为落点，否则按下出口的那一刻就会闪出一次无效反馈。已有的连接点距离判定保留，落点在连接点附近仍走 React Flow 自己的提交路径。

两条被否掉的实现路径（写在这里避免重复踩）：React Flow 节点的 `className` 只在首次接管用户节点时记录，动态改它不会反映到 DOM；`onNodeMouseEnter/Leave` 在「按下连接点时指针已经在源节点内」的场景不会再触发，因此按指针位置直接命中测试更可靠。

涉及文件：`frontend/src/features/canvas/ProjectWorkspacePage.tsx`（`zoomOnDoubleClick`、手势跟踪与提交、节点数据里的 `connectionTarget`）、`frontend/src/features/canvas/CanvasHandle.tsx` 与 `canvasRelations.ts`（`canvasTargetHandleId`）、`frontend/src/styles.css`（高亮层）、`frontend/src/features/canvas/CanvasConnectionDrop.test.tsx`（新增）。

实际检查：`corepack pnpm exec vitest run src/features/canvas` 27 个文件、175 项通过；`tsc --noEmit`、`eslint . --max-warnings=0`、`git diff --check` 通过。浏览器核对（临时预览页 + Chrome `--headless=new` + CDP 真实指针事件）15/15 项通过：双击空白画布缩放矩阵不变且仍打开添加菜单；从出口拖到目标卡片中部（距连接点 153px）松手即建立关系线；不可建立关系的卡片显示红色高亮且松手不提交；没有当前版本的卡片不成为落点；源卡片自身不作为落点、自连松手不产生连线；手势结束后高亮清除。

未验证限制：浏览器核对使用的预览页没有后端，写入侧只核对「交付的连接对象」；完整工作区里的真实语义引用修订与 Agent 绑定写入由组件测试覆盖，未在浏览器中对真实服务端跑过。本轮未运行 `frontend/e2e/manual-storyboard-browser.mjs`，未重新构建本地 Compose 镜像，未调用真实 Provider。

### 2026-09-27 关系线去掉文字

用户反馈：取消连线上的文字说明（例如“输入”）。投影出的关系线此前带 `label`（“输入”“输入 · 历史版本”“Agent 输出组”“素材引用 · 角色”），现在三条投影分支都不再设置 `label`，关系类型只靠颜色与线型区分：蓝线是输入、绿线是输出组、灰虚线是素材引用。

“绑定指向历史版本”原本只由文字表达，去掉后改用线型：输入绑定指向非当前版本时投影额外加 `relation-edge--input-binding-historical`，CSS 给这条蓝线 `stroke-dasharray: 4 4`（选中态仍是实线白色）。「选择与对齐」面板的说明同步改为“蓝线是输入（指向历史版本时是虚线）”。引用角色、绑定版本等具体信息在卡片详情与 Agent 配置里查看，信息没有丢失。

涉及文件：`frontend/src/features/canvas/canvasRelations.ts` 及测试、`frontend/src/styles.css`、`frontend/src/features/canvas/ProjectWorkspacePage.tsx`（面板说明）。

实际检查：`corepack pnpm exec vitest run src/features/canvas` 28 个文件、179 项通过（含“关系线不带 label”“历史版本绑定带虚线修饰类”的断言）；`tsc --noEmit`、`eslint . --max-warnings=0`、`git diff --check` 通过。浏览器核对 5/5：四条关系线全部渲染、线上没有任何 `.react-flow__edge-textwrapper` 文字、当前版本输入为实线蓝线、历史版本输入为虚线蓝线、素材引用仍为灰色虚线；截图逐条比色确认。

未验证限制：浏览器核对使用无后端预览页（投影数据构造，未走服务端）；完整工作区里的观感、1280px 窄宽度与本地镜像未验收。

## 2026-10-01 圆圈加号与限幅悬停

按用户参考图将连接点的方向箭头替换为 Phosphor `PlusCircle` 细线图标：静止为灰色圆圈加号，悬停与连接态用主色和轻微缩放反馈。右侧入口只在选中时显示，左侧落点仅在拖线靠近时浮现，Agent 输出组锚点仍没有可见图标。

右侧入口的鼠标跟随只在静止中心附近的局部命中区内生效，距离上限为 28 个画布像素；跟随比例为指针距离的 35%，二维总位移最多 6 个画布像素。移开、超出距离、按下开始拖线或取消指针手势会复位；触摸、按住鼠标移动与减少动态效果下不位移。固定的 `canvas-handle-home` 测量静止位置，内层圆点通过 CSS 变量和过渡移动，避免追逐自身位置或逐帧触发 React/画布重渲染。距离按当前画布缩放换算。React Flow 的几何盒和连线端点不移动，卡片外侧间隙与命中区覆盖圆点的完整位移范围。

涉及 `CanvasHandle.tsx`、`CanvasHandle.test.tsx` 与 `styles.css`；同步规格 6.2、ADR 0005 和任务清单。没有 API 合约、后端、数据库迁移或依赖变更。

实际检查（在 `frontend` 执行）：

| 检查 | 结果 |
| --- | --- |
| `corepack pnpm exec vitest run src/features/canvas/CanvasHandle.test.tsx src/features/canvas/CanvasConnectionDrop.test.tsx` | 2 个文件、16 项通过；使用真实 React Flow Handle，jsdom 几何与 PointerEvent 为测试替身，覆盖二维限幅、范围外复位、0.5/2 倍缩放、离开/按下/取消、触摸/拖动/减少动态效果，以及既有卡片落点提交 |
| `corepack pnpm build` | TypeScript 检查与 Vite 生产构建通过；工作区 bundle 超过 500 kB 的体积提示仍存在 |
| `corepack pnpm lint` | 主题颜色检查与 ESLint 通过，零 warning |
| `git diff --check` | 通过 |

真实浏览器核对：Codex 内置浏览器打开一次性 Mock 预览，使用真实 `CanvasHandle`、React Flow 和项目样式。截图确认圆圈加号、卡片外侧位置，以及未选中入口/静止落点的隐藏；从可见圆圈执行实际指针拖放到目标落点后，预览显示一条新连线；手势结束后偏移变量已清除。浏览器控制台未记录 warning/error。核对完成后删除临时预览文件。

未验证限制：鼠标跟随的连续观感、完整登录工作区与真实服务端写入未做浏览器验收；限幅与复位由上述定向单元测试覆盖。未运行全量测试、后端测试或真实 Provider 调用，未重新构建/部署本地 Compose 镜像。
