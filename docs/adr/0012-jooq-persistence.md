# ADR 0012：持久化改用 jOOQ，生成源码入库

状态：接受（2026-09-26）。

数据访问从 Spring `JdbcClient`（手写 SQL 字符串 + 位置/命名参数）改为 jOOQ。同时移除 MyBatis-Plus starter —— 它在整个代码库中零引用，只是基线里的一行依赖。

## 背景

原实现有 24 个使用 `JdbcClient` 的类（仓储、Reader 及少量服务），约 5000 行、229 处 `sql(...)` 调用。每一处都用字符串拼 SQL、用 `rs.getObject("列名", X.class)` 手工映射，代价是三类问题反复出现：

- **列名与类型只存在于字符串里**。改列名、改列类型、把 `char` 换成 `varchar`，编译器全都不报错，只能靠集成测试兜底。`cast(:x as jsonb)`、`java.sql.Types.OTHER`、`Types.NUMERIC` 这类手写类型提示散落在调用点，是靠人记住 PostgreSQL 的类型推导规则。
- **参数与占位符的对应靠位置**。`.param("projectId", ...)` 与 SQL 里的 `:projectId` 之间没有机械校验。
- **无法表达的查询用字符串拼**（排序白名单、动态条件），拼错就是运行期 SQL 错误。

MyBatis-Plus 在本仓库中从未被使用：`grep -rn "^import.*baomidou\|^import.*mybatis" src/` 命中 0 行，`@Mapper`/`@TableName`/`BaseMapper` 命中 0 行。它只出现在 `pom.xml`、`AGENTS.md`、`MVP-SPEC.md` 和依赖基线里。

## 决策

**1. 用 jOOQ 替换 JdbcClient。** `spring-boot-starter-jooq` 取代 `spring-boot-starter-jdbc`（前者传递依赖后者，因此 `JdbcTemplate`/`JdbcClient` 仍在类路径上，供 Spring Session JDBC 内部使用）。Repository 注入 Boot 自动配置的 `DSLContext`。

**2. 移除 MyBatis-Plus starter 及其版本属性。**

**3. jOOQ 生成源码提交入库，构建期不连数据库。** 这是被部署形态倒逼的：`deploy/docker/server.Dockerfile` 在固定的 Maven/Temurin 21 构建镜像里执行 `./mvnw verify -DskipITs`，容器内没有 Docker daemon 也没有 PostgreSQL。构建期 codegen 只有两条路，都不可行：

- **离线 codegen（`DDLDatabase` 解析 Flyway 迁移）**：已实测失败。jOOQ 3.19 开源版解析 `CREATE FUNCTION ... LANGUAGE plpgsql` 时抛出 `Feature only supported in pro edition`，而本仓库的迁移含触发器函数（`reject_artifact_version_mutation` 等）。
- **构建期连真实 PostgreSQL**：镜像构建环境不存在该数据库。

因此生成结果放在 `src/jooq/java`（101 个文件，包名 `dev.agenvas.db`），通过 `build-helper-maven-plugin` 加为源码根。生成的是 jOOQ 官方模板产物，**禁止手改**；与前端 `src/shared/api/schema.ts` 的处理方式一致。

**4. codegen 走 `jooq-codegen` profile，对一次性数据库执行。** 该 profile 先用 `flyway-maven-plugin` 把 `src/main/resources/db/migration` 迁到一个空的 PostgreSQL 17，再用 `jooq-codegen-maven` 反向生成：

```bash
docker run -d --name agenvas-jooq-codegen \
    -e POSTGRES_DB=agenvas -e POSTGRES_USER=agenvas \
    -e POSTGRES_PASSWORD=codegen-only -p 55432:5432 postgres:17.11-alpine
./mvnw -Pjooq-codegen generate-sources
```

profile 默认连 `localhost:55432`，可用 `-Djooq.codegen.jdbcUrl=...` 覆盖。CI 让 Docker 自动分配仅绑定 127.0.0.1 的宿主端口，读回实际端口后覆盖 JDBC URL，避免与 Testcontainers 临时映射端口冲突；就绪检查指定 TCP 地址，避免把初始化阶段仅接受 Unix socket 的临时服务当成正式服务；一次性容器及匿名卷在步骤结束时清理。**不要把它指向开发库或部署库**：Flyway 会执行迁移，且 jOOQ 会对整个 schema 建元数据。

## 迁移约定

生成代码的表/列常量都在 `dev.agenvas.db.Tables`，按 `表名.列名` 静态导入使用（如 `USAGE_LEDGER.OPERATION_KEY`）。列类型由数据库推导：`uuid→UUID`、`timestamptz→OffsetDateTime`、`jsonb→JSONB`、`numeric→BigDecimal`、`smallint→Short`、`bytea→byte[]`。

- **写入用 `.set(表.列, 值)` 链**，不用位置参数 `values(Object...)`；可空列因此不必再手写 `java.sql.Types.OTHER`。
- **JSONB** 写入 `JSONB.valueOf(json)`，读取 `record.getXxxJson().data()`。
- **枚举列**写 `枚举.name()`，读 `枚举.valueOf(...)`。
- **CAS 与行锁**用 `.execute() == 1`、`.forUpdate()`、`.forUpdate().skipLocked()`，与原来的 `update() == 1` / `for update` 语义一一对应。
- **幂等插入**用 `.onConflict(列).doNothing()`；PostgreSQL 对未插入行返回影响行数 0，与原来的 `update() == 1` 判断一致。
- **PG 专有且 DSL 表达别扭的 SQL**（聚合 `FILTER (WHERE ...)`、`extract(epoch from ...)`、复杂 CTE + `delete ... using`）允许通过 `dsl.fetchSingle(sql, 绑定值)` / `dsl.execute(sql, 绑定值)` 保留原 SQL 文本。这仍是 jOOQ 调用，不再有 `JdbcClient`。用到的地点必须能说明为什么不用 DSL。
- **java.time 绑定必须显式开启原生模式。** 实测（jOOQ 3.19.37 + PostgreSQL 17.11）：默认设置下 jOOQ 为兼容旧驱动会把 `OffsetDateTime` 编码成**字符串**绑定，`dsl.resultQuery("... occurred_at < ?", offsetDateTime)` 于是报 `operator does not exist: timestamp with time zone < character varying`。生成代码的类型化字段不受影响（`.set(TABLE.TS_COL, offsetDateTime)` 正常），只有纯 SQL 的绑定值中招。修法是全局开启 `Settings.bindOffsetDateTimeType(true)`（见 `JooqSettingsConfiguration`），启用后两条路径一致；`?::timestamptz` 显式转换也能绕过，但没有必要。其余类型（UUID、String、Long、Integer、Boolean、BigDecimal）实测不受影响。

事务语义不变：Boot 的 `JooqAutoConfiguration` 把 `DataSource` 包进 `TransactionAwareDataSourceProxy` 再交给 jOOQ，`DSLContext` 因此参与 Spring 管理的事务。「业务状态、执行账本、project_event 同事务提交」这一约束不受影响。

## 范围边界

本次只迁移 `src/main/java` 的数据访问。`src/test/java` 里约 60 个集成测试仍用 `JdbcClient` 直接写 SQL 断言数据库状态，这是**有意保留**的：

- 测试里的裸 SQL 是独立于生产映射的验证通道。让测试复用生产同一个 jOOQ 映射，等于用被测代码验证被测代码——列的取值断言会跟着映射的错误一起错。
- Spring Session JDBC 与 jOOQ starter 都会把 `spring-jdbc` 带进类路径，`JdbcClient` bean 依然存在，测试无需改动。

因此「仓库中不存在 `JdbcClient`」不是本次的验收标准；**生产数据访问层不再有 `JdbcClient`** 才是。

## 后果

- 数据库 schema 成为编译期输入：列名/类型写错在编译或测试期即暴露。排序白名单这类动态查询由 jOOQ 的字段常量表达。
- **生成代码与迁移会漂移**。普通构建与部署镜像都不会重新生成（构建期不连数据库），`./mvnw verify` 也不做。兜底有两条：CI 的 backend job 对一次性 PostgreSQL 重跑 `jooq-codegen` profile，并要求 `git status --porcelain -- src/jooq/java` 为空（用 porcelain 是因为新增表会产生未跟踪文件，`git diff` 看不到）；集成测试跑真实 PostgreSQL，能发现映射与 schema 不符。漂移到测试覆盖不到的表时，仍只能靠人重新生成。
- `src/jooq/java` 有 101 个文件入库。它们不参与代码评审的逐行阅读，但会出现在 diff 里；改动数据库 schema 后重新生成会产生大量噪声 diff。
- 依赖基线减少一项（MyBatis-Plus），增加一项（jOOQ，Boot 4.0.8 管理的 3.19.37）。

## 2026-10-02 未发布基线重建

状态：接受；用户明确要求在应用尚未发布时重建数据库，清理开发遗留字段和表，并补齐 DDL 注释；同时授权先备份再重置现有开发环境。这是“Flyway 文件只增不改”的一次性例外，不改变后续迁移规则。

旧目录包含 V1–V77 共 76 个迁移文件（没有 V61）。先在隔离 PostgreSQL 17.11 执行旧链以核对最终模型，再依据当前生产消费者清理结构，形成单个 `V1__initial_schema.sql`。新基线为 67 张业务/会话表、567 列，保留必要的外键、唯一与检查约束、索引、不可变版本触发器及任务租约/CAS 边界；Flyway 自己创建历史表。所有表、列、约束、索引、函数和触发器通过 `COMMENT ON` 持久化用途注释，基线测试从 PostgreSQL catalog 校验覆盖。

删除 `creative_data_reset_marker`、`comfyui_config_version`、`media_legacy_import_marker`、`media_legacy_origin_map` 及对应重置/环境导入/未绑定媒体 Worker 链。所有媒体任务通过连接与能力目录固定版本，由统一内核执行。删除 `task_dependency` 与任务 `PENDING`：Agent 续回合在同一事务中先完成旧任务、再创建 READY 后继，任一步失败全部回滚；没有媒体执行 DAG。审批自己的 `PENDING` 保留。

任务来源由 run_id 是否为空直接确定，删除重复的 task.origin 字段；直接任务允许类型的 CHECK 与部分索引保留等价语义。删除仅写空值或常量的 `task.provider_id`、`agent_binding.binding_type`、`asset.status`、`installation_lock.purpose`，移除旧规划输出槽 `task_artifact_target.output_slot_key` 并要求目标产物非空；删除把空节点选择与共享产物版本绑定的旧 mode CHECK，允许资源默认版本变化后空节点继续生成。删除旧候选请求定位字段 `provider_attempt.candidate_request_id/candidate_origin_sha256`；当前尝试保留已提交 request_key、外部请求 ID 与固定连接/能力版本。Skill 安装的命令键与摘要只保留在 `skill_install_command`，不在 operation 重复存储。UNKNOWN 不自动重提，旧 Worker fencing、用户版本选择、取消晚到归档及 SSE 同事务约束保持。

初始化数据为 28 行：安装/设置单例、Mock 图片/视频/音频连接与能力、默认能力、LOCAL 图片处理能力、8 个内置风格及存储默认值。时间使用 `now()`；不包含用户、项目、任务、素材、真实连接或凭据，也不再种入任何开发重置或旧环境导入标记。

这是未发布的破坏性基线切换：Task API 去掉 providerId、ASSET_INGEST/PENDING，Agent 输入去掉 bindingType，使用量去掉 exportCount，媒体内容去掉旧全局 providerConfigVersion，诊断媒体模式只保留 MOCK/CONFIGURED。媒体使用量记录实际连接版本；LLM 使用量仍记录实际模型配置版本。OpenAPI 与生成 TS 同步，jOOQ 从新空库重新生成；不保留旧客户端或旧媒体 JSON 兼容层。

新 V1 定稿后不再重写，后续 Schema 或初始化数据变化从 V2 开始追加。本次验收依据是空库初始化、全部注释覆盖、退役结构缺失、当前行为及恢复安全回归；清理后的 Schema 有意与旧最终库不同，不能再以 dump 完全等价作为验收。实际命令和结果见开发清单及依赖基线，旧迁移验收仍保持历史范围。

2026-10-03 基线审阅补充：从已重建的当前数据库导出仅含结构的 SQL，排除 Flyway 历史表，再按表与依赖顺序整理 V1。567 个字段在定义旁直接显示说明；全部约束写入 `CREATE TABLE`，数据库注释、索引及触发器紧随所属表，不保留后续 `ALTER TABLE` 或开发期回填。公开内置种子沿用审阅后的初始化数据，不导出用户或真实配置。首次排版整理与当时的重建库结构完全一致；以下外键精简是随后按用户决定实施的结构变化。

同日按用户要求精简 13 个外键，保留 99 个必要外键和全部字段、索引、唯一及检查约束，数据库对象注释变为 1,229 条。移除 CreativeSkill 当前版本、Agent 当前会话、Artifact 资源默认版本及 Project 活动 Run 的四个反向指针外键，以及 ArtifactVersion 父版本的自引用外键，依赖图不再有循环。指针由所属应用服务的归属查询、事务、项目锁和 CAS 维护；首个资源版本的 SQL 更新也校验项目及产物归属。不可变版本触发器、版本所属产物外键、Run 所属会话复合外键与核心跨项目约束继续保留。

CallLog 与 UsageLedger 的项目、Run、Task 标识改为历史弱引用，移除六个外键；清理关联执行对象不会自动删除记录或清空原标识。写入身份仍来自可信执行上下文，查询仍须校验现存项目权限；本次不改变日志保留策略、不新增删除业务对象的 API，也不让已删除项目的记录可被越权读取。外部 Provider 请求 ID 原本没有外键，继续保留以核对原请求。另移除 LibraryEntry 中被包含媒体类型的文件复合外键覆盖的简单文件外键，以及 AgentSkillBinding 中被 Skill 版本归属覆盖的 owner 外键；LibraryEntry 的 owner 外键保留，因为文字条目没有 file_id，文件复合外键不能覆盖其用户归属。保留更强的归属约束及原删除语义。旧 V77 边界保持，当前仍未定稿的本地 V1 可在备份后事务性应用这些约束和注释差异，并校验唯一历史行后同步 checksum；不得将此操作用于已发布迁移。

旧 V1–V77 history 不能直接启动新基线；不得通过 repair、删除历史行或自动 baseline 掩盖边界。重置前保存 PostgreSQL、素材、密钥、配置和匹配旧镜像的一致性私有备份，并在隔离库验证可恢复。新版本从空数据库和空素材卷初始化，不自动导入旧数据；恢复旧备份须使用对应旧版本并先进入恢复模式。项目导出清单不能代替数据库和素材备份，操作步骤见[备份与恢复说明](../operations/backup-restore.md)。
