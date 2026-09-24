# T15 Tool Registry 与工具账本：阶段证据

当前开放十四个模型可见工具：`read_project_summary`、`read_artifacts`、`read_selection`、`read_task_status`、`create_text`、`create_character`、`create_scene`、`create_shots`、`revise_artifact`、`place_artifacts`、`arrange_items`、`link_artifacts`、`propose_generation_plan`、`propose_export`；没有审批或直接媒体提交工具。T15 的真实模型兼容性与完整安全验收仍未完成。

V14 `tool_execution` 使用唯一 `(run_id, step_index, tool_call_id)`、原始参数哈希和响应外键。执行器先锁定可信 Run，确认完整模型响应已落库且 tool_call_id 原样存在，再检查 Run 状态和每 Run 40 次上限。模型参数严格限制 `title/text/format`；ownerId、projectId、runId 不在 Tool JSON Schema 内，由服务端 `TrustedToolContext` 注入。Artifact 应用服务按原有内容 Schema 创建 `AGENT` 来源的不可变文本版本；工具账本、Artifact 与项目事件在同一事务提交。重放读取已保存结果，不能再次创建产物。ToolCallback 自身禁止直接执行，避免绕过业务账本。

`ToolExecutionPostgresIT` 使用真实 PostgreSQL、假 ChatGateway 验证响应未保存时拒绝、同一调用并发仅一份产物与账本、错误身份被阻止、模型伪造 ownerId 被拒绝且事务不留残留。全量 `./mvnw verify` 为 14 个单元测试和 12 个 PostgreSQL 集成测试；Compose 已执行 V14 且健康。

角色、场景和镜头的引用另由服务端检查是否属于 Run 起始时显式绑定的版本或本轮 Agent/Task 产出；输出卡片在同一事务保存到 Agent 的输出分组。批量镜头中的任一引用无效会回滚整批产物、布局和工具账本，详见 `T16-storyboard-tools-partial.md`。

后续切片已补齐计划提案、业务层 `planId + stepKey + attemptNo` 去重和 Run Worker 的持久认领；这些变更不引入模型审批工具。真实模型工具兼容性、结构修复和完整安全样本仍待验证，因此 T15 与 M3 门禁保持未通过。

2026-09-24 协议闭环补充：`AgentTurnWorkerPostgresIT` 使用假模型与真实 PostgreSQL 进一步断言：下一回合的 assistant 调用 ID、工具回复 ID/名称和已提交的 `createdIds` 结果一一对应，而不只是看见一条 `TOOL` 类型消息；定向测试通过。结构修复上限的本地实现与验证见 `T16-structured-repair.md`。真实模型对该协议的兼容性及完整安全样本验收仍未验证，T15/M3 不据此整体勾选。

2026-09-24 内容修订补充：`revise_artifact` 只接受目标 Artifact ID、从 0 开始的预期 CAS 版本、可选标题和完整内容对象。应用服务在版本比较前检查目标当前内容是 Run 显式绑定版本或该 Run 产物，拒绝媒体修订；镜头若已含人工选用的图片/视频版本，只能走局部重做流程，不能由通用模型工具覆盖或注入选择；新内容中每个引用也必须在 Run 可见范围内。成功创建 `AGENT` 来源不可变版本，工具结果使用 `updatedIds`/`affectedVersions`，并沿同一账本幂等。`AgentRevisionPostgresIT` 使用假模型与真实 PostgreSQL 验证创建后修订、旧版本冲突、未绑定项目产物即使猜错版本也仅得到作用域拒绝、镜头媒体选择与跨范围场景引用被拒，且无额外工具账本。未新增 HTTP 合约或 Flyway 迁移。

CAS 可用性补充：新建与修订工具回执增加 `artifactVersions`（Artifact 的整数 CAS 版本），与 `affectedVersions`（不可变内容版本 UUID）明确分开。Run 创建时把显式绑定产物的种类、标题，以及仅在所绑内容仍为当前版本时可用的 `expectedVersion` 固定到输入快照，首轮模型上下文显示该值。绑定后用户修改产物时，旧 Run 即使猜到新的 CAS 值也不能修订该绑定；本轮新产物则从工具回执取下一次 CAS 值。`AgentRunPostgresIT` 与 `AgentRevisionPostgresIT` 覆盖该区别和用户编辑竞争。

幂等回执补充：首次工具执行完成后，执行器重新读取 PostgreSQL JSONB 中的持久结果再返回，与以后 `tool_call_id` 重放的回执采用同一表示；避免新增整数版本字段在内存与 JSONB 反序列化后节点类型不同。`ToolExecutionPostgresIT` 和 `StoryboardToolsPostgresIT` 对原结果与重放结果的完整树相等断言覆盖该行为。

本轮验证：新增 `AgentRevisionPostgresIT`、既有 `AgentRunPostgresIT`、`AgentTurnRepairPostgresIT`、`MockStoryboardPostgresIT`、`ToolExecutionPostgresIT` 与 `StoryboardToolsPostgresIT` 定向执行通过；最终无并行 Maven 进程的 `./mvnw -q verify` 通过。此前一次全量执行在 `TaskStaleShotPostgresIT` 的 Flyway 建连阶段遇到 PostgreSQL 容器读取超时，测试方法未开始；该测试独立重跑与最终全量重跑均通过。`git diff --check` 通过。真实 LLM 仍未调用。

2026-09-24 按需读取补充：`read_project_summary` 仅接受空对象，返回项目公开概要与 Run 固定上限；`read_artifacts` 必须给出 1–12 个互异版本 ID，只可读显式绑定或同 Run 产物。单项超过 24 KiB、累计超过 160 KiB 的内容只返回 Unicode 安全的预览和截断标记。首轮上下文对超过 1,200 个 Unicode 字符的绑定仅给预览，模型可凭版本 ID 读取全文。两种读取走与写工具相同的响应先持久化、可信 Run 作用域、40 次预算及 JSONB 结果重放路径。`ReadToolsPostgresIT` 用假模型和真实 PostgreSQL 验证长内容按需读取、项目概要、重放不增加账本、猜测未绑定版本触发 Run 阻断且私有内容不泄漏。未新增 HTTP 合约或 Flyway 迁移；真实 LLM、其余 P0 只读工具及完整安全样本未验证。

按需读取切片的 `ReadToolsPostgresIT` 定向执行通过，随后单独运行 `./mvnw -q verify` 退出码为 0；`git diff --check` 退出码为 0。上述验证未进行真实模型或媒体 Provider 调用。

2026-09-24 任务状态补充：`read_task_status` 接受 1–12 个互异 Task ID，先校验整组参数，再按项目 owner 边界查找，并要求每个 Task 的 runId 等于可信 Run ID；仅返回 ID、种类、状态、取消标记、尝试次数、稳定错误码与时间，不返回输入快照、Provider 请求 ID、内部输出或密钥。该工具沿用持久化工具账本且描述明确不应反复轮询。扩展的 `ReadToolsPostgresIT` 使用假模型与真实 PostgreSQL 验证运行中任务状态随工具回复回填；`ReadToolServiceTest` 验证同项目其他 Run 的任务拒绝与重复/超限 ID 在查找前拒绝。首次定向测试暴露重复 ID 校验发生在首项查找之后，已修正为完整预校验；修正后的定向测试通过。未新增 HTTP 合约或 Flyway 迁移。

任务状态切片随后单独执行 `./mvnw -q verify`，退出码为 0；`git diff --check` 退出码为 0。未进行真实模型或媒体 Provider 调用。

2026-09-24 当前选择补充：启动 Run 的可选 `selectedItemIds`（最多 20 个唯一卡片）经同项目服务端画布查询核对后，以卡片、主体和当时内容版本 ID 固定到 Run 上下文；幂等请求哈希也包括该选择。首轮上下文和 `read_selection` 只返回这些引用，不返回整张画布，也不把选择升级为内容修改/读取授权。前端运行前确认显示所选卡片 ID，失败重试保留原选择与幂等键。扩展的 `ReadToolsPostgresIT` 用假模型与真实 PostgreSQL 验证：无效卡片被拒、同 key 改选择冲突、重复选择被拒、模型能读取固定选择，但选中而未绑定的私有产物仍不能通过 `read_artifacts` 读取。前端 `ProjectWorkspacePage.test.tsx` 覆盖启动请求携带选择。更新了 `contracts/openapi.yaml` 的 Run 创建请求和上下文快照 Schema，并重新生成 TS 类型；未新增 Flyway 迁移。旧 Run 无 selection 时只读工具返回空列表，合约中该快照字段保持可选。真实模型仍未调用。

本切片定向运行 `ReadToolsPostgresIT`、`AgentRunPostgresIT` 通过；后端 `./mvnw -q verify` 退出码 0。前端 `vitest run` 为 12 个文件/32 个测试通过，`tsc --noEmit` 与 `vite build` 退出码均为 0，`git diff --check` 退出码 0。Vite 提示主包超过 500 kB，性能优化仍待 T28；由于本机 pnpm shim 无法切到声明版本，生成类型和检查使用 `node_modules/.bin` 中已安装的对应 CLI。

2026-09-24 输出卡片补充：`place_artifacts` 只接受 1–6 个互异不可变版本 ID 和固定 `AGENT_OUTPUT` 枚举。工具先验证整批 ID 属于显式绑定或同 Run 产物、版本仍为当前选用且未归档，再调用画布应用服务以服务端 Agent 输出分组自动放置；相同 Artifact 已在该分组有卡片时复用其 Item ID，即使模型换一个 `tool_call_id` 也不重复放置。创建的卡片与账本处于同一事务，响应只给卡片 ID 映射和是否新建。扩展的 `ReadToolsPostgresIT` 用假模型与真实 PostgreSQL 验证两次不同调用只留一张输出卡、结果回填、越界版本/任意分组/用户改版后的旧版本被拒。未修改 HTTP 合约或 Flyway 迁移；真实模型调用未验证。

验证记录：`ReadToolsPostgresIT` 定向通过，`./mvnw -q test` 退出码 0；全部 38 个 PostgreSQL IT 在分批或独立执行后的 Surefire 报告中为 0 失败、0 错误，`git diff --check` 退出码 0。两次单进程全量 `./mvnw -q verify` 均未通过：第一次 `ShotRedoPostgresIT`、第二次 `MockImageSchedulerPostgresIT` 在 Flyway 初始建连阶段遇到 PostgreSQL socket read timeout，测试方法未开始；两个类独立重跑均通过。一次 10 类批次中的 `PlanResumeWorkerPostgresIT` 也同样在建连阶段超时，随后独立重跑通过。故不能声称单次全量验证通过；长批次 Testcontainers/Docker 稳定性仍需排查。未进行真实媒体或模型 Provider 调用。

后续测试基础设施修正：堆栈定位超时发生在 PGJDBC 初始 SSL 协商读取，测试容器是本地明文 PostgreSQL。仅在 Maven Surefire/Failsafe 测试进程注入 `spring.datasource.hikari.data-source-properties.sslmode=disable`，没有改变生产连接配置。修正前额外按组/独立运行覆盖 38 个 IT 且全部通过；随后一次命令行临时指定该属性的 `./mvnw -q verify`、一次将属性固化到测试插件后的普通 `./mvnw -q verify` 均退出码 0。此前两次失败仍如上保留，不能据此推断未来任何 Docker 环境绝无波动。

2026-09-24 输出布局补充：`place_artifacts` 结果增加对应的 `versionId` 和 `itemVersion`，供 `arrange_items` 以卡片 ID、内容版本 ID、预期布局版本和水平/垂直/网格布局执行。工具预校验全部条目属于可信 Agent 输出分组、当前内容版本对 Run 可见且卡片未锁定，再通过画布应用服务的批量 CAS 命令提交；计算布局时排除目标卡片的原位置，使不同 `tool_call_id` 的同布局重放不会持续右移。扩展的 `ReadToolsPostgresIT` 使用假模型与真实 PostgreSQL 验证卡片版号和位置、内容版本不变，以及分组外卡片、用户手动移动后的旧 CAS、旧内容版本和锁定卡片均被拒。未新增 HTTP 合约或 Flyway 迁移；真实模型未调用。

输出布局切片的 `ReadToolsPostgresIT` 定向通过（含不同 `tool_call_id` 的同布局重放）；随后普通 `./mvnw -q verify` 退出码 0，`git diff --check` 退出码 0。前端无本切片改动，未重跑浏览器测试。

2026-09-24 语义关系补充：`link_artifacts` 仅接受 source Artifact ID、预期 CAS 整数版本、target 内容版本 ID 和四种类型化关系（角色/场景→图片引用、镜头→角色/场景）。source 当前版本与 target 版本都必须在可信 Run 范围内；工具通过 `reviseFromAgent` 复用内容 Schema、权限、版本与引用校验，以 source 新的不可变内容版本记录关系，不写画布线或执行 DAG。同一 source 当前内容中已有相同引用时返回成功但不新增版本；不同 `tool_call_id` 也不重复改写。`LinkArtifactsPostgresIT` 的假模型 + 真实 PostgreSQL 定向测试通过，覆盖双回合工具回执、四种关系、作用域拒绝、类型拒绝、旧 CAS 冲突和无媒体任务。未新增 HTTP 合约或 Flyway 迁移；未调用真实模型。

语义关系切片随后运行普通 `./mvnw -q verify`，退出码 0；`git diff --check` 退出码 0。后端全量测试中的故障注入曾输出数据库回滚异常日志，但未造成该次验证失败。本切片未修改前端，未重跑浏览器测试。

2026-09-24 跨 `tool_call_id` 批准去重补验：`PlanResumeWorkerPostgresIT` 在真实 PostgreSQL 中先持久化含两个不同 ID、相同 `propose_generation_plan` 参数的模型响应。服务层执行第一个提案并经鉴权用户批准后，原 ID 重放返回原持久结果；再以第二个 ID 执行被当前 Run 状态拒绝。该项目最终仅有一份计划、一笔审批、一项 `IMAGE_GENERATION` Task、一笔媒体预留和一条已完成工具账本。此用例刻意直接调用已持久化响应后的工具服务，以验证即使绕过 Runtime 对“一回合计划提案必须为最后一个工具调用”的批次形状检查，也不会因新 ID 再次授权同一步骤。定向 `./mvnw -q -Dit.test=PlanResumeWorkerPostgresIT verify` 与后端全量 `./mvnw -q verify` 均退出码 0；Surefire/Failsafe XML 中未发现失败或错误，`git diff --check` 退出码 0。真实模型未调用，不能据此勾选 T15 整体安全验收。

2026-09-24 可信权限补验：新增 `ToolAuthorityPostgresIT`。已持久化的模型响应分别在合法图片计划参数中夹带 `ownerId`、`userId`、`projectId`、`approved`、`maxImages`、`maxVideos` 和 `budget`，每项均由服务端字段白名单返回 `PLAN_INVALID`；伪造未注册的 `approve_plan` 返回 `TOOL_ARGUMENT_INVALID`。拒绝后无计划、Approval、媒体 Task、媒体额度预留或已完成工具账本；随后同一可信 Run 的合法计划可创建一份待批计划，但仍无审批和媒体 Task。测试故意直接调用已持久响应后的工具服务，检验 Runtime 批次形状校验之外的防御。Run 自身的正常 LLM 用量预留不计入“无媒体预留”断言；首次测试误把它算入而失败，修正断言范围后通过。

`ToolAuthorityPostgresIT`、`ToolExecutionPostgresIT`、`PlanResumeWorkerPostgresIT` 组合定向 `verify` 与完整后端 `./mvnw -q verify` 均退出码 0；Surefire/Failsafe XML 无失败或错误。前端、OpenAPI、Flyway 和生产代码本轮未变更。以上证明本地应用服务权限链和账本行为，不代替真实模型工具协议、真实 Provider、恶意素材或全路径安全验收。
