# T29 隔离 Compose 数据库＋资产恢复：阶段性证据

日期：2026-09-23。只使用新建的 `agenvas-recovery-source-0923` 与 `agenvas-recovery-target-0923` Compose 项目及其独立命名卷，未使用默认 `agenvas` 项目。

- 源实例：Compose 构建并健康启动，在 Mock 模式下经 HTTP 初始化管理员、登录，创建项目 `Restore smoke project`，上传 16×16 PNG Asset，并创建引用该 Asset 的 IMAGE Artifact。原图 SHA-256 为 `e6aeb8688de618cf3c8d49d4c0798db5eb5e6fd9cd58e63b38b5f348f743d014`。
- 备份：停止源 `web`、`server`，保持 PostgreSQL；`pg_dump -Fc` 生成约 114 KiB 的数据库归档，并把源 `asset-data` 卷打成约 421 B 的 tar.gz。两份备份均计算 SHA-256；未包含真实 Provider 密钥。
- 恢复：在全新目标项目的空 PostgreSQL 与资产卷执行 `pg_restore --no-owner --no-privileges` 和 tar 解包，使用 `AGENVAS_RECOVERY_MODE=true` 启动。目标三服务均健康；原管理员密码登录成功，原项目与 IMAGE Artifact ID/版本可读，私有 HTTP 下载的 PNG 与源文件 SHA-256 完全相同。目标项目 POST 返回 HTTP 503、`RECOVERY_MODE_READ_ONLY`。
- 防重提：`SchedulingConfiguration` 在恢复模式下不启用 Spring 调度；所有现有 Agent、Mock/ComfyUI 媒体、导出和任务恢复扫描组件还各自受恢复模式条件限制。`RecoveryModePostgresIT` 在真实 PostgreSQL 验证所有定时器 Bean 缺席、只读项目及登录相关入口可用、项目/Run/模型诊断写入被 503 拒绝；默认模式的 `MockImageSchedulerPostgresIT` 仍通过。本次没有伪造旧外部提交，不能声称实际 Provider 对账完成。
- 旧 Schema 补充验证：将测试 PostgreSQL 预先迁移到 V27，再以含 V28 的当前应用及恢复模式启动；启动和 HTTP 核对后，Flyway 版本仍是 V27。`RecoveryFlywayConfigurationTest` 验证空数据库不被自动迁移且拒绝作为恢复实例启动。真实旧备份的全版本兼容、Provider 对账及恢复后正式升级步骤仍未完成。
- 本轮补充检查：`./mvnw -q -Dtest=RecoveryFlywayConfigurationTest -Dit.test=RecoveryModePostgresIT verify` 与完整 `./mvnw -q verify` 均通过；`git diff --check` 通过。该旧 Schema 证据来自测试数据库，不是生产备份回滚演练。
- 检查：`./mvnw -q -Dit.test=RecoveryModePostgresIT,MockImageSchedulerPostgresIT verify` 与完整 `./mvnw -q verify` 均通过；默认和 `AGENVAS_RECOVERY_MODE=true` 的 `docker compose config` 均验证通过。OpenAPI 类型重新生成，前端类型检查、lint、26 个测试和生产构建通过（构建仍有大于 500 kB 的 chunk 警告）。数据库仍为 Flyway V28，无迁移。HTTP 恢复演练仅证明 Mock 数据与文件可恢复；没有测试加密配置跨备份、旧 Provider 请求可能已执行的时间窗、停机时正在运行的外部任务、生产耗时/RPO/RTO 或升级回滚兼容性。T29 与 M6 保持未完成。
- 清理：源/目标两个隔离 Compose 项目均已 `down --volumes`，仅含测试数据的容器、网络和四个命名卷已删除；临时数据库归档、资产包、图片与会话 Cookie 也已删除。演练数据不可恢复，默认 `agenvas` Compose 项目仍保持三服务运行。

## 2026-09-24 加密配置备份恢复补验

`LlmProviderConfigPostgresIT` 在源 PostgreSQL 中通过管理员 API 保存三个加密模型配置版本（固定零值测试密钥，不接真实 Provider），使用容器内 `pg_dump -Fc` 生成真实数据库备份，再导入全新的 PostgreSQL 17.11 容器。新建 `CredentialCipher` 使用恢复的同版本测试主密钥逐条解密原配置凭证；轮换后的新主密钥配合版本 7 历史密钥环也能解密，遗漏历史密钥或全部不提供密钥均返回 `CREDENTIAL_KEY_VERSION_MISSING`。备份临时文件及目标容器由测试清理；没有使用、输出或写入真实部署密钥。最终源码的定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=LlmProviderConfigPostgresIT verify -q` 和后端全套 `./mvnw --batch-mode --no-transfer-progress verify -q` 均退出码 0，`git diff --check` 退出码 0；本轮没有前端改动，未重跑前端检查。

这仅证明数据库加密行与匹配密钥可一起恢复，不等于实际部署已安全交接/轮换密钥，也不证明旧 Provider 请求在恢复后可核对；T29 仍未完成。
