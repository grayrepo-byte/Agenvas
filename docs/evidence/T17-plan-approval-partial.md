# T17 计划与两阶段审批：阶段性证据

- 行为：模型 `propose_generation_plan` 只保存 IMAGE/VIDEO 计划、输入快照和 DAG，并使 Run 等待审批；不创建媒体 Task。用户经会话认证 API 按展示的 planHash 批准或拒绝。批准在同一数据库事务内复核输入版本、配置版本与 Run 数量额度，写 Approval/预留、Task、事件和 Run 状态；并发重复批准返回同一审批与任务。步骤通过稳定输出槽位创建新媒体 Artifact，当前仅 Mock。
- 合约/迁移：`contracts/openapi.yaml` 增加计划查询、按 Run 列表、批准、拒绝与响应 Schema；生成的 `frontend/src/shared/api/schema.ts` 同步。Flyway `V16__execution_plans_and_approvals.sql` 增加计划、步骤、审批及计划范围 Task 唯一约束；既有迁移未修改。
- 兼容说明：HTTP 仅新增路径与 Schema，现有响应形状不变；Task 数据库唯一键从 Run 范围调整为已计划 Task 的 Plan 范围及无计划 Task 的 Run 范围，以允许同一 Run 的图片/视频阶段重用 stable stepKey。开发阶段按规格允许这项破坏性数据库约束变更。
- 界面：Run 等待审批时显示阶段、工作流/Provider 版本、步骤输入、预计数量/未知成本、哈希与明确的确认/返回修改按钮；SSE 订阅计划事件。没有自动批准。
- 实际检查：`backend/./mvnw verify`，14 个单元测试、15 个 PostgreSQL Testcontainers 集成测试全部通过；随后针对新增 MockMvc 断言执行 `./mvnw -q -Dtest=ExecutionPlanPostgresIT test` 通过。`frontend/corepack pnpm api:generate`、`typecheck`、`lint`、`test`（11 个）、`build` 全通过；`git diff --check` 通过。PostgreSQL 测试覆盖环依赖、审批前零 Task、布局修改不失效、错误哈希、双线程重复审批、一笔预留、跨阶段固定图片版本与 Mock 输出；MockMvc 覆盖计划只读未登录 401、越权 404、缺 CSRF 审批 403、错误哈希 409。
- 真实 Provider：未调用真实 LLM、ComfyUI 或 FFmpeg，不能称为真实媒体生成。Compose 尚未应用 V16，也未做 HTTP 浏览器完整黄金路径。
- 尚缺：HTTP 真实会话/代理链路验证、配置变更及更多非法类型组合测试、真实提交前再次校验、Run 自动唤醒与恢复、真实 Provider 能力。20 路并发审批已在下文补验；T17 与 MVP 均不应标记完成。

## 后续加固

- 计划提案、批准、拒绝及工具账本执行统一先锁项目事件计数行，再锁 Run；审批的输入版本复核因此与内容修订串行，避免旧输入批准与项目/Run 反序锁死。
- 新增 PostgreSQL 回归断言：跨项目镜头引用被拒；另一事务在持有项目锁时修订镜头，等待中的批准在其提交后返回 `PLAN_CONFLICT` 且无 Task；拒绝可使 Run 返回规划状态。同 Run 图片数量累计超过八张时第二次批准被拒且不产生额外 Task。
- 加固后执行 `./mvnw -q -Dtest=ExecutionPlanPostgresIT test` 与完整 `./mvnw verify -q` 均成功；完整验证包含上述并发编辑测试。
- 配置变更与真实提交前再次校验仍未覆盖；上述加固不代表 T17 已完成。

2026-09-24 计划输入边界补验：`ExecutionPlanPostgresIT` 在真实 PostgreSQL 中将一个同项目、已绑定的 SCENE 版本冒充 SHOT，服务端返回 `PLAN_INVALID`；将另一个项目的真实 SHOT 版本填入计划，服务端返回不泄露资源存在性的 `RESOURCE_NOT_FOUND`。两次拒绝后 `execution_plan` 仍为 0。原有测试同时覆盖 DAG 环及批准时媒体额度超限且不多建 Task。定向 `./mvnw -q -Dit.test=ExecutionPlanPostgresIT verify` 与完整 `./mvnw -q verify` 均退出码 0，Surefire/Failsafe XML 未发现失败或错误，`git diff --check` 退出码 0。这不涵盖真实 Provider 或所有计划变更时序。

2026-09-24 版本失效补验：新增 `PlanApprovalVersionPostgresIT`，在真实 PostgreSQL 中创建待批图片计划后，分别模拟服务端 Provider 配置版本及固定图片工作流版本变化。两次批准均返回 `PLAN_CONFLICT`，计划仍待批，Run 仍等待审批，且无 Approval、媒体 Task 或额度预留；恢复原版本后同一计划才生成一项 Task。既有 `ExecutionPlanPostgresIT` 还验证内容编辑后的批准冲突与纯画布布局变化后的可批准。定向 `./mvnw -q -Dit.test=PlanApprovalVersionPostgresIT verify` 和完整 `./mvnw -q verify` 均退出码 0；Surefire/Failsafe XML 未发现失败或错误。该测试模拟版本变化，未连接真实 Provider，也未演练实际部署时配置轮换。

2026-09-24 审批入口补验：`PlanApprovalPanel` 新增“取消本次 Run”按钮，调用现有受会话/CSRF 保护的 Run 取消 API；请求期间禁用其余审批动作，失败保持面板并提示核查 Run 状态。组件测试确认取消不发送批准、失败后可再试。`ExecutionPlanPostgresIT` 将原 2 路并发审批扩为同时提交 20 路，核对恰好一个首次批准、其余重放均返回相同审批 ID 与三项任务；数据库仍只有一笔 Approval 和三项媒体预留。定向 PostgreSQL 测试及完整 `./mvnw --batch-mode --no-transfer-progress -q verify` 通过；前端定向组件测试 4/4，全量 42/42，类型检查、lint、构建通过（仍有主包超过 500 kB 提示）。该项不改变 OpenAPI、迁移或审批状态机；真实浏览器取消交互及真实 Provider 仍未验收。

## 显式关键帧选择

- Flyway `V17__shot_keyframe_selection.sql` 增加每个镜头的选定图片版本、来源任务、选择者与 CAS 版本；与 Artifact/Version/Task/User 使用数据库外键。HTTP 新增 Run 全量 Task 列表与镜头关键帧读取/选择，OpenAPI 和生成 TypeScript 同步。
- Flyway `V18__keyframe_task_project_boundary.sql` 追加来源 Task 的复合项目外键，确保选择记录无法跨项目指向任务；V17 保持不改。
- 用户只能选择本轮、同镜头、已成功的图片任务输出。视频计划提案要求逐镜头精确匹配用户选择，批准时再次核对选择版本；改变选择不能沿用旧视频计划。前端从已完成任务列出 Mock 图片并提供明确选择按钮，不将它显示为真实媒体预览。
- PostgreSQL 测试覆盖未选择时拒绝视频计划、跨镜头图片拒绝、选择 API 缺 CSRF 403、选定后的视频计划与批准。前端组件测试覆盖不会自动选择及提交精确版本。真实媒体文件和阶段 B 自动唤醒仍未实现。
- 选择切片检查：最终 `backend/./mvnw verify -q` 通过，Flyway 18 个迁移在 PostgreSQL Testcontainers 应用，14 个单元测试及 15 个集成测试均通过；定向 `./mvnw -q -Dtest=ExecutionPlanPostgresIT test` 亦通过。前端 `api:generate`、`typecheck`、`lint`、`test`（13 个）、`build` 全通过，包含新增 SSE 事件断言。
