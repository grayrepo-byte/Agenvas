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

profile 默认连 `localhost:55432`，可用 `-Djooq.codegen.jdbcUrl=...` 覆盖。**不要把它指向开发库或部署库**：Flyway 会执行迁移，且 jOOQ 会对整个 schema 建元数据。

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
