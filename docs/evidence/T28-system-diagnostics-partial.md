# T28：只读系统诊断页面阶段性证据

2026-09-24。按主规格 §6.1、§24.2 新增 `/settings/general` 的“系统诊断”只读区域与管理员 `GET /api/v1/settings/diagnostics`。响应仅含检查时间、数据库可读状态、存储路径元数据检查、LLM/媒体模式及配置布尔值、最近七天 FAILED/UNKNOWN/BLOCKED Task 的状态计数和最近更新时间。它不返回项目/Run/Task ID、原始错误文本、存储路径、Provider endpoint、密钥或密文；不调用 LLM、ComfyUI 或任何付费生成 API。配置状态不等于外部服务连通性，也不声称真实媒体模板已验证；存储检查只读元数据，不实际写入或证明磁盘空间充足。

PostgreSQL 集成测试在已保存加密 LLM Key 后验证管理员可读、匿名 401、配置状态与响应脱敏、没有 `provider_attempt`；另一项现有任务恢复集成测试验证 UNKNOWN 最近错误只以计数显示且不泄露任务/项目/请求 ID。存储单元测试验证未创建的新路径可由可写父目录判定、普通文件及符号链接被拒绝。前端测试验证状态和错误显示、手动刷新仅做 GET。定向 `./mvnw --batch-mode --no-transfer-progress -Dtest=SystemDiagnosticsServiceTest -Dit.test=LlmProviderConfigPostgresIT,TaskRecoveryPostgresIT verify -q` 通过；前端 `corepack pnpm test`（44 项）、`typecheck`、`lint`、`build` 均通过。Vite 仍提示主 JS chunk 超过 500 kB，未做 300 卡片负载验收。

`contracts/openapi.yaml` 新增只读路径与 `SystemDiagnostics`/`RecentTaskError` Schema，已重新生成 `frontend/src/shared/api/schema.ts`；无 Flyway 迁移。该路径为开发版 0.1.0 的向后兼容新增 GET，不改变已有请求或响应。最终完整后端 `./mvnw --batch-mode --no-transfer-progress -q verify` 退出码 0；实际浏览器人工检查仍未执行。诊断不包含数据库断线后仍可登录、真实 Provider 连通性、磁盘写满或运维告警链路演练。

后续页面补充：`/settings/general` 还包含独立的管理员改密表单，复用已有 `POST /api/v1/auth/change-password`，不属于只读诊断请求。前端验证确认两次新密码不一致时不发送、成功仅提交旧/新密码且清空三个输入、服务端认证失败后也清空输入并显示错误。最终 `corepack pnpm test` 为 46 项通过，`typecheck`、`lint`、`build` 通过；本补充未改后端、OpenAPI 或迁移，后端的其他会话失效仍以 `docs/evidence/T03-identity-session.md` 的真实 PostgreSQL/HTTP 验证为证据。语言选择与媒体 Provider 在线配置仍未完成。

Provider 页面补充：规格路由 `/settings/providers` 现指向既有加密 LLM 设置页，同时显示系统诊断 API 返回的媒体模式、图片与视频配置布尔值；旧 `/settings/llm` 保留。页面明确媒体地址/固定模板由部署管理员通过服务端环境变量配置，状态展示不会联系 ComfyUI，也不等同模板/模型验证。前端 46 项测试、类型检查、lint、构建通过；无后端、OpenAPI 或迁移变更。媒体 Provider 在线编辑与真实连接能力仍未完成。
