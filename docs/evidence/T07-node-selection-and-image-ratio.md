# T07 节点选中与图片比例修正

日期：2026-09-26

## 行为变化

- 媒体和内容节点共用的 `ArtifactCardFrame` 移除覆盖 React Flow 坐标变换的固定位置样式，工具栏位于对应节点上方，随节点移动及视口平移、缩放更新位置；空选和多选不显示单节点工具栏。
- Artifact 与 Agent 节点未选中时不绘制外边框；选中时使用贴合卡片圆角、不占内部空间的白色外圈。缩放控制器的框线与八个控制点均透明，保留边缘和角落的拖动热区及缩放光标，不影响连线端点。
- 图片预览继承节点圆角，移除卡片边框所占的内距。保留 `object-fit: contain`，完整展示图片，不裁切去填充旧节点矩形。
- 节点依据当前显示 Asset 的原始宽高调整自身比例，保留长边大小。切换横图、竖图或选用版本后重新投影；空态与尺寸不可用时保留原尺寸。结果图片的手动缩放锁定比例。
- 预览与布局复用相同的草稿/结果及素材判定；按素材去重查询原始元数据，尺寸读取失败可重试。未选用的历史结果不参与当前节点尺寸计算。
- 读取图片与 DOM 测量不自动写入布局。拖动、缩放、对齐复用尺寸逻辑并使用既有 CAS 命令保存；极长图的短边按存储约束归一化，刷新时通过原图比例和长边恢复显示尺寸。失败保留草稿，对齐成功仅清理此次提交的节点草稿。

## 涉及文件

- `frontend/src/features/canvas/ArtifactCardFrame.tsx`、对应 CSS 与测试：工具栏定位和共享节点表面。
- `frontend/src/features/canvas/AgentChatCard.css`、`frontend/src/styles.css`：Agent 选中状态、缩放控制器、媒体边缘及尺寸失败提示。
- `frontend/src/features/canvas/imageNodeLayout.ts`、`mediaDisplay.ts`、`useImageNodeRatios.ts`：尺寸计算、展示素材判定与查询复用。
- `frontend/src/features/canvas/ProjectWorkspacePage.tsx`、`MediaCanvasCard.tsx` 及相关测试：节点投影、布局保存、比例缩放与失败重试。
- `docs/MVP-SPEC.md`、`docs/DEVELOPMENT-CHECKLIST.md`、`design-qa.md`：同步用户确认的交互要求及验收限制。

没有修改后端、API 合约、数据库迁移或依赖。

## 实际检查

以下命令在 `frontend` 执行；没有运行全量测试。

| 检查 | 结果 |
| --- | --- |
| `corepack pnpm exec vitest run src/features/canvas/ArtifactCardFrame.test.tsx` | 2 项通过；使用真实 React Flow 验证节点移动、视口平移/缩放、切换选中、空选和多选 |
| `corepack pnpm exec vitest run src/features/canvas/imageNodeLayout.test.ts src/features/canvas/ProjectWorkspaceImageLayout.test.tsx src/features/canvas/ProjectWorkspacePage.test.tsx src/features/canvas/MediaCanvasCard.test.tsx` | 4 个文件、38 项通过；覆盖原图比例、切换结果、空态、尺寸重试、共享素材查询、缩放、对齐、保存刷新与冲突保留 |
| `corepack pnpm exec vitest run src/features/canvas/MediaCanvasCard.test.tsx src/features/canvas/ContentCanvasCard.test.tsx src/features/canvas/AgentChatCard.test.tsx` | 修改共享表面后 3 个文件、29 项通过；其中媒体测试随后随比例修改扩充，并在上一组重新通过 |
| `corepack pnpm lint` | 通过，零 warning |
| `corepack pnpm build` | 通过，包含路由类型生成、TypeScript 检查和 Next.js 静态构建 |
| `git diff --check` | 通过 |

去除重复执行的媒体测试后，以上定向覆盖共 7 个测试文件、62 项测试。

后续按用户要求隐藏八个缩放控制点，仅调整共享样式与规格描述。重新运行 `corepack pnpm exec vitest run src/features/canvas/AgentChatCard.test.tsx src/features/canvas/ProjectWorkspaceImageLayout.test.tsx`，2 个文件、21 项通过；`git diff --check` 通过。透明热区保留既有缩放交互，未增加只校验 CSS 实现细节的测试。

## 未验证限制

本轮内置浏览器访问 `http://localhost:5173/projects` 返回 `net::ERR_BLOCKED_BY_CLIENT`，没有完成截图、真实拖拽或边缘贴合的视觉验收；坐标和组件测试不替代该验收。待验项目记录于根目录 `design-qa.md`。本轮未调用真实媒体 Provider。

后续排查确认：本地 5173 端口仍有服务监听；内置浏览器访问同一地址再次被拒绝，标签停留在 `about:blank`，该空白页可以成功截图。因此此次阻断发生在页面导航阶段，没有证据表明是截图权限不足。页面错误日志为空，当天 Codex 应用日志未发现匹配此地址或错误码的详细记录，具体客户端拦截原因尚不明确；未修改权限或绕过浏览器限制。

再次核对运行部署后更正：本项目当前 Docker Web 入口为 `http://localhost:8088`（`agenvas-web-1`，Compose 路径指向本仓库），5173 实际是 Next.js 迁移前遗留的 Vite 进程。Chrome 访问 5173 同样被拒绝，但访问正确的 8088 成功加载 Agenvas 登录页并成功截图，因此此前使用了错误的服务地址，不能据此判断当前项目不可访问或截图权限不足。5173 的具体客户端拦截规则仍未定位；正确地址无需修改浏览器权限即可访问。此次只验证到登录页，画布的交互与截图验收仍待登录后完成。

用户随后指定 Chrome“日常”实例及 `127.0.0.1:8088` 项目页，已使用其现有登录会话打开 Docker 画布并截图，确认该部署仍展示旧节点样式。按用户要求停止遗留 Vite 后，以 `corepack pnpm dev --hostname 127.0.0.1` 启动当前 Next.js，地址为 `http://127.0.0.1:5173`；Docker 部署未重启。Chrome 已加载当前工作区的项目画布并截图：选中图片完整展示，外圈贴合圆角且无白色缩放点；通过拖动画布空白处平移视口，确认工具栏保持在图片节点上方。该检查没有运行生成、改动媒体或保存节点布局；未覆盖所有比例、真实尺寸拖拽及锁定状态的视觉回归。

## 2026-09-27 取消选中同步

用户反馈：点击画布或移动画布后，被选中的卡片没有取消选中。

定位到两处独立原因：

- **React Flow 用 select 变更同步受控节点的选中态。** 点空白走 `resetSelectedElements`、拖出选框走 `getSelectionChanges`、点关系线走 `addSelectedEdges`，三者都会发出 `select` 变更并交给 `onNodesChange`；页面此前只处理 `position` 与 `dimensions`，所以画布上已经取消选中，应用侧的 `selectedIds` 仍保持原样——高亮、底部编辑器都不会退出。关系线一侧早就处理了 select 变更，节点一侧的缺失正是这个不对称。
- **平移与缩放画布不发 select 变更。** 需要在视口移动时显式清除：新增 `clearSelection`，由 `onMoveStart` 在事件非空（用户发起的平移或缩放）时清除节点与关系线选中。程序化视口移动（`event` 为 null，例如“查看输出”后的 `fitView`）不清除，否则刚选中的输出卡片会被立刻清掉。

涉及文件：`frontend/src/features/canvas/ProjectWorkspacePage.tsx`（`handleNodesChange` 接受 select 变更、`clearSelection`、`onMoveStart`）、`frontend/src/features/canvas/CanvasSelectionClearing.test.tsx`（新增）。

实际检查：`corepack pnpm exec vitest run src/features/canvas` 28 个文件、179 项通过；`tsc --noEmit`、`eslint . --max-warnings=0`、`git diff --check` 通过。

浏览器核对（临时预览页 + Chrome `--headless=new` + CDP 真实指针事件）6 项中 5 项通过：

- 选中卡片时 React Flow 发出 `select: true`；点空白发出 `select: false` 且卡片确实回到未选中。
- 中键拖动平移发出带事件的 `onMoveStart`，视口坐标随之变化。
- 未通过的一项：拖动小地图时 `onMoveStart` 的事件为 null（小地图是程序化平移），因此当前不会清除选中。应用自己的程序化移动与小地图共用 null 事件，无法在守卫里区分；要覆盖它需要额外区分“节点拖动自动平移”等场景，本轮未做。

未验证限制：以上是 React Flow 契约核对与 jsdom 页面测试；完整工作区里的真实点击、平移与底部编辑器退出未在浏览器中做视觉验收。本轮未运行 e2e 脚本、未重新构建本地镜像、未调用真实 Provider。

### 2026-09-27 工具栏点击取消选中回归修复

- 原因：`NodeToolbar` 通过 Portal 位于节点 DOM 外，未继承节点的平移隔离；鼠标按下按钮触发视口 `onMoveStart`，页面清空选择使工具栏在 click 前卸载。
- 修复：共享 `ArtifactCardFrame` 的 `NodeToolbar` 容器增加 `nopan`，工具栏操作不再启动画布平移；画布取消选择的原有逻辑保持有效。无 API 合约或数据库迁移。
- 回归：新增 `ArtifactCardFrameGestures.test.tsx`，使用真实 React Flow 和 NodeToolbar，模拟鼠标按下、松开、点击，断言工具栏保持挂载且按钮动作执行一次。修复前在“按钮仍在文档中”断言失败，修复后通过。
- 定向验证：`pnpm test src/features/canvas/ArtifactCardFrame.test.tsx src/features/canvas/ArtifactCardFrameGestures.test.tsx src/features/canvas/CanvasSelectionClearing.test.tsx src/features/canvas/MediaCanvasCard.test.tsx src/features/canvas/ContentCanvasCard.test.tsx`，5 文件 26 项通过。
- 限制：以上为 jsdom 组件验证；未运行全量测试、真实浏览器触控验收或真实 Provider 调用。

### 2026-09-27 选中状态回写简化

用户反馈：点击节点选择会偶发不生效。

已确认的行为与待验证的原因：

- 受控 `nodes` 更新会通过 `setNodes` / `adoptUserNodes` 同步到内部 `nodeLookup`，`onSelectionChange` 可以观察到同步后的选择。此前“受控 selected 不回写内部标记”和“回调从来看不到追加选择”的解释不正确。
- `addSelectedNodes` 的多选分支只发送追加 select 变更，不立即修改内部标记；应用处理变更并回传受控 nodes 后，内部标记仍会同步。原探针中未见选中类名，不能证明完整受控链路不会更新。
- 原 `onNodeClick` 无条件 `setSelectedIds([node.id])` 会覆盖追加选择；修饰键点击现在交给 React Flow 的 select 变更处理。默认追加键是 Cmd（macOS）或 Ctrl（其他系统）；Shift 用于框选，不是默认的点击追加键。
- “过期的 onSelectionChange 整体回写造成偶发选择失败”仍是假设，现有测试未复现原始失败时序，不能据此确认根因或宣称偶发问题已消失。

实现：保留 `handleNodesChange` 增量更新，移除 `onSelectionChange` / `handleSelectionChange` 对同一应用状态的整量回写；普通点击选中单张，修饰键点击由 React Flow 处理。选择仍由应用状态控制。

验证范围：

- `CanvasSelectionClearing.test.tsx` mock React Flow 并直接驱动页面回调，覆盖 select 变更、普通点击、Cmd 追加、用户视口移动与程序化移动。它不验证真实组件的事件顺序。
- 本次复核运行选择清除、工具栏位置和工具栏手势三个测试文件，共 9 项通过。
- 额外临时测试使用真实 React Flow：修改受控 selected 后，断言 nodeLookup 包含新增选择且 onSelectionChange 收到更新，1 项通过。临时测试已移除。
- 此前记录的画布目录 180 项、类型检查与 lint 通过属于上一轮检查，本次复核未重复执行。此前一次工具栏测试失败发生于并行修改期间，原因未确认，不能直接归因为测试偶发。

未验证限制：尚无能在旧实现失败、在新实现通过的原始偶发问题回归用例；完整工作区里的真实点击、Cmd/Ctrl 追加、框选与底部编辑器退出仍未在浏览器中验收。本次未运行全量测试、构建镜像或真实 Provider 调用。

### 2026-09-27 普通点击去除重复写入与真实组件回归

- `onNodeClick` 不再无条件写入 selectedIds。普通点击未选中的节点、Cmd/Ctrl 追加与取消均由 React Flow 的 `onNodesChange` 更新应用状态。
- 保留一个已有交互：多选后普通点击其中一张，收拢为单选。真实 React Flow 默认不会在这个场景发送取消其他节点的变更；直接删除整个回调时对应测试失败。因此回调只在此场景构造其他节点的取消选择变更，复用 `handleNodesChange`，不另行整量覆盖 selectedIds。
- 程序选择及双击聚焦编辑的入口保持不变；不手动修改生产环境的 React Flow 内部节点。
- `CanvasSelectionClearing.test.tsx` 增加真实 React Flow + 工作区回调的交互测试，覆盖普通点击替换、macOS Meta 与其他平台 Control 追加/取消、多选收拢、Shift 框选后点击替换、程序选择同步及空白取消。原 mock 用例继续验证页面回调处理。
- jsdom 不提供真实布局：测试固定视口，并给内部节点补空的已测量 handleBounds，避免 React Flow 将所有未测量节点算入选框。节点尺寸仍来自工作区投影，选择事件与状态同步使用真实库实现；这些几何补偿不进入生产代码。
- 实际检查：`pnpm test src/features/canvas/CanvasSelectionClearing.test.tsx src/features/canvas/ArtifactCardFrame.test.tsx src/features/canvas/ArtifactCardFrameGestures.test.tsx`，3 文件 14 项通过；`pnpm exec tsc --noEmit` 与两个改动 TSX 文件的 ESLint 通过。
- 限制：未运行全量测试或真实浏览器验收；本轮证明上述确定性交互，不代表已经复现或确认原始偶发问题根因。无 API 合约或数据库迁移。

### 2026-09-27 选择/手形工具与点击微移容差

- 新增 `canvasInteraction.ts`：默认选择、Space 临时手形、V 返回选择，释放/失焦/页面隐藏清除临时状态；输入框、可编辑文本、输入法组合和系统组合键不切换工具。
- 新增 `CanvasToolMenu.tsx`：参考用户图稿的深色竖条、粉色添加按钮、选择/手形图标及向左展开菜单；支持菜单勾选、方向键、Esc 和外部点击关闭。菜单选择持久生效，Space 仅临时覆盖。
- 工作区按工具配置框选、平移、节点移动、连接和双击添加。手形模式下卡片内容和缩放控件不接收指针，拖动卡片表面用于平移，不改节点布局。现有用户平移清空选择的规则保留。
- 点击/拖动节点阈值统一为 `CANVAS_POINTER_THRESHOLD = 3` 像素：此前默认点击容差 0、拖动阈值 1 导致 1 像素微移被吞掉。完整按下/微移/松开/click 回归覆盖 0、1 和 3 像素，均首次选中。
- 实际检查：选择交互、工具栏位置、工具栏手势三个文件共 21 项通过；TypeScript 与四个修改/新增 TSX/TS 文件的定向 ESLint 通过。真实 React Flow 工作区测试覆盖 Space 从节点表面平移、不移动节点、松开恢复、输入保护及模式切换。
- 内置浏览器打开临时组件预览，检查菜单布局、展开和手形勾选/图标状态；截图保存于 `/tmp/agenvas-canvas-tools.png`。这是独立组件预览，未连接后端，不能替代完整工作区验收。临时预览文件和服务已清理。
- 规格与 ADR 0005 已同步。无 API/数据库变更；未运行全量测试或真实 Provider，完整工作区浏览器手势验收仍未完成。

### 2026-09-27 空格短按与长按补充

- 按用户确认，短按空格切换并保持手形工具，V 返回选择；长按仍临时切换，松开恢复此前工具。以命名常量 `SPACE_HOLD_THRESHOLD_MS = 200` 毫秒区分短按/长按，按下时立即提供手形工具，无需等阈值。
- 按住空格期间发生 pointerdown 或键盘重复按键时，按临时手势处理；避免快速 Space + 拖动被误判成持久切换。失焦、页面隐藏或 V 取消临时状态，晚到 keyup 不会再切回手形。输入框里的空格不触发工具切换。
- 定向验证：`canvasInteraction.test.ts` 和 `CanvasSelectionClearing.test.tsx` 共 24 项通过；TypeScript 与四个相关文件 ESLint 通过。未运行全量测试或本轮真实浏览器手势验收。无 API/数据库变更。

### 2026-09-30 节点点击与拖动分离

- 用户决定：点击才显示工具栏与编辑区；拖动时保留节点选中外圈，松手取消外圈及选择。移动不超过 3 屏幕像素沿用点击微移容差，超过阈值由 React Flow 的拖动回调进入临时反馈。
- `ProjectWorkspacePage` 关闭 `selectNodesOnDrag`，不把未选中节点的拖动反馈写入普通 `selectedIds`。拖动节点 ID 独立投影为卡片外圈；拖动期间隐藏所有节点工具栏、外置编辑区和多选批量栏，已选中节点也如此。松手立即清除临时反馈及节点/线选择，不等待保存。后续独立点击及 Cmd/Ctrl 追加、框选仍使用原选择逻辑。
- `ArtifactCardFrame`、`ContentCanvasCard`、`MediaCanvasCard` 增加工具栏显隐参数，将卡片外圈与工具栏分开控制。多节点拖动在结束时一次提交所有移动节点的布局；缩放仍使用同一布局保存路径，保存失败保留草稿和错误状态。`ProjectWorkspaceImageLayout` 测试模拟的批次节点现在提供真实结束位置。
- 新增 4 项真实 React Flow 工作区测试，覆盖未选中节点拖动期间仅外圈、松手后的 click 抑制及下一次点击恢复、已有文字选择的工具栏/编辑区隐藏、多选组高亮与批量布局、保存冲突后外圈取消而位置草稿保留。既有 0/1/3 像素微移测试补充按下/微移期间不显示浮层、点击完成才显示的断言。
- jsdom 无实际视口边界，测试关闭节点拖动边缘自动平移，避免虚拟零尺寸视口改变位置断言；生产自动平移保留。首次定向验证中该几何问题使一个位置断言失败，调整测试视口补偿后重跑通过。
- 实际检查：`node_modules/.bin/vitest run` 定向运行 `CanvasSelectionClearing.test.tsx`、`ProjectWorkspaceImageLayout.test.tsx`、`ArtifactCardFrame.test.tsx`、`ArtifactCardFrameGestures.test.tsx`、`ContentCanvasCard.test.tsx`、`MediaCanvasCard.test.tsx`、`CanvasKeyboardDeletion.test.tsx`、`CanvasConnectionDrop.test.tsx`、`ProjectWorkspacePage.test.tsx`，9 个文件 86 项通过；补充点击微移浮层断言后 `CanvasSelectionClearing.test.tsx` 22 项再次通过。`tsc --noEmit`、6 个修改 TSX 文件的 `eslint --max-warnings=0`、`vite build`、`git diff --check` 通过。
- MVP 规格、ADR 0005 和任务清单同步，无 API 合约或数据库迁移变更。本轮未运行全量测试、后端测试、浏览器端到端、触屏或真实浏览器手势验收，未调用真实 Provider、未部署。
