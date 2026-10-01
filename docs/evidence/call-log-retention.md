# 调用日志与执行账本保留期

2026-10-01。按照用户最新决策，日志和底层执行账本一起手动清理，业务结果保留，不使用定时任务。最新决定包含未结束与 UNKNOWN 的过期执行，并确认停止其本地执行。实现位于 audit 的 CallLogRetentionService、JooqCallLogRepository，CallLogRetentionScheduler 已删除。LLM 的 ExecutionLedgerRetention 与 Task 的 ProviderAttemptRetention 各自拥有账本删除。前端 SystemSettingsPage 只负责分类，SystemDiagnosticsSection、CallLogRetentionSection、DebugModeSection 与 PasswordChangeSection 分别管理对应内容。

策略默认永久；30 天、90 天与自定义 1–3650 天均需管理员明确保存。GET/PUT `/api/v1/settings/call-log-retention` 使用 no-store、管理员授权、CSRF 与 CAS；遗漏 retentionDays 不等于永久，必须明确传 null。设置失败/冲突保留 UI 草稿，重新读取后使用新版本重试。页内四个 tab 的草稿保持挂载，诊断/日志请求只在对应分类启用；侧栏名为系统设置，既有诊断与 debug 链接分别指向相应分类。

保存设置只更新规则，不触发删除。管理员点击“立即清理”并确认后，POST `/api/v1/settings/call-log-retention/cleanup` 传入已确认的 expectedVersion；服务端在每批策略行锁内校验版本，变更返回 409。接口需要管理员权限与 CSRF，响应 no-store，返回实际清理的执行单位数量 cleanedExecutions 和 batchLimitReached。未保存草稿、永久保留或清理中不可再次发起清理；请求不自动重试。

每次手动请求最多十批、每批最多 1000 个执行单位，达到上限提示用户再次手动执行。每批按策略、项目、Run/Task 的顺序锁定，成员锁被跳过时推迟整个执行。所有状态均可清理；完成时间（未完成时使用创建时间）、最近任务活动、调用以及模型/工具/Provider 检查点都必须严格早于 `Clock.now - retentionDays`。过期非终态先停止本地执行为 CANCELED，任务记录 EXECUTION_HISTORY_CLEANED、递增 epoch、清空租约，释放仅该 Run 持有的活动槽并保存版本化项目事件；终态成员保持原状态和结果。旧 Worker 的心跳、完成和晚到结果均不能回写，也不能自动恢复或重发原请求。不再恢复或核对已清理的外部请求，不承诺外部停止或退款；界面和确认弹窗已明确说明。调用元数据、debug 正文、模型回合、工具执行及 Provider 提交账本同事务删除，错误整批回滚；后续批次失败不能恢复之前已提交的删除，前端明确提示结果未确认和手动清理剩余记录。HTTP 幂等、Task/Run 身份和输入/最终结果、用量、创作版本与媒体资产保留。旧对话动作明细和记忆中的工具摘要会消失。未承诺 PostgreSQL 数据文件立即缩小或自动清理既有备份。

## 升级

V71 新增单行保留设置；V72 将清理范围扩展至完整执行历史，移除仅日志删除阶段使用的水位及无用索引。已有 Flyway 文件未修改，已由一次性 PostgreSQL 17.11 迁移库重新生成并提交 jOOQ 源码。本次扩大非终态范围没有新增迁移或修改数据库模型，TS 已由更新后的 OpenAPI 合约重新生成。新字段默认 null，升级后不自动删历史；已有有限保留策略也只在明确手动执行后删除。现有 debug GET/PUT 结构不变，其历史记录受手动清理策略约束。删除不可恢复，延长保留期不会恢复旧账本投影。

## 实际验证

- 后端 `CallLogServiceTest`、`CallLogRetentionServiceTest`、`TaskHistoryCleanupServiceTest`、`RunHistoryCleanupServiceTest`、`ApiI18nTest`、`SecurityI18nTest`：23 项通过。覆盖策略验证、Clock、批次上限、版本、停止后的事件/活动槽处理及失败传播。
- `CallLogPostgresIT`：PostgreSQL 17.11/Testcontainers，41 项通过。覆盖 Run 下与独立任务的七种非终态（PENDING/READY/RUNNING/SUBMITTING/WAITING_PROVIDER/UNKNOWN/BLOCKED）、五种非终态 Run、停止/epoch/租约/原请求 ID/版本事件、旧 Worker 心跳/完成/晚到结果被拒绝、不再认领、整组删除及业务身份保留、停止和删除失败一同回滚、三个账本近期活动推迟整组。既有手动接口的授权/CSRF/CAS/不自动清理、永久、边界、分批、并发项目/Run/任务锁和旧账本投影测试仍通过。
- 前端 `CallLogRetentionSection`、`SystemSettingsPage`、`taskErrorMessages`：20 项通过。覆盖确认弹窗包含未结束/UNKNOWN、停止本地任务和外部计费风险、原因码可读说明，以及保存不清理、确认/取消、防重复提交、上限、失败/冲突、跨 tab 草稿。
- TypeScript、变更文件 ESLint、四语言资源和主题颜色检查通过；Vite 生产构建通过，仍有既有大 chunk 提示。
- 浏览器使用明确标注 Mock 的 HTTP 预览夹具，检查手动清理按钮、确认弹窗与风险说明、桌面及 390×844 窄屏（实际内容宽 375，页面宽与内容宽相同）无水平溢出。截图中 30 天仅为 Mock 已保存夹具；打开确认后取消，未执行真实清理、未改实际系统保留策略，真实默认仍为永久。

![桌面 Mock 预览](assets/call-log-retention/settings-desktop.png)

![手动清理确认 Mock 预览](assets/call-log-retention/manual-confirmation.png)

![窄屏 Mock 预览](assets/call-log-retention/settings-mobile.png)

未运行全量测试、真实 Provider 调用、生产数据库清理或长时间容量测量。浏览器预览不代替 PostgreSQL 持久化证据。
