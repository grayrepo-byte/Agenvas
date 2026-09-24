# T21：ComfyUI 地址指纹固定（阶段性）

- 行为：ComfyUI 图片和视频计划把服务端配置的精确 origin SHA-256 写入每个步骤输入，因此计划哈希覆盖该指纹。用户批准时再次比对当前 origin；不一致时不创建任务。计划任务提交前再次比对；已受理任务轮询前还要核对当前 origin 与持久 ProviderAttempt 中原请求的 origin、request ID，避免相同数字配置版本误指向另一实例。
- 范围：指纹仅用于比较，不把 endpoint、密钥或任意 URL 放进任务输入。查询与归档仍仅使用固定的 ComfyUI 路由；拒绝时保留已受理请求记录，不把旧请求视为可安全重提。`ManualUnknownRetryService` 的显式新尝试也复核原计划的 origin。
- 合约与迁移：只增加服务器生成的步骤输入字段 `providerOriginSha256`；现有 OpenAPI 的 `ExecutionPlanStep.input` 与 `Task.input` 为开放的对象形状，不需要重生成前端类型。没有数据库迁移。开发期既有未提交 ComfyUI 计划若缺少指纹，批准/提交会被拒绝，须重新规划。
- 测试：`ComfyUiImagePostgresIT` 验证指纹被计划持久化、篡改后审批无任务副作用、相同数字版本但不同 origin 的 Worker 在网络查询前阻断已受理任务。`ComfyUiOriginPreflightPostgresIT` 验证同版本不同地址在提交 checkpoint 前失败，ProviderAttempt 数为 0。`ComfyUiVideoPostgresIT` 验证视频计划同样固定指纹；原请求核对与显式新尝试的 PostgreSQL 测试继续运行。上述五组 PostgreSQL 定向测试已通过。
- 实际检查：`./mvnw --batch-mode --no-transfer-progress -q -Dtest=ComfyUiImagePostgresIT,ComfyUiVideoPostgresIT,ComfyUiReconciliationPostgresIT,ManualUnknownRetryPostgresIT test`、新增预检类定向测试，以及完整 `./mvnw --batch-mode --no-transfer-progress -q verify` 均退出码 0；本次无前端代码改动，未重复运行前端测试。
- 后续：V34 已补旧 ComfyUI endpoint 的持久保留与原请求查询，见 `T21-comfyui-historical-lookup-partial.md`。旧凭证与工作流模板版本仍未覆盖；真实 ComfyUI 和密钥轮换未联调，因此 T21 和 MVP 门禁仍未完成。
