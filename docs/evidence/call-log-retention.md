# 调用日志与执行账本保留期

2026-10-01。按照用户最新决策，日志和底层执行账本一起清理，业务结果保留。实现位于 audit 的 CallLogRetentionService、CallLogRetentionScheduler、JooqCallLogRepository，LLM 的 ExecutionLedgerRetention 与 Task 的 ProviderAttemptRetention 各自拥有账本删除。前端 SystemSettingsPage 只负责分类，SystemDiagnosticsSection、CallLogRetentionSection、DebugModeSection 与 PasswordChangeSection 分别管理对应内容。

策略默认永久；30 天、90 天与自定义 1–3650 天均需管理员明确保存。GET/PUT `/api/v1/settings/call-log-retention` 使用 no-store、管理员授权、CSRF 与 CAS；遗漏 retentionDays 不等于永久，必须明确传 null。设置失败/冲突保留 UI 草稿，重新读取后使用新版本重试。页内四个 tab 的草稿保持挂载，诊断/日志请求只在对应分类启用；侧栏名为系统设置，既有诊断与 debug 链接分别指向相应分类。

每五分钟清理一次，首次启动等待一分钟，每次最多十批、每批最多 1000 个执行单位。每批短事务锁定策略、终态 Run/Task，活动任务锁跳过整个执行。仅 SUCCEEDED/FAILED/CANCELED 执行可清理，完成与最近活动严格早于 `Clock.now - retentionDays`；Run 的全部任务及调用都必须符合条件，UNKNOWN、待处理、过期租约的可恢复执行继续保留。调用元数据、debug 正文、模型回合、工具执行及 Provider 提交账本同事务删除，错误整批回滚。HTTP 幂等、Task/Run 身份和输入/最终结果、用量、创作版本与媒体资产保留。旧对话动作明细和记忆中的工具摘要会消失。未承诺 PostgreSQL 数据文件立即缩小或自动清理既有备份。

## 升级

V71 新增单行保留设置；V72 将清理范围扩展至完整执行历史，移除仅日志删除阶段使用的水位及无用索引。已有 Flyway 文件未修改，已由一次性 PostgreSQL 17.11 迁移库重新生成并提交 jOOQ 源码；TS 由 OpenAPI 重新生成。新字段默认 null，因此升级后不会自动删任何历史。现有 debug GET/PUT 结构不变，其历史记录改为受保留策略约束。删除不可恢复，延长保留期不会恢复旧账本投影。

## 实际验证

- 后端 `CallLogServiceTest`、`CallLogRetentionServiceTest`：9 项通过，覆盖策略验证、永久/有限/CAS、Clock、批次上限和失败传播；编译通过。
- `CallLogPostgresIT`：PostgreSQL 17.11/Testcontainers，15 项通过。涵盖授权/CSRF/no-store、必需字段、范围/CAS、元数据与正文/各类账本同删、业务身份保留、永久切换不重现旧日志、UNKNOWN/边界/近期活动保护、分批、失败回滚、直连任务及仅有旧账本的历史，并用并发事务验证单个 Task 被锁时跳过整个 Run。
- 前端 `CallLogRetentionSection`、`SystemSettingsPage`、`DebugModeSection`、`CallDebugDetails`、`LlmSettingsPage`、`PageShell`：29 项通过，涵盖时长选择/自定义整数/失败与冲突保留、会话过期、分类读取、键盘切换及跨 tab 草稿。
- `ApiI18nTest`、`SecurityI18nTest`：8 项通过。
- TypeScript、变更文件 ESLint、四语言资源和主题颜色检查通过；Vite 生产构建通过，仍有既有大 chunk 提示。
- 浏览器使用明确标注 Mock 的 HTTP 预览夹具，检查系统设置命名、四个分类、自定义 45 天草稿跨 tab 保持、桌面及 390×844 窄屏（实际内容宽 375，页面宽与内容宽相同）无水平溢出；截图中的 45 天为未保存示例，未改实际系统保留策略。

![桌面 Mock 预览](assets/call-log-retention/settings-desktop.png)

![窄屏 Mock 预览](assets/call-log-retention/settings-mobile.png)

未运行全量测试、真实 Provider 调用、生产数据库清理或长时间容量测量。浏览器预览不代替 PostgreSQL 持久化证据。
