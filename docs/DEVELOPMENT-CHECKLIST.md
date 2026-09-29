# Agent Canvas — 开发任务与验收清单

**规格版本：1.0｜目标版本：v0.1.0｜日期：2026-09-22**

本清单用于执行与验收，不代表任务已经完成。所有项目保持未勾选；只有存在相应代码、测试和运行证据时才可更新。

主规格：`MVP-SPEC.md`。冲突时先核对主规格与 ADR，不由编码助手自行选择更容易的一项。

2026-09-27 范围收缩：角色、场景、镜头三类产物、三镜头规划链路、执行计划与两阶段审批、关键帧选择、镜头局部重做与媒体导出已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 移出产品范围。相关任务与已勾选条目在下文逐条标注为「已随 ADR 0013 撤回」并保留原验收记录；未勾选的相关条目已改写或删除。

## 0. 工作规则

每个任务形成一个可以验证的增量。先实现最小正常路径，同时覆盖对应失败路径，再进入依赖它的任务。

禁止先把真实付费生成接到一个未持久化、无预检、无幂等的聊天循环里。Mock 与 Real 必须经过相同应用服务和任务状态机。

依赖含义：T05 依赖 T03，则 T03 的验收应先通过；不是只创建了文件就算完成。

---

## M0：工程、依赖、安全与合约基线

### T01 版本冻结与工程初始化

依赖：无。

交付：前后端目录、构建 Wrapper、精确依赖、lockfile、容器版本、`docs/dependency-baseline.md`。

- [x] JDK 21、Boot 4.0 基线、Spring AI 2.0.1 与数据访问 starter 可解析与编译。
- [x] 前端 stable 依赖锁定，Node/pnpm engines 一致，类型检查与生产构建可执行。
- [x] 不含 SNAPSHOT/RC/动态 latest，不重复引入 ORM/工具执行循环。

2026-09-26 前端构建迁移补验：按 [ADR 0009](adr/0009-nextjs-static-frontend.md)迁为 Next.js 16.3.6 静态导出，生产仍由 Nginx 托管且不增加 Node 运行时、Next.js API、Server Action 或 SSR 数据访问；client-only catch-all 保留原 URL 和 React Router 行为。OpenAPI 类型生成、TypeScript、ESLint、20 个文件/84 项 Vitest、Next 生产构建、Node 24 容器冻结安装/构建、Nginx 配置及 `/login`、未知项目 UUID 深链、Chrome 登录页水合均通过，见 [迁移证据](evidence/T01-nextjs-static-frontend.md)。

2026-09-26 前端构建回退：按 [ADR 0011](adr/0011-revert-to-vite.md) 撤销 [ADR 0009](adr/0009-nextjs-static-frontend.md) 的 Next.js 静态导出迁移，恢复 `index.html`、`src/main.tsx` 与 `vite.config.ts` 入口，构建产物由 `out` 回到 `dist` 并同步 Dockerfile、`.dockerignore` 与 e2e 脚本；`@vitejs/plugin-react` 随 dev server 一并恢复以提供 Fast Refresh。`pnpm install --frozen-lockfile`、TypeScript、ESLint、34 个文件/212 项 Vitest、Vite 生产构建，以及 dev server 的 `/api` 代理与未知项目 UUID 深链均通过，见 [回退证据](evidence/T01-vite-rollback.md)。未运行后端测试、容器镜像构建与浏览器视觉验收。

2026-09-26 数据访问迁移到 jOOQ：按 [ADR 0012](adr/0012-jooq-persistence.md) 把生产数据访问从 Spring `JdbcClient` 换成 jOOQ（24 个类、229 处调用点），jOOQ 生成源码提交在 `backend/src/jooq/java` 且构建期不连数据库，同时移除零引用的 MyBatis-Plus starter，并把状态魔法值收敛为枚举/常量。Surefire 112 项、Failsafe 80 项全部通过（先前两项既有失败经 A/B 对比确认与本次迁移无关并已修复），生成结果连续两次字节一致。未做真实 Provider 调用、容器镜像构建与前端浏览器验收，见 [迁移证据](evidence/T01-jooq-migration.md)。

### T02 默认三服务与 Mock 模式

依赖：T01。

交付：Compose、Nginx、server、PostgreSQL、持久卷、健康检查、Mock Provider。

- [x] 无模型 Key 和 GPU 时能启动，不产生未说明的外部请求。
- [x] 默认端口安全，SSE 代理配置正确，容器非 root。
- [x] Mock 状态醒目标识，支持可重复成功和失败 fixture。

### T03 数据迁移、初始化与会话

依赖：T01–T02。

交付：Flyway、管理员初始化、登录/退出/改密、Session JDBC。

- [x] 新数据库一次迁移成功；初始化竞态只创建一个管理员。
- [x] bootstrap secret、CSRF、会话失效、暴力尝试限制经过测试。
- [x] 重启后会话按设计保存；生产不存在无认证旁路。

补充：`/settings/general` 已接入管理员改密表单，复用现有 CSRF/会话失效 API；前端对密码确认、提交及清空输入有测试，见 `docs/evidence/T28-system-diagnostics-partial.md`。语言选择仍未实现。

部署安全补验：Compose 不再为数据库密码或管理员初始化密钥提供公开回退值，README/恢复命令显式读取根目录 `.env`，后端拒绝历史示例 bootstrap secret；见 `docs/evidence/T03-bootstrap-secret-fail-closed.md`。已有本机默认实例仍使用旧值，未获授权前不自动轮换。

### T04 API 合约与 CI

依赖：T01。

交付：OpenAPI、错误 Schema、TS 类型生成、测试脚本、CI 工作流。

- [x] 成功与错误状态符合合约，生成类型可用于前端请求。
- [x] 生成文件无漂移；失败用例不会被统一转换成 HTTP 200。
- [x] 真实密钥不进入 fork PR；依赖、镜像、密钥与许可证扫描可运行。

**M0 门禁**：可从干净环境启动与构建，并有实际测试输出；不是仅写好了 Dockerfile。

---

## M1：项目、画布、版本与 Agent 展示

### T05 项目和资源归属

依赖：T03–T04。

交付：项目 CRUD/归档、owner 边界、UUID/UTC 规范、分页。

- [x] 两用户测试夹具不能交叉读取/修改资源。
- [x] 归档后禁止新运行；版本冲突有明确错误。

### T06 Artifact 与不可变版本

依赖：T05。

交付：三类 Artifact Schema（TEXT / IMAGE / VIDEO）、Version、语义引用、当前版本选择。

- [x] 版本递增唯一，历史内容不可覆盖。
- [x] 引用验证同项目、存在性和类型，非法半截 JSON 不入库。
- [x] （已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）共享场景局部修改不会影响未选中的镜头。原验收证据见 `docs/evidence/T06-manual-storyboard-partial.md`；场景与镜头已不在产品范围内。

### T07 画布与持久化命令

依赖：T06。

交付：React Flow 投影层、卡片、拖拽缩放、选中/框选、布局命令。

2026-09-24 补充：已保存的输入绑定、Agent 输出组和当前可见精确版本素材引用现投影为不同样式的关系线；Artifact→Agent 手工连线保存绑定，Artifact→Artifact 手工连线通过受控内容修订建立精确版本引用，不触发执行。引用可从卡片移除并保留历史；历史引用不误连到新版卡片。所有画布保存命令将 409 与普通失败分开显示，未确认草稿不清除。浏览器真实指针手势已补验，且修复受控节点重建时连接点边界丢失的问题；大画布性能仍待验收，见 `docs/evidence/T07-canvas-persistence.md`。（本条记录中的角色/场景/镜头引用与「必填场景只能替换」部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27；原验收证据保留。）

- [x] 刷新后布局与内容恢复，保存失败保留草稿。
- [x] Query/Zustand/React Flow 没有三份独立内容状态。
- [x] 删除展示卡片不删除媒体；输入文字时不会误删节点。

2026-09-26 画布显示补充：按用户图稿实现纯媒体卡片、空态上传、浮动扩展工具栏、Prompt 编辑器、模型菜单、参数摘要和 Beautiful UI 加载态；修复保存草稿后刷新错误切换展示模式的问题。定向验证与未完成的浏览器视觉验收见 [T07 显示重构证据](evidence/T07-canvas-display-refactor.md)。本轮不增加草稿级尺寸/画质覆盖参数，未接入的扩展能力保持禁用。

2026-09-26 其他节点体验统一（已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 部分撤回，2026-09-27）：文字与其他内容节点复用黑色卡片/浮动工具栏/底部编辑器交互，保留版本与引用操作；视频增加精确输入图缩略图选择及播放加载/失败重试。内容草稿固定 CAS 基准并保留冲突输入。实现范围、定向验证与浏览器阻断见 [T07 节点统一证据](evidence/T07-other-canvas-nodes.md)；无 API 或数据库迁移，浏览器视觉验收仍未完成。角色、场景、镜头的节点编辑区随三类产物一并移除。

2026-09-26 节点交互修正：工具栏改为锚定节点上方并跟随节点/视口移动；节点未选中无外边框，选中外圈贴合圆角，去除缩放控件的直角框线。图片节点按当前图片原始比例适配尺寸、保持完整预览，不裁切图片填充旧矩形。定向检查与视觉验收限制见 [节点交互证据](evidence/T07-node-selection-and-image-ratio.md)。

2026-09-27 编辑区跟随节点（已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 部分撤回，2026-09-27）：文字、图片、视频的编辑区统一锚定节点下方，与上方工具栏保持相同跟随方式；媒体编辑区移除重复生成状态条，保留取消排队和异常处理。同步规格与 ADR 0005，无 API 或数据库迁移。5 个相关测试文件共 57 项通过，TypeScript 类型检查与修改文件 ESLint 通过；浏览器视觉验收未运行。角色、场景、镜头编辑区已移除。

2026-09-27 文字生成输入与输出分离：文字节点下方 Prompt 创建固定当前内容、版本和模型配置的 `TEXT_GENERATION` 持久 Task，完整响应落库后追加不可变版本；并发直接编辑时生成结果只进历史。工具栏“编辑内容”进入节点内直接编辑实际输出，版本标签切换当前版本，连线继续固定实际 ArtifactVersion。同步 OpenAPI、生成 TS、Flyway V47、规格与 ADR 0005。前端 6 个定向测试文件共 62 项通过，TypeScript 类型检查与 ESLint 通过；后端 `DirectTextGenerationPostgresIT` 在 PostgreSQL 17.11 上通过，编译通过。真实 Provider 调用和浏览器视觉验收未运行。

2026-09-26 连线入口收敛（按用户确认）：画布只在选中卡片右侧显示连接点，左侧落点与 Agent 输出组锚点不再绘制，但仍作为受控落点由距离判定接收连线；手势中合法落点与连接线为强调色、非法关系为红色且不提交，单击建连已关闭。实现、浏览器实测与未验证限制见 [连接点证据](evidence/T07-canvas-connection-handles.md)。

2026-09-27 画布键盘删除（按用户确认）：选中卡片按 Delete 或退格移除卡片，选中关系线按 Delete 或退格删除对应绑定或引用；绿线由 Agent 输出组决定，不可选中也不可删除。删除先写服务端再改投影，移除卡片不会连带删除绑定与引用。实现、浏览器实测与未验证限制见 [键盘删除证据](evidence/T07-canvas-keyboard-deletion.md)。

2026-09-27 双击与落点（按用户反馈）：双击空白画布只开添加菜单，不再缩放；连线落点按卡片判定，指针落在卡片任意位置松手即可建立关系，手势中指针所在卡片显示强调色/红色高亮外圈，源卡片自身不作为落点。定位与实测见 [连接点证据](evidence/T07-canvas-connection-handles.md) 的 2026-09-27 小节。

2026-09-27 卡片标题编辑（按用户反馈）：Artifact 卡片标题可双击原位编辑，Enter/失焦保存、Esc 取消；失败或冲突保留草稿。标题持久化在 CanvasItem，使用画布项 CAS，同一 Artifact 的其他卡片及 Artifact/ArtifactVersion 均不改变；V49 从既有 Artifact/Agent 名称回填旧卡片。验证记录见 [标题编辑证据](evidence/T07-canvas-item-title-editing.md)。

2026-09-27 取消选中（按用户反馈）：点空白、拖出选框、平移与缩放画布都清除选中；受控节点改为接收 React Flow 的 select 变更（此前后者被忽略，导致画布已取消选中而应用侧仍保留高亮）。原始偶发失败尚未复现，过期回写的根因假设未证实；受控 selected 仍会同步到 React Flow 内部状态。实现与验证范围见 [节点选中证据](evidence/T07-node-selection-and-image-ratio.md) 的 2026-09-27 小节。

2026-09-27 关系线去掉文字（按用户反馈）：线上不再显示“输入”等说明文字，关系类型只靠颜色与线型区分，历史版本的输入绑定改用虚线表达。实测见 [连接点证据](evidence/T07-canvas-connection-handles.md) 的 2026-09-27 关系线小节。

2026-09-28 媒体图片输入条（按用户标注图稿）：顶部只显示左上序号的纯图片缩略图，不再显示名称、版本或移动操作；悬停/聚焦显示右上关闭。关闭即明确取消引入，不追加确认，服务端以 CAS 原子清除该精确版本的所有来源、相关连线和结构化标签；失败可重试且保留并发编辑。拖拽和键盘排序保留。同步规格、ADR 0014、OpenAPI 与生成 TS，无数据库迁移；实现与验证见根目录 `design-qa.md`。

2026-09-27 选中状态回写简化（按用户反馈“点击节点偶发不生效”）：应用状态是选中的唯一权威，React Flow 的 select 变更只按增量应用，删除按 React Flow 内部标记整体回写的 `onSelectionChange` 通道；Cmd/Ctrl 点击追加选择不再被单击覆盖。原始偶发失败尚未复现，过期回写的根因假设未证实；受控 selected 仍会同步到 React Flow 内部状态。实现与验证范围见 [节点选中证据](evidence/T07-node-selection-and-image-ratio.md) 的 2026-09-27 小节。

2026-09-27 选择回写优化：普通点击不再重复写入 selectedIds；仅保留多选后点击已选节点收拢为单选的补充处理，并复用增量更新。真实 React Flow 工作区组件覆盖普通点击、Cmd/Ctrl 追加与取消、框选、程序选择同步和空白取消；相关 14 项测试、类型检查及定向 lint 通过，浏览器验收与原始偶发问题根因仍未确认，见 [节点选中证据](evidence/T07-node-selection-and-image-ratio.md)。

2026-09-27 选择/手形工具：默认选择与空白框选，Space 短按保持手形、长按临时平移、菜单持久切换、V 返回选择；右上角采用参考图的竖向添加/工具入口。节点微移点击阈值同步修正。行为决策见 ADR 0005，组件预览与定向验证见 [节点选中证据](evidence/T07-node-selection-and-image-ratio.md)。完整工作区浏览器验收仍未完成。

- [ ] 本次图稿重构的浏览器同视口视觉与真实指针验收（范围限于文字/图片/视频/Agent 四类卡片；本地浏览器工具访问受阻；不以组件测试替代）。

### T08 Agent 卡片与输入绑定

依赖：T07。

交付：Creator Profile、AgentInstance、AgentBinding、输出区域。

- [x] 在画布中添加并编辑 Agent；不是只有侧栏聊天。
- [x] 选中引用、绑定输入、查看输出范围可见且可修改。
- [x] Agent 实例不在共享内存对象中保存线程或用户上下文；连续记忆由持久会话与每轮冻结快照管理。

2026-09-26 Agent 对话外观重构（下述“一次发送即一次对话”语义已由 ADR 0010 修正；其中计划审批与关键帧部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）：按 [ADR 0005 的呈现修订](adr/0005-canvas-interaction-redesign.md)将 Agent 卡片改为黑色对话界面，顶部对话/历史/设置与底部任务输入分离；每次发送先做内嵌运行前确认，确认后创建独立 AgentRun，历史不自动进入新任务上下文。公开回复、已提交业务动作、计划审批、关键帧选择、阻断和 UNKNOWN 跟随所属 Run 展示；卡片不在画布时保留全局处理入口。四个审批与异常组件同时保留原面板和聊天呈现，定向组件测试两种模式共 32 项通过，覆盖显式批准、计划修订后清空旧确认、取消失败、精确关键帧选择和 UNKNOWN 风险确认；TypeScript 与相关文件 ESLint 通过。这些检查不替代整张聊天卡片的浏览器验收。计划审批与关键帧相关组件随后按 ADR 0013 删除。

整合补验：本轮 7 个相关测试文件共 60 项通过（工作区 16、聊天记录 5、聊天卡片 7、四种审批/恢复面板 32）；覆盖发送幂等与冲突、公开回复白名单、历史分页返回最新任务、IME 快捷键、预检失败禁止沿用旧结果、慢请求保留新草稿及产物选择。前端 ESLint、TypeScript 与 Next 静态生产构建通过，未运行全量测试或真实 Provider。内置浏览器访问 localhost 被 `ERR_BLOCKED_BY_CLIENT` 拦截，视觉与实际指针验收保持未完成，见根目录 `design-qa.md`。

2026-09-26 会话语义修正：按 [ADR 0010](adr/0010-persistent-agent-conversations.md)新增持久 AgentConversation，同会话每次发送追加 Run 并冻结已有公开上下文；新建会话从空历史开始，原会话可以切回续聊。V45 将每条旧 Run 独立迁入会话，保留任务状态（原记录含审批状态，审批机制已随 ADR 0013 移除，2026-09-27）。卡片支持新建/切换/刷新恢复、会话草稿隔离及返回运行会话。前端 4 个定向文件 43 项通过；上下文、产物继承和旧数据升级的 PostgreSQL 检查与命令见 [会话实现证据](evidence/T08-persistent-agent-conversations.md)。未运行全量测试或真实 Provider，浏览器验收仍因 localhost 拦截未完成。

- [ ] 浏览器验证 Agent 卡片中的对话/历史/设置、输入绑定和输出入口；同会话多次发送各创建一条有序 Run 并共享该会话上下文，新建会话才开始空记忆；输入/配置/会话变化后旧确认失效。
- [ ] 浏览器验证持久化公开回复与动作的刷新恢复、历史查看和空态/错误；不展示私有消息，不混入其他会话或虚构回复。
- [ ] 浏览器验证阻断与 UNKNOWN 均可在所属对话处理；调用日志保持只读、不提供 UNKNOWN 恢复，同项目活动 Run 和取消边界不变。

**M1 门禁**：不依赖真实 AI，即可手工完整操作一个项目（创建文字/图片/视频/Agent 卡片、编辑内容、拖拽布局、刷新恢复）并保持版本冲突可见。

（原 M1 门禁为「手工完整操作一个三镜头项目并刷新恢复」，已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27；下列 2026-09-24/25 的验收进展同样针对已移除的三类产物，原证据保留。）

2026-09-24 进展（已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）：工作区新增角色、场景、镜头的手工创建表单；镜头固定场景与可选角色当前精确版本，内容创建和画布放置失败重试有组件回归。TEXT/CHARACTER/SCENE 可在卡片上保存不可变新版本，SHOT 继续使用受控局部修改；所有 Artifact 卡片可按需读取历史并以 CAS 选用旧版本，见 `docs/evidence/T06-manual-storyboard-partial.md`。仍成立的部分：手工创建 TEXT/IMAGE/VIDEO 与底层不可变内容记录；媒体节点的历史选择已由 [ADR 0016](adr/0016-media-nodes-are-single-results.md) 取代，文字仍保留版本历史和选用。

创建请求现增加服务端持久幂等键，前端对未确认的同一表单内容沿用键重试；真实 PostgreSQL 已覆盖并发去重与原响应重放。隔离 Compose + 真实 Chrome 已完成无 AI 的场景、角色、三镜头创建，检查精确版本引用、手工拖线、拖拽布局、场景修订、刷新恢复与无 Run/Task，见 `docs/evidence/T06-browser-m1-gate.md`。（该浏览器路径随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27；仍成立的幂等键与并发去重机制继续沿用。）图片上传断线恢复、大画布性能和真实 Provider 不由此验收。

---

## M2：持久化运行、事件与故障骨架

### T09 Run 与项目互斥

依赖：T05、T08。

交付：Run 状态机、项目活动槽位、输入与策略快照、创建请求幂等。

- [x] 同项目两个并发新 Run 只允许一个活动执行。
- [x] 重放同一幂等请求返回原 Run；同 key 不同参数返回冲突。
- [x] WAITING/BLOCKED 状态持久化，取消/结束能释放槽位。

### T10 Task 调度与租约

依赖：T09。

交付：Task/Dependency、SKIP LOCKED、lease、heartbeat、fencing epoch。

- [x] 两个 Worker 竞争任务不产生两份已确认业务结果。
- [x] 旧租约 Worker 的状态更新被拒绝。
- [x] 网络工作不占数据库事务；等待外部状态不长期占用线程。

### T11 持久事件与提交水位

依赖：T06、T09。

交付：project_event、项目计数行锁、事务事件、快照水位。

- [x] 业务成功而事件写入失败时，事务按设计回滚。
- [x] 并发事务产生事件不会因提交顺序不同而漏读。
- [x] 快照与 snapshotSeq 来自同一一致性快照。

### T12 SSE 与前端重连

依赖：T07、T11。

交付：单项目事件连接、历史补发、心跳、游标失效处理。

补验：浏览器黄金路径曾发现媒体归档后事件流重连与缓存陈旧；现固定项目初始订阅水位、补齐 `asset.ready` 游标处理，并在恢复快照时刷新用量及未包含在快照中的运行历史、任务等缓存。单连接、事件序列和缺口恢复缓存回归已增加；浏览器服务端进程中断重连见 `docs/evidence/T12-browser-process-reconnect.md`。隔离 Chrome 验收现还覆盖断线时清理事件、旧游标 409、快照恢复和后续直播事件，见 `docs/evidence/T12-browser-cursor-expiry.md`；未等待真实保留期或压测长期抖动。

- [x] 断网重连、重复事件、旧版本事件均正确处理。
- [x] 游标过期重新获取快照，不继续使用不完整缓存。
- [x] 慢消费不会无限增长服务器内存。

### T13 取消、UNKNOWN 与恢复分类

依赖：T10–T12。

交付：取消请求、恢复扫描、Provider attempt 记录、UNKNOWN UI。

2026-09-26 展示位置调整：画布不再常驻 UNKNOWN 横幅，历史/待核对调用及账本核对入口集中到 `/settings/calls`；所属 Agent 对话内的当次任务状态和风险确认保留。此调整不修改任务状态与额度，也不自动重提请求。

2026-09-26 产品决策变更：移除“核对原请求”能力与重复成本确认，UNKNOWN 改为卡片或所属对话上的单次显式重试。后端删除 `reconcile` 与 `attempts` 端点、`UnknownTaskReconciler` 及 ComfyUI 候选核对链，`new-attempt` 只接受 `expectedTaskVersion`；`ManualUnknownRetryService` 对当时 ComfyUI 单槽的硬拒绝一并移除。原 UNKNOWN 任务与提交记录仍完整保留，自被替代起不再占用同卡片任务互斥。该单槽及其他共享容量门禁又在 2026-09-28 整体移除。规格 §12.7、AGENTS §6、ADR 0005/0006 与 OpenAPI 已同步；系统层崩溃恢复不自动重提的行为未变。

- [x] SUBMITTING 崩溃不会自动重提。
- [x] 取消后晚到结果不自动替换当前版本或唤醒下游。
- [x] 显示可能的外部成本，不伪装成退款或确定失败。
- [ ] UNKNOWN 列表逐项展示任务 ID、尝试次数、已保存的原请求 ID 或缺失警告、错误码，不展示私有输入；保留人工核对线索。（2026-09-26 产品决策移除核对能力与该列表 UI，本项作废。）
- [ ] 按项目权限读取单任务的提交关联键、attempt 状态与原 Provider 请求 ID，页面按需展开且不暴露工作线程信息。（同上，`/attempts` 端点已移除。）
- [x] 新 ComfyUI 请求使用提交前持久化的关联键作为候选 prompt_id，并拒绝不一致回执；不把该 ID 当幂等保证。
- [ ] 新 ComfyUI UNKNOWN 仅在候选 ID、原 endpoint 指纹、工作流配置及 Provider 返回的 prompt/client ID 全部匹配时恢复原请求轮询；空查询、旧 attempt、配置漂移和取消不自动重提。（2026-09-26 产品决策移除“核对原请求”，本项作废。）
- [x] 重试可为 UNKNOWN 任务新建尝试，原 attempt 保留；新尝试、独立用量预留与待执行依赖重连已通过 PostgreSQL 并发测试；真实 ComfyUI 联调暂缓。2026-09-26 起不再要求显式的重复成本确认，改为界面上的单次重试；被替代的原任务不再占用同卡片任务互斥。
- [x] 实际中断进程并重启，验证提交 checkpoint 与租约恢复不重复提交。
- [x] 进程中断期间的 SSE 客户端重连在浏览器端到端验证（隔离 Compose 中 stop/start server，页面自动恢复并接收新 Run 事件；见 `docs/evidence/T12-browser-process-reconnect.md`）。

**M2 门禁**：在 Mock 下通过重复、崩溃、租约过期、晚到结果和 SSE 故障测试，才允许进入真实媒体提交。

---

## M3：Spring AI 与 Agent 文本闭环

### T14 LLM Gateway 与能力测试

依赖：T01、T09–T13。

交付：Spring AI ChatClient、配置版本、受控回合、工具协议测试。

进展：Spring AI 2.0.1 OpenAI 兼容 ChatModel 候选已接入可显式启用的 configured 模式；真实适配器＋假 HTTP 端点验证双回合工具 ID、供应商 Token 元数据、默认 Mock 无外部 ChatModel，并在 PostgreSQL 上将配置模型五回合接到三镜头、图片/视频双审批、Mock 媒体与导出（其中三镜头、双审批与导出部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27；回合与工具回填机制继续沿用）。Worker 端到端还核对原 assistant `tool_call_id` 与持久化工具结果的回填一致性，见 `docs/evidence/T14-openai-compatible-candidate.md`、`docs/evidence/T15-tool-ledger-partial.md`。未进行真实模型调用或具体模型能力鉴定，以下真实验收仍未完成。

- [x] 默认自动工具执行在此路径关闭，只有一套执行器（假模型及受控业务工具执行器已验证）。
- [ ] 工具调用完整往返成功，保留必要 tool_call_id 与协议元数据。
- [x] 视觉/结构化输出能力只在测试通过后声明（当前显式为 false，真实能力未验证）。

### T15 Tool Registry 与工具账本

依赖：T14、T06–T08。

交付：限定工具、ToolContext、权限链、tool_execution、业务命令键。

进展（工具目录原为 14 个，`link_artifacts`、`propose_export` 及四个三类产物创建工具已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 删除，2026-09-27，现为 8 个）：已增加受 Run 输入/输出范围与 Artifact CAS 保护的 `revise_artifact`、`place_artifacts`、`arrange_items`，以及 `read_project_summary`、`read_artifacts`、`read_selection`、`read_task_status` 四个有上限的只读工具；长绑定内容在首轮上下文只显示预览，可按显式版本 ID 读取全文。启动 Run 时所选卡片由服务端验证并固定到上下文，仅表示操作意图，不扩大绑定权限；任务状态查询只允许本 Run 的任务，不返回 Provider 内部输入或输出。输出卡片只可放置当前 Run 可见版本，固定到 Agent 输出分组，重复工具调用复用原卡片；布局工具仅排列该分组且检查内容版本、布局 CAS 和锁定状态。模型伪造身份/项目/额度字段与不存在的批准工具、不存在的工具名都不会产生媒体副作用，见 `docs/evidence/T15-tool-ledger-partial.md`。（原语义关系工具与 `propose_export` 的提案/审批验收已随 ADR 0013 撤回，2026-09-27；`docs/evidence/T15-export-proposal-partial.md` 保留为历史证据。）真实模型协议兼容性与更广的端到端安全验收仍未完成。

- [x] 工具不能伪造身份、项目、额度和授权（模型响应注入字段/不存在的批准工具、可信上下文越权拒绝；真实 PostgreSQL 测试）。
- [x] 响应持久化先于业务副作用；重放返回原结果（先执行被拒、持久响应后并发同 ID 只写一条账本/一份产物）。
- [x] （已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）同一批准步骤换一个 tool_call_id 仍不会重复建生成任务（获批提案的新 ID 被 Run 状态拒绝，原 ID 重放；计划/审批/Task/预留均单份）。

### T16 文本闭环

依赖：T15。

交付：创作说明与文字产物创建及输出布局。（原交付为「说明、角色、场景、镜头创建」，三类产物已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27。）

进展补充：活动 Run 进入 BLOCKED 时，画布只读取同项目持久 Task 的稳定错误码，明确解释固定模型配置/工具能力不可用、历史密钥缺失或模型结构修复耗尽；媒体 UNKNOWN 在所属对话或媒体卡片上显式重试，不展示模型输入或私有内容。结构或领域校验失败时，同轮工具事务回滚，并在持久化模型回合内最多修复两次；真实 Provider 效果仍未验证。见 `docs/evidence/T16-structured-repair.md`。首轮模型规则与运行前确认面板现明确声明当前不传像素/帧/音频、不能声称已分析视觉内容，也不能把草稿冒充已归档结果；Mock＋PostgreSQL 与前端测试见 `docs/evidence/T16-media-capability-boundary.md`，真实模型遵循情况尚未验证。

- [ ] 一句指令创建有合法结构与引用的文字产物，并摆到画布上。
- [ ] 模型不支持某能力时明确提示，不伪装成已看图或已生成。
- [x] 结构错误最多两次修复；失败无不合法业务数据写入（Fake 模型 + 真实 PostgreSQL 集成测试；真实 Provider 未验证）。

### T17 计划、DAG 与两阶段审批（已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）

依赖：T16、T10。

交付（已撤回）：ExecutionPlan、Approval、输入/计划 hash、输出槽位、额度预留。执行计划与两阶段审批已从产品移除；下列条目仅作为历史验收记录保留，不再适用。

阶段性实现与未覆盖边界见 `docs/evidence/T17-plan-approval-partial.md`；现有 PostgreSQL 回归已覆盖 DAG 环、已绑定但错误类型的资源、跨项目镜头、超额度、20 路同时审批只保留一组任务与一笔预留，以及输入、Provider 配置和固定工作流版本变化时的审批拒绝；纯布局变化不使审批失效。审批面板可直接取消 Run。真实 Provider 和更广的故障/兼容边界仍未验收。

- [x] （已撤回）环、非法类型、跨项目引用、超过限额等被服务端拒绝（真实 PostgreSQL 计划提案/审批测试；拒绝时无新增计划或超额任务）。
- [x] （已撤回）并发审批只创建一组任务；Agent 无批准工具（20 路并发数据库断言及工具注册表断言）。仍成立的部分：Agent 没有任何批准工具。
- [x] （已撤回）输入/模型/工作流变化使批准失效；布局变化不使批准失效（PostgreSQL 输入编辑/布局及模拟服务端配置、工作流版本变化的审批测试；真实 Provider 版本轮换未演练）。

### T18 Run 唤醒与停止边界

依赖：T17。

交付：等待任务、任务完成唤醒、模型回合限制、取消检查点。（原「等待审批」已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27。）

运行前确认补验：卡片现在显示并提交系统提示词版本；若预览后规则变化，创建返回 409、无 Run/首轮 Task，旧确认清空后须重新预览。PostgreSQL/HTTP 与前端测试见 `docs/evidence/T18-initial-turn-partial.md`；真实部署交错尚未浏览器端到端演练。

2026-09-26 公开动作读接口：新增 `GET /api/v1/projects/{projectId}/runs/{runId}/actions`，只返回工具账本中已提交业务动作的六个公开字段，最多 40 项（当时保留的 `WAITING_APPROVAL` 业务状态已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 移除，2026-09-27，该字段现只有 `SUCCEEDED`）。OpenAPI 与生成 TypeScript 已同步；这是新增只读接口，不新增数据库表或迁移。定向 `RunActionsPostgresIT` 在独立 PostgreSQL 17.11 Testcontainers 上通过（1 项、0 失败/错误/跳过）：验证四个真实执行的 Mock 工具动作、顺序与上限、幂等重放不增行、未完成账本过滤、公开字段白名单、私有数据不泄露，以及 401/所有者越权/项目不匹配/未知 Run 的拒绝。该检查未调用真实模型，也未生成真实媒体；聊天整链路仍按 T08 的浏览器项单独验收。

首个持久化回合、新输出画布放置，以及配置模型＋假 HTTP 服务贯穿同一链路的阶段性实现见 `docs/evidence/T18-initial-turn-partial.md` 和 `docs/evidence/T14-openai-compatible-candidate.md`。（原记录中的 Mock 三镜头、图片/视频审批及归档、逐镜头人工选择部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27；`docs/evidence/T-browser-mock-golden-path.md` 的 Mock 三镜头黄金路径同样撤回，原证据保留。）Run 预览与确认增加模型配置来源/版本对照，变更后拒绝未重新确认的 UI 启动，亦见 T18 证据。另有隔离 Compose 项目的浏览器 Mock 黄金路径，见 `docs/evidence/T-browser-mock-golden-path.md`。Agent Run 历史的只读游标列表和任务摘要见 `docs/evidence/T18-run-history-partial.md`。连续工具回合到第 12 次会以可见诊断码停止，不建第 13 次回合；隔离 Chrome 关闭和新服务进程从已持久化回合账本接管的实测见 `docs/evidence/T18-browser-close.md`。真实 Provider 和随机中断模型 HTTP 调用仍未验收。

- [x] 关闭浏览器不影响后台持久化编排（隔离 Compose＋真实 Chrome：关闭进程后 Mock Run 完成 3 个持久回合）。
- [x] 不存在等视频完成的无限 LLM 轮询（持续请求工具的假模型在真实 PostgreSQL 后台任务中恰好执行 12 回合后阻断；媒体完成由 Task 唤醒，不靠模型无限轮询）。
- [x] 重启从账本恢复，不重新执行整条原始用户指令（隔离 Compose 的已响应/已执行工具/未完成任务窗口，重启后原任务收尾、epoch 递增，工具/版本及响应摘要不变；随机崩溃窗口仍待更广演练）。

**M3 门禁**：真实 LLM 完成文字产物闭环；执行动作与画布结果一致。

---

## M4：真实图片与文件可靠归档

### T19 文件上传、存储与读取

依赖：T05、T06、T13。

交付：本地 StorageGateway、上传检查、临时文件、hash、GET/HEAD/Range。

PNG/JPEG/WebP 上传、私有缩略图与受保护读取、用户上传图片的真实 Artifact/画布放置，以及任务键视频文件归档恢复的阶段性实现见 `docs/evidence/T19-local-image-archive-partial.md`、`docs/adr/0001-upload-image-provenance.md` 和 `docs/evidence/T23-mock-video-partial.md`；真实 PostgreSQL 插入故障已验证用户上传清理与任务键原字节恢复。图片原图、缩略图及 MP4 临时写入的 ENOSPC 注入与原 Provider ID 重试见 `docs/evidence/T27-disk-full-injection.md`。格式/像素、路径及越权拒绝已在 PostgreSQL 与 HTTP 测试中覆盖；真实存储耗尽、生产网络与非稀疏大文件吞吐，以及浏览器端到端故障恢复尚未完成。

2026-09-27 图片卡片改显原图：既有卡片与旧视频输入图改为直接读取归档原文件；变更、定向检查与未完成的浏览器实测见 [图片卡片改显归档原图](evidence/T19-image-original-display.md)。ADR 0014 的新媒体图片栏改用已保存缩略图，因此该旧证据只继续证明图片卡片主预览，不证明新图片栏行为。

下载路径现有 400 MiB 稀疏媒体在 `-Xmx128m` 下通过 Spring `ResourceHttpMessageConverter` 完整传输，单次写入有界；Range 跳过不能越过选定字节区间。该内存检查不等于生产网络压测或实际非稀疏磁盘吞吐测试。

- [x] 恶意格式、超大像素、路径穿越、越权读取被拒绝（含静态项目目录符号链接；不覆盖有卷写入权的本地进程并发替换目录）。
- [x] 下载流式执行，大文件不会整段加载到 JVM 内存（400 MiB、128 MiB JVM 堆、Spring 资源转换器完整读取；生产网络与磁盘吞吐仍待测）。
- [x] 磁盘满或数据库失败不产生 READY 坏文件（ENOSPC 写入故障注入覆盖原图/缩略图/MP4，PostgreSQL INSERT 故障覆盖上传清理与任务键恢复；原记录中的导出 MP4 随媒体导出撤回，未演练物理磁盘耗尽）。

### T20 ComfyUI 固定图像工作流

依赖：T17–T19。

交付：受信任 image-v1、模板/节点/模型版本清单、submit/query/归档映射。

进展：精确服务地址与固定 HTTP 路由、`image-v1` 候选模板、参考图到固定节点映射、从任务受理到提交/原 ID 跟踪/归档的假服务＋PostgreSQL 闭环见 `docs/evidence/T20-comfyui-protocol-partial.md`。V23 持久单槽已由 V56 删除，活动 ComfyUI 请求不再阻塞其他卡片提交。真实 ComfyUI/模型、并发资源表现和模板兼容性尚未验证，验收项保持未勾选。

Compose 已可显式传入候选 LLM/ComfyUI 模式、精确端点、固定模板模型名及 Provider 配置版本，默认仍为 Mock；见 `docs/evidence/T20-compose-provider-config-partial.md`。这只解除部署配置阻断，不作为真实兼容性证据。

- [ ] 至少一个真实生图任务完成，并验证参考图实际进入正确输入路径。
- [ ] 返回 prompt_id 后只查询原任务；历史为空不被立即当成失败。
- [ ] 用户/Agent 不能安装节点、上传任意可执行 workflow 或改 endpoint。

### T21 密钥、配置版本与出站安全

依赖：T14、T20。

交付：加密配置、配置版本快照、诊断、端点白名单、脱敏。

进展：管理员加密配置与脱敏页面、活动数据库配置优先读取、Run 策略快照中的来源/版本已实现；数据库中已验证的旧 LLM 版本可供固定该版本的 Run 继续使用，环境变量来源变更仍阻断。部署主密钥增加显式历史密钥环，缺失旧密钥明确报错；合成工具双回合诊断成功才标记 Tool Calling。LLM 出站固定主机/路径、逐次验证 DNS 并拒绝重定向；假 HTTP＋真实 PostgreSQL 已验证。ComfyUI 任务固定精确服务地址指纹，V34 只追加保留历史 endpoint，已受理的固定 v1 请求可在模型名轮换后按原版本/指纹核对和归档；新提交仍严格匹配当前工作流版本，同版本换地址拒绝启动，见 `docs/evidence/T21-comfyui-origin-pinning-partial.md` 与 `docs/evidence/T21-comfyui-historical-lookup-partial.md`。真实 Provider、未知旧媒体模板/凭证版本、跨备份密钥轮换演练及更完整的 SSRF/运维验收仍未完成，见 `docs/evidence/T21-encrypted-llm-settings-partial.md`。

补验：关闭新 ComfyUI 视频生成后，历史轮询器仍可按原请求 ID 下载并归档有效 MP4，创建任务键 VIDEO Asset 与新 ArtifactVersion；真实 PostgreSQL＋本地 FFmpeg＋假 HTTP 已验证，无新提交。真实 Provider 与跨备份演练仍未验证。

设置页补充：`/settings/providers` 已合并现有管理员 LLM 设置与媒体配置状态；媒体端点/模板仍通过服务端环境变量安装，页面不提供在线编辑，也不把配置齐全误称为真实连通或模板兼容，见 `docs/evidence/T28-system-diagnostics-partial.md`。固定 ComfyUI origin、LLM 逐次 DNS 校验及提交/下载重定向拒绝的本地假服务测试见 `docs/evidence/T21-outbound-ssrf.md`；不代表生产网络层隔离已演练。

2026-09-26 画布外界面补充：登录/初始化、项目列表、LLM 与媒体设置、系统诊断/改密统一为 Beautiful UI 黑色主题，复用导航、表单、状态和现有像素加载器；项目搜索明确仅覆盖已加载数据，保留真实游标分页。后台刷新保留草稿，冲突提供显式重载；付费诊断仍需逐次确认。定向测试、构建结果与浏览器限制见 [画布外页面证据](evidence/T21-outside-canvas-beautiful-ui.md)，没有新增后端/合约/迁移或完成真实 Provider 验收。

- [ ] Key 轮换后旧任务仍能按原配置查询，或明确报认证阻断。
- [ ] 无 Key 泄露到日志、SSE、导出或浏览器持久存储。
- [x] 端点地址策略放行私网与代理 fake-ip 段（`EndpointAddressRules`），云元数据与非路由地址仍拒绝，理由见 ADR 0007；重定向/DNS 等 SSRF 测试通过（精确管理员端点、ComfyUI 双路由重定向目标零请求、LLM 模拟恶意 DNS；真实网络基础设施仍未演练）。

### T22 图片版本与晚到结果

依赖：T20–T21。

交付：图片历史、选用版本、旧输入标记、结果 CAS。

进展：假 ComfyUI 完成后首次下载返回无效图片、归档拒绝，只轮询原 prompt 并重新下载的 PostgreSQL 故障测试见 `docs/evidence/T20-comfyui-protocol-partial.md`。V24 持久技术重试账本覆盖带抖动退避、五次重试后 BLOCKED、成功清零；Task 固定资产 ID 的原图落盘后恢复、缩略图重建、READY 资产复用及同进程双 Worker 竞争也有 PostgreSQL＋本地卷测试。独立进程交错写入和真实 Provider 尚未验证，本项保持未勾选。

输入选用补验：视频 Task 现固定所选输入图片版本，并在提交前与晚到结果选用前复核该图片的当前版本。真实 PostgreSQL 下旧输入冲突、旧结果只归档、未提交任务阻断的测试见 `docs/evidence/T22-stale-media-input-selection.md`。前端阻断提示现覆盖三种输入过期来源，运行记录也区分原请求的技术核对/归档与用户重新发起；改选交互及所有并发交错未穷尽，T22 总门禁仍未完成。（原记录中的关键帧选择、计划审批与镜头版本复核部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27。）

- [ ] 用户改输入后旧生成结果只进入历史。
- [ ] 归档失败重试下载，不重新生图。
- [x] 明确区分“技术失败重试”与“用户要求再生成一张”（已受理请求仅查询/下载原任务且有持久重试上限；用户不满意须重新点击“运行”创建新的媒体 Task 或发起新 Run；运行记录明确提示潜在新成本。真实 Provider 尚未联调）。

**M4 门禁**：真实图片链路与 UNKNOWN、归档失败、输入更新竞争均通过。

---

## M5：视频、局部重做与输出

### T23 真实图生视频

依赖：T22。

交付：受信任 image-to-video-v1、参数能力、图像版本固定、进度与归档。

阶段性 Mock 视频、固定输入图片版本、任务键 MP4 归档恢复和前端手动播放证据见 `docs/evidence/T23-mock-video-partial.md`。另有默认关闭的 Wan 2.1 `image-to-video-v1` 候选接入、选定图片上传、帧数/画幅映射、原 prompt 查询和假 ComfyUI＋PostgreSQL 闭环，见 `docs/evidence/T23-comfyui-video-candidate.md`；尚无真实模型兼容测试，以下真实 Provider 验收项仍未完成。

- [ ] 真实参考图片进入视频输入，不只验证文生视频替代路径。
- [ ] 时长/画幅/分辨率映射与模板能力一致。
- [ ] 不调用共享实例全局 interrupt 取消其他任务。

### T24 局部重做（已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）

依赖：T23。

交付（已撤回）：选中镜头修改、依赖影响计算、新计划、新审批、历史保留。局部重做以镜头为唯一目标，镜头已不在产品范围内；产物修改改为直接创建内容新版本。下列条目仅作为历史验收记录保留，原证据保留。

进展（已撤回，2026-09-27，原证据保留）：目标镜头与可选共享场景的原子新版本、旧媒体选用清除、其他镜头引用隔离、Mock 模式下的限定单镜头新 Run、图片/视频双审批闭环，以及旧任务提交前阻断和晚到结果历史归档见 `docs/evidence/T24-local-redo-partial.md`。现增加真实归档视频选择的两侧镜头隔离核对，以及旧任务归档与第二镜头修订的双线程提交交错；隔离 Chrome＋当前代码镜像 Compose 已验证 Agent 卡片局部 Run 的 Mock 双审批、单镜头媒体输出、镜头精确选用、手动视频预览与刷新恢复。视频完成后按固定镜头版本 CAS 追加镜头选用关键帧/视频的新版本，恢复回合引用该新版本继续提出导出提案，真实 PostgreSQL＋FFmpeg 三镜头及局部重做测试已通过。真实 Provider 尚未验证，总门禁未通过。

- [x] （已撤回）只改第二镜头，不改变第一/第三镜头的内容、引用和选用视频（真实 PostgreSQL + 已归档 MP4 版本测试）。
- [x] （已撤回）对共享场景创建新版本并仅重新绑定目标镜头（真实 PostgreSQL 精确引用测试）。
- [x] 未完成的旧任务结果不会回盖新结果（提交前过期阻断、提交后晚到历史归档及双线程修订/归档交错测试；未穷尽全部时序）。仍成立：直接媒体任务沿用同一「结果只入历史」规则。
- [x] （已撤回）输入修改后尚未提交的旧计划任务按主规格 11.4 进入 BLOCKED，提示停止旧 Run、重新绑定及审批新计划；真实 PostgreSQL 测试核对用量释放、Run 阻断和新计划审批。

### T25 顺序导出（已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）

依赖：T23–T24。

交付（已撤回）：镜头顺序、输入快照、受控 FFmpeg、无声 MP4、播放与下载。按镜头顺序拼接无声 MP4 的媒体导出与 `Task.Kind.MEDIA_EXPORT`、导出提案已从产品移除；下列条目仅作为历史验收记录保留。仍成立的部分：受控 FFmpeg 子进程继续用于媒体探测与归档期规范化（读时长/尺寸、生成封面帧、结果去音），其安全约束继续适用。

进展（已撤回，2026-09-27，原证据保留）：项目级持久导出、精确版本/区间快照、受控 FFmpeg 规范化、前端顺序编辑、点击后才加载的预览与私有下载已实现；服务端预览与手动入队现共用同一套精确版本/素材/区间校验，预览本身不创建 Task。Agent 可另行提出需用户审批的导出快照；Mock 三镜头黄金路径已覆盖“提案→批准→无声 MP4”，并在隔离浏览器中完成审批界面和 3 秒 MP4 下载补验，见 `docs/evidence/T15-export-proposal-partial.md`。PostgreSQL + FFmpeg 混合素材及原有 Mock 导出测试见 `docs/evidence/T25-sequential-export-partial.md`。隔离 Compose 项目中的浏览器也已完成手动顺序编辑、提交和“已完成”呈现，并经同项目已登录 HTTP 会话下载实际 15 秒无声 MP4，见 `docs/evidence/T-browser-mock-golden-path.md`。现另以当前代码镜像在 Chrome 中完成 Mock 单段手动导出的私有 HEAD、下载链接和 1280×720 MP4 手动播放。运行中取消、工具超时/非零故障及临时清理已有受控测试；已归档但未记成功的导出现在按 Task 键恢复且不重复编码。真实服务子进程在 FFmpeg 写入期间被强杀、原租约过期后由第二进程接管并只登记一份结果的测试已通过；物理磁盘写满和容器级进程组退出仍未演练，相关验收项保持未勾选。

补验：PostgreSQL＋FFmpeg 集成测试已覆盖横向、纵向、方形输出，逐帧解码核对纵向/方形补边和主体颜色，输出均为 24fps；浏览器只验收单段 Mock 导出的播放器，多分辨率混合输入未在浏览器复验。V35 起新视频归档固定时长，手工导出按源时长设置默认终点，服务端预检在入队前拒绝越界或缺时长的旧素材；见 `docs/evidence/T25-sequential-export-partial.md`。旧视频不自动回填，需重新归档。

- [ ] （已撤回）不同输入分辨率/帧率规范化后可播放，默认不裁剪主体。
- [x] 命令不经过 shell，参数与路径不由模型自由提供（服务端固定 FFmpeg 参数及归档 Asset 路径、`ProcessBuilder` 参数数组；子进程在独立锁定工作目录运行，媒体子进程不继承应用密钥环境；真实 PostgreSQL/FFmpeg 导出与本地进程测试通过）。本条在媒体导出撤回后继续适用于探测与归档规范化。
- [ ] （已撤回）取消、超时、磁盘不足正确清理临时文件，不伪装成功。

### T26 项目导出与用量显示（项目导出清单部分继续有效；媒体导出相关部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）

依赖：T19、T22。

交付：脱敏项目 JSON/素材清单、模型/模板版本、已知/估算/未知用量。

进展：同一一致性快照下的脱敏项目 JSON/素材元数据清单、历史产物版本、前端下载入口，以及 LLM 回合和图片/视频 Task 的持久预留与唯一结算、UNKNOWN 费用显示见 `docs/evidence/T26-project-manifest-partial.md`。图片/视频任务仅在证明尚无提交检查点的取消、预检失败或取消租约恢复后幂等释放预留。项目导出清单已通过真实 PostgreSQL 与受保护 HTTP 读取验证；已提交请求、LLM 的安全失败释放与真实价格仍未完成。（原记录中的媒体导出 Task 与按 Task 输入快照下载 MP4/各段原视频已随 ADR 0013 撤回，2026-09-27；原证据保留。）

- [x] 导出无服务端 Key、会话、原始内部配置或可复用签名链接（项目清单逐字段白名单；真实 PostgreSQL 配置密钥、HTTP 会话标记、媒体参数与签名链接的集成测试；用户主动写入的创作正文仍按内容导出）。
- [x] 未知费用不显示零；同一任务重复通知不重复结算（HTTP null 金额、前端 UNKNOWN 优先显示及媒体任务重复结算的真实 PostgreSQL 测试；外部真实价格和提交后失败费用语义仍未验证）。

**M5 门禁**：真实 LLM + 真实图片 + 真实视频 + 单结果节点派生与来源保护全链路通过。

---

## M6：稳定化、文档与发布

### T27 故障、安全和回归套件

Prompt 版本补充：新 Run 的策略快照固定系统 Prompt v2；历史无版本 Run 无法可靠还原，未发送的首轮请求必须阻断并人工重建。原有的 v1/v2 两份各 30 条固定样本与 `CreatorEvaluationCorpusTest` 于 2026-09-27 随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 的收缩一并删除：它们描述三镜头、局部重做与媒体导出的评测场景，已不可运行。两者均未作为真实模型评估通过证据，历史记录见 `docs/evidence/T27-creator-corpus-partial.md`。另需注意：系统提示词尚未重建——现有 v1/v2 规则仍要求模型提出媒体计划，而该工具已不存在；新的样本集须与重建后的提示词一起制定。

依赖：T26。

进展：原 30 条 Creator 指令样本及其守卫测试已于 2026-09-27 随 ADR 0013 删除（场景已不可运行，见上）；固定样本集待随 Agent 能力重塑重建；真实 PostgreSQL 的恶意绑定文本及实际 PNG 参考图测试证明伪造的批准工具调用不能创建媒体副作用，但 PNG 尚未发送给真实视觉模型，见 `docs/evidence/T27-creator-corpus-partial.md`。§22.2 场景 1–2 的 20 次并发 Run 与生成受理落库证据见 `docs/evidence/T27-concurrent-run-and-approval.md`；场景 3 已补同一次假 ComfyUI 接收请求窗口内强杀真实提交进程、第二进程恢复 UNKNOWN 且不重提的测试，见 `docs/evidence/T27-comfy-accepted-process-kill.md`，响应丢失和独立进程恢复的早期证据另见 `docs/evidence/T27-comfy-response-loss.md` 与 `docs/evidence/T13-process-kill-smoke.md`；场景 5 的重复完成结果测试见 `docs/evidence/T27-duplicate-media-result.md`；场景 11 的图片原图、缩略图与 MP4 归档写满注入见 `docs/evidence/T27-disk-full-injection.md`；场景 12 的归档后已受理请求核对、未提交任务阻断及历史归档测试见 `docs/evidence/T27-archived-project-late-result.md`；场景 13 的 Artifact/Asset/Run/SSE 精确 ID 越权 HTTP 测试见 `docs/evidence/T27-resource-scope.md`；场景 15 已补 Chrome 展示的图片/视频生成数量与持久 Task 数量的跨层核对，见 `docs/evidence/T27-plan-task-count-partial.md`。真实模型逐条执行、配置版本记录及失败报告尚未完成。

- [ ] 主规格第 22 节的全部故障验收有可重复测试证据。
- [ ] 至少 30 条固定 Agent 样本集，保存配置版本与失败报告。
- [ ] 越权、权限提升、Prompt 注入、SSRF、恶意媒体、密钥泄露测试通过。

### T28 性能与可观测性

2026-09-26 调用审计：新增 `/settings/calls` 黑色日志页及管理员本人项目范围的分页筛选 API，V46 持久化新模型/媒体调用的起止时间、耗时和 Trace ID。历史记录明确标注缺失字段，UNKNOWN 核对入口从画布常驻横幅迁入日志；原恢复与成本确认规则不变。验收范围与实际检查见 [调用日志证据](evidence/T28-call-audit.md)，真实 Provider 与全链路分布式追踪未由此验收。

2026-09-26 入口与呈现收口（其中的审批呈现部分已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）：调用日志不再在画布放入口，只从侧栏菜单进入，进入后按当前项目筛选；`BlockedRunNotice` 与 `UnknownTaskAttemptPanel` 去掉已无调用方的 `panel` 呈现分支。定向 14 个前端文件、98 项通过，未运行全量测试，浏览器视觉与长内容滚动仍未验收。

2026-09-26 日志只读回退：核实调用日志的“待核对”实为查询时对写回失败调用行的投影——关联任务一旦不在运行中或提交中（含全部历史记录），该标签就永久显示；而 LLM 调用记录没有关联任务 ID，展开后没有任何可操作入口。日志页因此移除 UNKNOWN 核对与风险新尝试，只保留关联任务的当前状态与“前往项目”链接；调用结果与关联任务的状态标签、以及管理端系统诊断页的同一标签与说明文案，均改为“未知”。恢复动作仍只在所属 Agent 对话与媒体卡片编辑区。同步更新 MVP-SPEC、ADR 0005 与调用日志证据。代价：卡片移出画布期间不再有全局 UNKNOWN 入口，由用户接受。

HTTP 关联补验：服务端生成的请求 ID 同时进入响应头、ProblemDetail 与 ECS 结构化日志；客户端伪造值被忽略，请求线程 MDC 会清理。真实 Tomcat＋PostgreSQL 和单测见 `docs/evidence/T28-request-correlation.md`。后台任务与异步 SSE 后续发送尚未补齐全量关联；此关联测试本身不测性能。

本机隔离性能补验：真实 Chrome＋PostgreSQL 在业务项目上测项目列表/快照各 100 次、Run 受理及卡片内容写入到画布可见各 30 次；首次事件 p95 超过 1 秒，改为事务提交后提示共享事件读取、保留 1 秒持久补发后复测四项本机目标均满足。方法、样本和限制见 `docs/evidence/T28-api-and-event-latency.md`；高并发、长期内存和任务队列目标仍待验。

SSE 生命周期补验：真实 Tomcat＋PostgreSQL 三轮各 20 条 HTTP SSE 连接反复打开/关闭，管理员指标每轮在 45 秒内归零；25 秒时曾残留 6 条，断线并非即时感知。见 `docs/evidence/T28-sse-connection-cycle.md`。长期堆、代理连接与预览内存仍未测量。

依赖：T27。

进展：项目 SSE 活跃/关闭连接、发送失败和服务端事件发送延迟已有无高基数标签的指标；Actuator metrics 仅已认证管理员可读。`http.server.requests` 已配置服务端 p95 观测入口并经真实 Tomcat 请求验证，但尚非性能达标证据，见 `docs/evidence/T28-http-p95-instrumentation.md`。Task READY/UNKNOWN/BLOCKED 总量以三个固定状态标签从 PostgreSQL 定期刷新，V33 有部分索引；同一快照现还给出无 ID 标签的最久到期 READY 等待年龄，数据库失效置 -1，见 `docs/evidence/T28-ready-queue-age.md`。活动 Run 数量以无标签指标定期读取持久状态。资产卷所在文件系统的总量、可用量和占用比现按 30 秒采样、无项目标签，失效时返回 -1 而非旧值，见 `docs/evidence/T28-storage-capacity-metric.md`。readiness 已纳入数据库健康，独立 PostgreSQL 停机测试验证 503 readiness 与 200 liveness。新增管理员只读系统诊断页，展示本地数据库/存储/Provider 配置状态及七天内异常 Task 状态计数，不触发外部探测或付费任务，见 `docs/evidence/T28-system-diagnostics-partial.md`。前端按路由拆包与构建体积见 `docs/evidence/T28-route-bundles-partial.md`。真实 PostgreSQL＋HTTP SSE 回放、连接释放及持久状态测试见 `docs/evidence/T28-sse-metrics-partial.md`。隔离 Chrome 中 300 张真实业务卡片、600 条可见关系和 40 张归档 PNG 缩略图的刷新/拖动测量见 `docs/evidence/T28-canvas-capacity.md`；该测量以缩略图加载为前提，图片卡片改用归档原图后需要重新测量才能继续引用，见 [图片改显归档原图](evidence/T19-image-original-display.md)。其他性能指标仍待独立测量。

- [ ] 使用真实卡片和媒体测 300 节点/600 关系，记录机器与浏览器（原测量基于 40 张归档 PNG 缩略图、Chrome/M2/隔离 4 vCPU Compose 约 59 FPS，尚未包含视频卡片；图片卡片改为加载归档原图后需重新测量）。
- [ ] API、事件延迟、SSE 连接释放、任务队列与内存目标经过测量。
- [ ] 高基数 ID 未成为指标 label；诊断不会发起未确认的付费任务。

### T29 部署、升级与恢复演练

依赖：T27–T28。

进展：已实现显式 `AGENVAS_RECOVERY_MODE=true` 只读核对模式，关闭后台调度并拒绝项目/模型配置写入；在两个隔离 Compose 项目之间实际恢复 PostgreSQL 与资产卷，验证原管理员登录、项目/Artifact 读取、图片哈希和写入 503。另以隔离 PostgreSQL `pg_dump/pg_restore` 验证加密模型配置版本在新密钥实例或轮换后的历史密钥环下可解密、缺失历史密钥时拒绝；新增需显式项目名的备份命令，并以隔离项目验证三份归档、哈希和空库恢复探针，见 `docs/evidence/T29-isolated-restore-partial.md` 与 `docs/operations/backup-restore.md`。Compose 现配置可调的三服务内存/CPU 上限、日志轮转和服务停机等待期，并有 CI 配置断言，见 `docs/evidence/T29-compose-runtime-bounds.md`。应用关闭事件阻止新 Run 和全部 Task 认领入口，保留已有 READY 工作供后续恢复；隔离子 JVM 已实测 SIGTERM 触发关闭门闩且不重写待认领任务，见 `docs/evidence/T29-shutdown-admission.md`。本机隔离空卷 Compose 镜像构建、初始化/登录、容器优雅停机和重启后会话恢复也已实测，见 `docs/evidence/T29-fresh-compose-smoke.md`；这还不是全新机器验收。活跃外部提交交错和生产容量尚未验证。完整部署密钥交接、真实 Provider 请求与生产 RPO/RTO 尚未演练，门禁未完成。

- [ ] 全新机器按 README 成功启动与登录。
- [ ] 备份数据库、媒体、模板和密钥并完成实际恢复。
- [ ] 旧备份恢复以恢复模式启动，不盲目重提可能已经执行的外部请求。
- [ ] 停机、迁移、回滚兼容检查与恢复耗时有记录。

### T30 开源发布

依赖：T29。

进展：新增 `SECURITY.md`，明确开发版尚无受支持发布，并仅在仓库启用 GitHub 私密漏洞报告时使用该入口；私密渠道当前无法核实，仍是发布阻断。`docs/release-notes/0.1.0-mvp-draft.md` 汇总 Mock 支持范围、候选 Provider 限制、升级恢复边界与待验门禁，不作为正式发行。CI 已配置镜像级 CycloneDX SBOM/许可证清单工件生成，但项目许可证决定、NOTICE、模型/FFmpeg 许可审查和具体发行工件尚未完成。

- [ ] README/README.en、CONTRIBUTING、SECURITY、LICENSE、NOTICE、SBOM 完整。
- [ ] 媒体模板、模型权重、Custom Node 与 FFmpeg 构建许可分别核验。
- [ ] 核心 Agent 无闭源服务强依赖，Mock 可脱离作者账户启动。
- [ ] Release Notes 明确真实支持范围、已知限制、升级说明与测试证据。

**M6 门禁**：不能用“演示视频成功一次”替代稳定版验收。

---

## 后续媒体能力交付（2026-09-25 决策，进行中）

依据：[媒体能力基础规格](superpowers/specs/2026-09-25-media-capability-foundation-design.md)、[GPT Image 2/Seedance 固定渠道规格](superpowers/specs/2026-09-25-fixed-media-provider-adapters-design.md)、[ADR 0002](adr/0002-fixed-media-adapters-before-workflow-platforms.md)。RunningHub 类动态脚本接入不在当前清单中。

实施顺序：[整数秒迁移计划](superpowers/plans/2026-09-25-integer-video-seconds.md) → [媒体能力基础计划](superpowers/plans/2026-09-25-media-capability-foundation.md) → [GPT Image 2/Seedance 适配器计划](superpowers/plans/2026-09-25-gpt-image-seedance-adapters.md)。以下交付项按实际检查结果更新。

- [x] 管理员界面保存多连接、多能力、默认值及服务端加密密钥；V40 一次性导入 Mock/ComfyUI 配置与可精确匹配的历史任务来源，无法匹配的旧请求保持阻断或未知。PostgreSQL 迁移及目录集成测试通过；未进行真实 ComfyUI 调用。
- [x] 按[ADR 0003](adr/0003-integer-business-video-seconds.md)统一视频创作时长、Task 和用量的整数秒字段（原记录含新镜头、计划与导出区间，已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 撤回，2026-09-27）；保留素材探测毫秒精度，验证旧整数/小数与已受理任务迁移。V36 升级、v1 冻结任务与用量、Mock/假 ComfyUI 及前端表单有回归测试；`backend ./mvnw verify`（57 个集成测试）、前端类型检查/lint/73 个测试/构建通过，真实 Provider 未运行。
- [x] 图片/视频任务在受理时展示并固定所选能力版本；统一内核执行、恢复和归档 Mock/ComfyUI。`backend ./mvnw verify`（63 项、0 失败）、前端类型检查/lint/77 项测试/构建通过；ComfyUI 使用本地假服务，真实 Provider 未运行。
- [x] GPT Image 2 固定适配器完成生成与有序多参考编辑的本地假服务协议、PostgreSQL 和前端任务链路验收；最多 4 张冻结图片按顺序使用重复的 `image[]` multipart 字段提交，跨项目参考图在网络前拒绝，响应丢失保持 UNKNOWN，不自动重提。真实渠道已跑通文字生图并对齐了同步生成所需的读超时与租约，见 `docs/evidence/T21-openai-image-sync-result.md`；多参考编辑（`images/edits`）仍只有本地假服务验证。

2026-09-27 超时复发与原因码：真实渠道再次实测到 216 秒返回、而当时上限为 180 秒，已生成并计费的结果再次被丢弃。两个同步图片客户端的读/整次调用上限提到 5 分钟；本地一旦得到确定结论（读超时、连接中断、结果下载失败、响应不符合固定协议）就**当场**按具体原因码写入 UNKNOWN，不再停在 `SUBMITTING` 空等 30 分钟租约；每个失败点都有稳定原因码（`ProviderFailureCodes`），适配器原样透传，前端统一展示可读中文原因。租约过期扫描保留为进程被杀的兜底，写笼统的 `PROVIDER_SUBMISSION_UNKNOWN`。新增不变量：`agenvas.task.lease-duration` 必须同时大于调用耗时与客户端超时。已运行：`OpenAiImage2ClientTest`、`GoogleNanoBananaClientTest`、`OutboundTimeoutsTest`、`TaskSubmissionUnknownPostgresIT`、`OpenAiImage2PostgresIT`、`GoogleNanoBananaPostgresIT`、`ArkSeedancePostgresIT`、`TaskRecoveryPostgresIT`、`ManualUnknownRetryPostgresIT`、后端单元套件 125 项；前端 `typecheck`/`lint` 与 261 项测试通过。**未做真实 Provider 调用**，「216 秒结果能正常归档」由上限提升与上述验证推断，未实测。详见 `docs/evidence/T21-openai-image-sync-result.md`。
- [x] OpenAI 图片连接可选自定义 HTTPS API Base URL；历史空地址仍走官方 `/v1`，已受理任务固定连接版本，拒绝不安全地址、私网 DNS 和重定向。自定义公开网关的真实生成未运行。
- [x] 火山方舟 Seedance 固定首帧图生视频适配器完成 4–15 整数秒、异步原任务 ID 轮询、过期 URL 重查或无法刷新时阻断、带音轨结果去音归档、恶意地址阻断、下载失败后重试与创建响应丢失 UNKNOWN 的本地假服务及 PostgreSQL 验收；无真实方舟调用。
- [x] 分别记录真实调用状态：GPT Image 2 **已运行**，经第三方中转站（grsai，模型 `gpt-image-2.5`）完成一次真实文字生图并归档 Asset，生成耗时约 35 秒；此为兼容中转站而非 OpenAI 官方端点。Seedance **未运行**。管理员界面保持“已配置、未实测”，不把本地假服务或 Mock 结果标记为真实生成成功。

后续 Google Nano Banana 2 固定图片适配器见 [ADR 0004](adr/0004-google-nano-banana-2-fixed-adapter.md)：`GOOGLE` 连接、默认 `gemini-3.1-flash-image` 模型、文字生图和最多 14 张同项目有序参考图、项目画幅、管理员配置及 V41 平台约束已接入。假 Google HTTP 服务与 PostgreSQL 覆盖两张冻结参考图顺序提交、成功归档、断线 UNKNOWN 不重提、越权参考图预检拒绝；真实 Google API 调用 **未运行**，真实模型兼容、生成效果与费用尚未验收。

- [ ] 使用真实 Google Key 完成 Nano Banana 2 生图和多参考生成，并验证顺序语义、输出、计费及 UNKNOWN 人工处置。

本轮全量检查：`backend ./mvnw verify` 为 67 项、0 失败；前端类型检查、lint、78 项测试与构建通过。全量后新增的固定云 DNS 共用校验和 Seedance 过期地址断言另经定向测试验证；本地假服务不等于真实 Provider 验收。

## 画布重构与直接媒体运行（2026-09-25 产品决策，实施中）

依据：[ADR 0005](adr/0005-canvas-interaction-redesign.md)、[ADR 0006](adr/0006-direct-media-task-boundary.md)、[ADR 0013](adr/0013-contract-to-direct-generation.md)、[ADR 0014](adr/0014-canvas-item-media-branches-and-versioned-image-inputs.md)、[领域词汇](../CONTEXT.md)和 `docs/MVP-SPEC.md` 第 6.9–6.10 节。以下均为新目标，不能用已撤回的 Agent 三镜头生成验收代替。

实施进展：深色画布、四类菜单（文字、图片、视频、Agent）、底部编辑、资源抽屉、媒体草稿、直接 Task、数据库竞争认领、单实例有界并行派发和只读队列状态已进入代码。2026-09-28 删除项目级、能力级和 ComfyUI 单槽三层产品并发门禁及设置项；READY 只显示等待执行器，同卡片互斥、租约、fencing 和有界执行器保留。PostgreSQL 集成测试覆盖空版本媒体卡片、草稿 CAS、独立任务、Mock 结果版本、精确视频输入和跨项目/能力继续认领；ComfyUI 假服务覆盖活动请求重叠提交。当前能力目录没有已知价格字段，因此直接运行展示费用未知、账本记未知金额；金额预留需在价格配置落地后补验。浏览器端到端、真实 Provider、跨 Worker 故障注入和真实资源压力仍待验收，因此下列综合验收项暂不勾选。

2026-09-27 Issue #7 基础切片：媒体草稿、展示版本和直连任务已归属 CanvasItem，Artifact 资源默认版本独立；同一 Artifact 多卡片可独立选择、上传、保存草稿并运行，成功结果只条件选用发起卡片。资源库放置按默认版本初始化且使用空草稿。V52 清空项目创作数据并保留管理员、加密、Provider、能力与 LLM 设置；V53 在首次启动清理旧项目资产目录并保存完成标记；V54 允许删除卡片后保留任务历史。OpenAPI/Java/生成 TypeScript/jOOQ 已同步。全量后端 125 个单元测试与 74 个 PostgreSQL 集成测试、前端 36 个文件 232 项测试及 lint/类型检查/构建通过；边界和未验证项见 [Issue #7 证据](evidence/issue-7-canvas-item-media-context.md)。下列包含后续能力的综合条目仍不勾选。

2026-09-28 Issue #6 核心切片：已加入 CanvasItem 独占的有序精确版本图片输入、多来源引用计数与持久化连线、视频输入模式、结构化图片标签、卡片分支复制、冻结任务/版本来源、历史输入完整恢复及导出 schema v2；OpenAPI、V55 与生成源码已同步。后续按图稿补上设备多文件上传的完整验收、模型切换影响预览及 Agent 图片发送限制等能力，因此下列综合条目继续不勾选。详见 [Issue #6 证据](evidence/issue-6-versioned-media-inputs.md) 与根目录 `design-qa.md`。

2026-09-28 多参考 Provider 切片：OpenAI GPT Image 2 与 Google Nano Banana 2 已从冻结 Task 读取全部有序图片并分别按官方 `image[]` multipart 与 Gemini `inlineData` parts 提交，能力上限为 4／14；数量、重复版本、冻结顺序、同项目 Artifact 身份、单图与总字节均在网络前校验。ComfyUI 保持单图。本轮复用现有 `maxReferenceImages` 合约与 V55 数据模型，无 OpenAPI、Flyway 或生成 TypeScript 变更；真实多参考 Provider 调用仍未运行。

2026-09-29 视频输入模式与比例增量：底部编辑器用与图片节点一致的圆角深色弹层展示文生视频、全能参考和首尾帧；空图片栏自动保存文生视频并置灰需图模式，加入第一张图片时优先全能参考、移除最后一张时回到文生视频。视频草稿新增 `AUTO / 16:9 / 9:16 / 1:1` 比例，Task 冻结该值；Mock、ComfyUI 与 Seedance 固定适配器读取冻结比例，`AUTO` 仍跟随项目画幅。Mock 能力声明三种模式，现有 ComfyUI/Seedance 首帧适配器继续只声明首尾帧，未伪造真实能力。

2026-09-28 资源完整性选择切片：资源库面板已支持名称/版本搜索、历史版本、能力剩余容量内的有序多选和明确确认；确认前不修改图片栏，确认使用单次草稿 CAS。任一候选失效时整批不添加并刷新资源和历史版本。真实 PostgreSQL 测试确认混合有效/失效版本不会推进草稿版本或留下部分输入；该综合条目仍因设备上传端到端验收等剩余项不勾选。

2026-09-29 图片生成原子参数与批次切片：图片草稿增加比例、分辨率、画质、透明背景、1/2/4 生成数量和当时可选的“生成时新建节点”；能力合约声明实际支持范围，OpenAI、Google、ComfyUI 与 Mock 适配器按声明映射。节点输出开关与原位结果语义已在 2026-09-30 被 ADR 0016 取代，现为每个结果强制创建独立节点。其余专项测试与浏览器视觉核对见 [证据](evidence/T07-image-generation-parameters.md) 和根目录 `design-qa.md`；真实 Provider 调用仍未运行。

2026-09-29 图片后处理切片：选中图片工具栏已接智能编辑、深度提取以及扩展菜单中的 AI 重打光、AI 扩图、AI 三视图、AI 图层分离、AI 表情调整、AI 画笔标注、AI 移除背景、AI 局部擦除、AI 视角调整，以及本地裁剪、旋转、镜像和 2×/4× 放大。V57 安装不可修改且不参与普通生成模型选择的本地图片处理能力；V58 最初增加 `IMAGE_DERIVATION`，V59 已将其迁移为图片/视频通用且可删除的 `MEDIA_DERIVATION`。每次后处理受理时从来源图片创建独立结果节点和派生线，Task 与完成结果只绑定结果节点，不覆盖或占用来源节点；派生线不进入媒体草稿，可单独删除。本地与云端处理能力边界保持不变；未调用真实 OpenAI/Google。见 [ADR 0015](adr/0015-image-post-processing-local-first.md)、[ADR 0016](adr/0016-media-nodes-are-single-results.md) 与 [证据](evidence/T-image-post-processing.md)。

2026-09-30 媒体单结果节点切片：图片与视频生成、图片后处理和媒体上传都预建独立结果 CanvasItem，来源节点固定原结果且可继续派生；移除媒体节点版本选择接口和 UI、“生成时新建节点”参数及原位批次路径。V59 将派生关系统一为可删除 `MEDIA_DERIVATION`；删除只移除来源提示，之后图片节点手动重连按 `MEDIA_INPUT` 参考线处理。OpenAPI、生成 TypeScript、领域词汇、MVP 规格和 ADR 0016 已同步。PostgreSQL 定向集成测试覆盖图片/视频普通运行、上传幂等、图片后处理和派生线删除；真实 Provider 未调用。

- [ ] 工作区改为深色点状全画布；取消左侧创建栏；空白双击与悬浮“+”打开同一四类菜单（文字、图片、视频、Agent），键盘可达，边缘避让，新卡片精确落在交互位置；右下角缩放控件可用。
- [ ] 单选 Artifact 显示按类型切换的底部编辑区，空选隐藏，多选显示批量操作；文字、图片、视频字段与现有业务 Schema 对齐。Agent 使用卡片内对话/历史/设置，配置指令与本次运行指令分开；每次确认发送为独立 Run。
- [ ] 迁移原左栏能力：顶部可搜索资源抽屉、导入与项目导出清单，右侧版本/引用属性，多选对齐；Agent 的失败与 UNKNOWN 嵌入所属对话。无入口丢失，保存失败保留草稿，输入时快捷键不误删卡片。
- [ ] IMAGE / VIDEO 卡片创建时即有稳定 Artifact，首次生成前媒体版本可为空；媒体草稿改由 CanvasItem 独占并跨刷新 CAS 保存，同一 Artifact 的多张卡片可分别钉住展示版本、提示词、参数和图片输入。资源库放置使用资源默认版本与空草稿；复制卡片先保存再复制工作上下文，不复制任务或连线。
- [ ] Artifact 保留显式资源默认版本，ArtifactVersion 保留 `baseVersionId` 审计分支；媒体结果只固定到受理时新建的目标 CanvasItem，不修改来源节点、同 Artifact 的其他节点或资源默认版本。图片和视频节点不再提供版本列表、历史切换或冻结输入恢复。
- [ ] 图片/视频编辑器增加单行横向图片栏及固定在最前的“+”；上传和资源库均支持多选、能力剩余上限、历史版本、缩略图和失败状态。资源库批次原子校验，上传超选在创建资源前阻止，逐文件失败可重试且不打乱成功图片顺序。
- [ ] 实现有序 `canvas_item_input` 与多来源：同一目标按精确图片版本去重，支持拖拽及键盘重排、稳定颜色、手动来源和多条 CanvasItem 连线来源。断线只删对应来源；最后来源、缩略图移除、卡片移除、模式/模型转换的连带清理均按确认规则和 CAS 原子提交。
- [ ] 提示词编辑器支持只引用当前图片栏的结构化 `@` 标签、小缩略图与颜色；图片/全能参考按顺序显示 `Image N`，首尾帧显示 `Start Frame` / `End Frame`。删除单个文字标签不移除图片，删除图片清除全部标签；Task 按稳定文本与同序图片提交。
- [ ] CanvasItem 连线固定来源当前图片 ArtifactVersion，拒绝直接/间接 CanvasItem 环及拖拽过程版本漂移；手动选图不自动画线。历史版本连线使用虚线并支持显式同步/降级，命中已有目标版本时合并来源而不新增重复条目。
- [ ] 媒体能力合约支持图片有序多图，以及视频纯文本、首尾帧、全能参考三种模式的独立声明、默认模式和上限；首尾帧另行声明尾帧支持。能力更新不兼容时保留草稿并禁用运行；模型/模式转换先显示参数重置和图片清理影响。适配器只有协议与测试升级后才可声明多图，未升级适配器上限保持 1。
- [ ] 图片 CanvasItem 到 Agent CanvasItem 的连线建立独立 Agent 图片绑定：固定精确版本、同版本多来源标题别名、字节只发送一次，受每模型图片数量/单图/总大小限制。Agent 设置只读展示与解绑，新增只能拖线；一个 AgentInstance 最多一张卡片，移除卡片不删除会话且不影响已冻结 AgentRun。
- [ ] 直接媒体“运行”路径以来源 CanvasItem 固定草稿与父记录，并为每个输出预建目标 CanvasItem；Task 固定目标节点、能力版本、提示词、参数、输入模式、有序图片版本/角色和结构化标签，ArtifactVersion 保存同一份冻结来源。完成结果只固定到目标节点，不覆盖来源。
- [ ] 同一卡片跨来源任务互斥，排队/运行/UNKNOWN 时重复点击返回原任务；不同卡片与一个 AgentRun 可并行。点击时固定草稿、输入与能力版本；排队期间再编辑不改写任务。
- [x] （2026-09-28 变更）删除项目级、能力级和 ComfyUI 单槽产品并发门禁；READY 只因有界 Worker 暂不可用而持久排队并展示前方数量与“等待执行器”。保留同卡片任务互斥、数据库租约、fencing 与进程内资源上限，并用真实 PostgreSQL 与假 ComfyUI 验证跨卡片继续认领和活动请求重叠提交。见 [验证记录](evidence/media-concurrency-removal.md)。
- [ ] 受理时预留任务次数额度，已知价格预留估算金额；排队取消且未提交 Provider 时释放未消耗预留，UNKNOWN 不自动释放。任务取消、晚到结果、归档失败、旧 Worker、重复请求和跨项目引用有回归测试。
- [ ] 同一图片/视频 Artifact 追加不可变审计记录，但媒体节点固定单一结果且旧结果不可在节点内切换；移除来源节点不取消已绑定结果节点的任务。同步 OpenAPI、Flyway、生成 TS、浏览器验收；真实 Provider 单独实测后再标记。
- [ ] 新 Flyway 迁移按已确认的本地开发策略清空全部项目创作数据，保留管理员、加密密钥、Provider 与模型设置；重新生成 jOOQ。项目导出清单覆盖 CanvasItem 展示版本/草稿/输入/来源/颜色/连线、Artifact 分支图和资源默认版本。同步 OpenAPI、Java、生成 TS、契约测试与破坏性升级说明。


## 直连生成收缩后的后续变更（2026-09-27，未排期）

以下条目在收缩时明确留待后续**独立**变更，不属于本次收缩的完成范围，也未排期。

- [ ] Agent 工具能力重塑：ADR 0014 已把图片 CanvasItem 连线形成的视觉输入绑定纳入当前目标，但 Agent 仍不触发媒体生成；其余文本与画布编排工具是否扩展继续作为独立后续变更，需另补 ADR，不与本次图片输入实现合并。

## A. 跨模块验收矩阵

| 用例 | 必须结果 | 核心任务 |
|---|---|---|
| 重复创建 Run | 返回同一 Run | T09 |
| 同 key 不同参数 | 409，未产生额外副作用 | T09、T17 |
| 并发生成受理 | 一个任务、一笔预留 | T09、T13 |
| Provider 收到请求后断网 | 转 UNKNOWN，用户显式重试 | T13、T20、T23 |
| 租约失效后旧 Worker 返回 | 不覆盖新状态 | T10 |
| 生成完成但归档失败 | 不重复生成 | T19–T23 |
| 取消后任务晚到 | 历史保留，不触发下游 | T13、T22–T25 |
| 用户在生成中修改输入 | 旧结果只入历史，不覆盖当前选用 | T22 |
| 用户在生成中切换版本 | 旧结果不自动覆盖选择 | T22–T24 |
| SSE 快照竞争 | 无遗漏与旧覆盖 | T11–T12 |
| SSE 游标过期 | 重新拉快照 | T12 |
| 密钥轮换后旧任务查询 | 原配置核对或明确阻断 | T21 |
| 恶意素材要求越权操作 | 无权限提升 | T15、T27 |
| 内网/元数据 SSRF | 拒绝非白名单请求 | T21、T27 |
| 恢复旧数据库 | 不盲目重发外部任务 | T29 |

## B. 预期验证入口

以下是项目实现后应提供的检查入口，不是本次文档已执行过的命令。

```sh
# frontend/：脚本由 T01/T04 建立
pnpm install --frozen-lockfile
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm test:e2e

# backend/
./mvnw verify

# repo root：由 T02/T29 建立
# docker compose -f deploy/compose.yaml up -d
# 随后执行有文档说明的安装、健康检查和黄金路径 smoke test。
```

## C. 每个任务的证据记录模板

```text
任务编号：
实现 commit：
变更行为：
合约/迁移影响：
执行环境与版本：
实际运行的测试命令：
测试结果与报告路径：
真实 Provider 测试情况：
安全/成本影响：
未验证项与已知限制：
```

任务证据必须能区分：单元测试通过、Mock E2E 通过、真实供应商通过、故障注入通过和性能通过。它们不是同一种结论。
