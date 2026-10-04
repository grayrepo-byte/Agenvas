# 自托管备份与恢复（开发期操作说明）

本说明适用于根目录 `docker-compose.yml` 镜像部署、`docker-compose.local.yml` 源码部署及原 `deploy/compose.yaml` 的单机 Compose 环境。三个入口默认复用相同 `agenvas` 数据库、资产和密钥卷；备份脚本按显式项目名操作现有容器，不构建或重建服务，模板归档包含两个根目录 Compose 文件与 deploy 下的完整独立配置。下文恢复命令以源码入口为例；镜像恢复需改用根目录入口，并直接修改其中的 image 为与备份兼容的已发布版本或 digest；不要将滚动 latest 视为备份对应版本。数据库是业务状态真相；数据库与资产卷必须来自同一个停止写入的时间窗。合并基线前的隔离 Mock 实例曾完成数据库＋资产恢复演练；另有当时的 PostgreSQL 集成测试验证加密配置行经 `pg_dump/pg_restore` 后可由恢复的测试密钥解密。这些是原版本的历史验收，不证明新 V1 的恢复验收。**尚未**完成完整部署的密钥交接/轮换演练、真实 Provider 旧请求核对或生产 RPO/RTO 验收。

## 未发布基线重建的边界

2026-10-02 按用户明确要求，将尚未发布的 V1–V77 开发迁移一次性合并为 `V1__initial_schema.sql`，后续从 V2 开始追加。详见 [ADR 0012 补充](../adr/0012-jooq-persistence.md#2026-10-02-未发布基线重建)。当前 V1 创建 67 张业务表和 28 条必需种子，所有业务表、字段、约束、索引、触发器与函数都有数据库注释。旧开发重置/媒体导入标记、旧 ComfyUI 配置版本表、任务依赖与多余字段已删除；启动不执行开发数据重置或旧环境媒体配置导入。

带旧 V1–V77 Flyway history 的数据库不能直接升级到当前版本。不要对旧库执行 `repair`、删除 history 行或自动 baseline；这些操作不会迁移业务数据，也会掩盖版本边界。需要保留旧环境时，先按下文归档数据库、完整素材、部署配置、当前/历史密钥与对应旧镜像或 Git revision，再保持旧环境独立。需要保留旧环境时，当前版本使用独立空数据库和独立素材卷安装。明确授权重置的本地开发环境可在备份和恢复验证完成后清空目标卷，按新基线重新初始化；旧业务数据不会自动导入。

旧历史的备份使用备份对应的旧版本恢复到独立目标；合并后 V1 及后续版本的备份使用兼容的新版本恢复到另一独立目标。恢复模式只提供核对窗口，不能让两套迁移历史相互兼容。当前没有自动旧库迁移或数据导入，项目导出只是清单，不能恢复数据库或素材字节。

## 显式重置本地开发环境

完全重建采用“备份后清空并重新安装”，没有旧库升级或自动导入。执行前必须确定准确的 Compose 项目名、部署文件、数据库卷和资产卷；只处理该目标项目。备份与隔离恢复验证不等于在线重置成功，仍须验证重建镜像和目标环境。 本轮目标本地环境已完成空卷重建、V1/注释覆盖/空业务数据及服务健康核对，范围与限制见[开发清单](../DEVELOPMENT-CHECKLIST.md)；这不代表生产升级演练。

1. 在停止写入的窗口，备份数据库、完整素材卷、部署配置、当前/历史密钥及对应旧镜像。备份放在仓库外私有目录，目录权限 `0700`、文件权限 `0600`；密钥另外加密保管。
2. 核对归档哈希与可读取性，在独立数据库和卷执行恢复验证。旧数据库的恢复使用对应旧版本，不使用新 V1 的应用修复 Flyway history。
3. 停止已确认项目的应用与数据库，核对目标卷名称后，仅删除该项目的数据库卷和素材卷。保留备份及旧镜像；不删除其他 Compose 项目或用户现有备份。
4. 使用新代码构建镜像，在同一个已确认项目重新创建空卷并启动数据库和应用。由 Flyway 执行新 V1，不运行 `repair`、旧 history 导入或自动 baseline。
5. 验证 Flyway history 只有新 V1、业务表及种子符合基线、管理员初始化状态为未完成、健康检查正常、素材卷为空；通过后再创建管理员与模型配置。用户数据、历史任务和个人资产不会自动恢复到新库。

上述步骤是操作要求，不表示当前部署已完成重置。每次执行记录必须分别写清备份、隔离恢复、镜像构建、目标重置和启动验证的实际结果；私人路径、账号及执行 ID 不进入公开文档。

## 备份范围

1. PostgreSQL 数据库：项目、版本、Run/Task、Provider attempt、媒体连接及不可变能力版本、版本化连接地址、幂等账本、审批、事件与会话，以及个人资产条目、库文件、转存命令、导入追溯与清理队列。
2. `asset-data` 卷：原图、视频、音频、缩略图及任务键归档文件；根目录下的 `library/` 包含账号独立媒体、缩略图和已受理转存的文件 pin，必须完整备份。
3. 当前部署的 `configs/`、`deploy/`、应用镜像或精确 Git revision、所选 Compose 的非密钥运行配置及模板/模型文件名和版本。
4. 与该数据库对应的 `credentials-data` 卷（含数据库密码与当前主密钥）、`AGENVAS_CREDENTIAL_MASTER_KEY`、`AGENVAS_CREDENTIAL_KEY_VERSION`、`AGENVAS_CREDENTIAL_PREVIOUS_KEYS` 和外部 Provider 凭证。媒体连接与 LLM 连接的历史密文都可能引用旧密钥版本；密钥与数据库/资产备份**分开**加密存放，记录版本对应关系；不要把密钥放进项目导出或本仓库。

每天至少备份一次是初始建议，不代表已达到 RPO ≤24 小时或 RTO ≤2 小时。需要先完成正式恢复演练和计时。

## 一致性备份顺序

在维护窗口阻止新用户写入，记录当前版本和所有 `SUBMITTING`、`WAITING_PROVIDER`、`UNKNOWN` 任务及 Provider 原请求 ID；等待安全完成的短事务，对不能安全结束的外部提交保留不确定状态。停止该 Compose 项目的 `web` 与 `server`；**保持 `postgres` 运行**。确认应用容器已停止后，对同一项目执行 `pg_dump -Fc` 并将其 `asset-data` 卷打包。使用带日期、项目名和哈希的独立备份目录，并验证两个备份文件非空且可读取；把配置、模板版本与密钥版本清单一同保存。

仓库提供显式项目名的备份命令；先确认准确的 Compose 项目名和该部署的 Compose 文件，无需 env 文件。输出必须是仓库外、父目录已存在的**全新绝对路径**，脚本不会覆盖旧备份。它检查目标 PostgreSQL 和资产卷，记录原本运行的 Web/Server，停止写入后导出数据库、资产卷及仓库中的 `configs/`、`deploy/` 模板，校验归档可读取并写入 SHA-256 清单，最后恢复原本运行的服务。维护窗口内仍应先执行上文所述的外部请求核对和写入隔离；脚本不能替代 Provider 对账。

```sh
deploy/backup-compose.sh <确切Compose项目名> <全新绝对备份目录> [该部署Compose文件]
```

脚本需要 Docker Compose、`jq`、`sha256sum`、`tar` 和 `git`；`manifest.json` 包含项目名、Git revision、时间和归档哈希，默认读取根目录 `docker-compose.yml`，可通过第三个参数选择源码/Mock 或仓库外的部署文件。清单和归档**不包含** `credentials-data`、仓库外私有配置、主密钥、历史密钥、Provider 凭证，也不保证构建镜像、仓库外模板或模型权重已备份。上述内容须另行加密保管并与此清单关联。脚本失败时尝试恢复原本运行的 Web/Server，失败目录不可作为可恢复备份；必须人工确认服务状态。只有可信、由本部署产生的归档才可恢复；不要从不可信 tar 包提取文件到资产卷。

## 自动密钥的交接与恢复

首次启动无需 `.env`。数据库密码和主密钥位于 `credentials-data` 中的 `installation/`：`database-password`、`credential-master-key`。容器内根目录为 `/run/agenvas/credentials`，server 以只读方式挂载。普通 `down` 保留卷；`down -v` 会同时删除密钥、数据库和资产。

另外保存整个密钥卷并加密，不能只备份数据库。下例使用部署者安装的 GnuPG；`KEY_ESCROW_FILE` 指向仓库外、与数据库归档分开的私有位置，其父目录权限为 `0700`。先确认准确的 `COMPOSE_PROJECT_NAME` 和卷存在，勿将密钥输出到日志：

```sh
umask 077
docker volume inspect "${COMPOSE_PROJECT_NAME}_credentials-data" >/dev/null
docker run --rm -v "${COMPOSE_PROJECT_NAME}_credentials-data:/source:ro" \
  postgres:17.11-alpine tar -C /source -czf - . \
  | gpg --symmetric --cipher-algo AES256 --output "$KEY_ESCROW_FILE"
```

恢复到独立目标时，先核对目标卷是新卷，将**可信且匹配数据库**的密钥归档恢复，再启动 PostgreSQL。不要先启动应用生成另一套主密钥：

```sh
gpg --decrypt "$KEY_ESCROW_FILE" \
  | docker run --rm -i -v "${COMPOSE_PROJECT_NAME}_credentials-data:/target" \
      postgres:17.11-alpine tar -C /target -xzf -
```

`AGENVAS_CREDENTIAL_KEY_VERSION` 和 `AGENVAS_CREDENTIAL_PREVIOUS_KEYS` 仍由部署者在私有 Compose 中按原版本恢复。自动生成只负责首次安装，不能代替主密钥轮换或推断历史密钥。

从旧 `.env` 部署迁移时，在仓库外权限 `0600` 的私有 Compose 副本中，向 postgres 的两个空白 `AGENVAS_*` 字段填入原数据库密码和主密钥，再用该文件启动。首次成功导入后将这两个字段清空，后续使用持久卷。旧环境如果从未设置主密钥且从未保存加密凭据，可以显式生成一个 32 字节 Base64 主密钥；已存在密文时必须找回原钥，不能换成新值。已有数据库无对应密钥卷且未显式提供完整原值、密钥卷不完整或配置与持久值冲突时，启动会明确停止，不改数据库密码或覆盖密钥。

## 恢复顺序

使用**全新、空的**数据库和资产卷，不要覆盖现有卷。先选择与备份 Schema 和 Flyway history 兼容的应用镜像：旧 V1–V77 使用对应旧版本，合并后 V1 使用兼容的新版本。先恢复匹配的 `credentials-data`（上文），再启动独立目标项目的 `postgres`，将可信数据库归档以 `pg_restore --no-owner --no-privileges` 导入空库，再向该项目的资产卷解包。把对应的部署配置、模板、镜像/Git revision 和分开保管的密钥按版本恢复。在目标 Compose 的 server 环境中持久设置 `AGENVAS_RECOVERY_MODE: "true"`，并在不打印其他环境变量的前提下核对 `server` 的实际配置，再启动 Web/Server；不能只在首次命令临时设置，否则后续 `up`/重启可能回到正常调度。恢复模式跳过 Flyway 迁移和修复；尚未导入备份的空数据库会拒绝启动。它不保证任意旧 Schema 可由新二进制读取，也不是旧 history 升级到当前 V1 的入口。

```sh
docker compose -p "$COMPOSE_PROJECT_NAME" -f deploy/compose.yaml up -d postgres
docker compose -p "$COMPOSE_PROJECT_NAME" -f deploy/compose.yaml exec -T postgres \
  pg_restore -U agenvas -d agenvas --no-owner --no-privileges \
  < "$BACKUP_DIR/database.dump"
docker run --rm -v "${COMPOSE_PROJECT_NAME}_asset-data:/target" \
  -v "$BACKUP_DIR:/backup:ro" postgres:17.11-alpine \
  tar -C /target -xzf /backup/assets.tgz
# 确认目标 Compose 已持久设置恢复模式；只输出判定，不打印其他密钥。
docker compose -p "$COMPOSE_PROJECT_NAME" -f deploy/compose.yaml config --format json \
  | jq -e '.services.server.environment.AGENVAS_RECOVERY_MODE == "true"' >/dev/null
docker compose -p "$COMPOSE_PROJECT_NAME" -f deploy/compose.yaml up -d
```

使用兼容版本时，恢复模式下登录、项目、Artifact、Asset 与任务只读查询可用；项目及模型设置写操作返回 `503 RECOVERY_MODE_READ_ONLY`。全部后台调度器不注册，包括模型回合、媒体提交/轮询、资产转存/清理和过期提交扫描；Flyway 不迁移 Schema。它是**核对窗口**，不是自动修复：逐项比对数据库里的 Task/Provider attempt、资产文件及原 Provider 实例的请求状态。旧快照可能不知道备份之后发生的提交；不能批量把 `RUNNING`/`SUBMITTING` 改成 `READY`，不能因为历史查询暂时为空就重提。原端点或旧密钥不可用时保持阻断/UNKNOWN，并记录人工决定。

只有完成原请求、文件和密钥核对，确认恢复后的兼容版本调度不会重复计费，才可在维护窗口将 `AGENVAS_RECOVERY_MODE=false` 并重建/重启服务。当前尚无自动化的全量核对/放行命令；这一步必须由了解 Provider 状态的操作员决定。回滚应用之前还要核对旧二进制是否兼容已迁移的数据库 Schema。旧 V1–V77 的核对和放行仍使用旧版本，不允许因此切换到当前 V1。

GPT Image 2 与火山方舟 Seedance 连接共用数据库中的版本化媒体连接和上述密钥环。恢复时须保留每个被 Task 固定的连接/能力版本及相应旧主密钥，不能只恢复当前默认渠道。Seedance 的 `providerRequestId` 是原方舟任务 ID；恢复后只查询该 ID 并重取临时结果 URL。数据库备份里没有临时视频字节，已受理但未归档的任务须先核对原任务及 URL 有效期，过期又无法取得原结果时保持 BLOCKED，不创建第二个生成任务。GPT Image 2 同步请求如已发出但结果未入库且无法核对，应保持 UNKNOWN。这两个云渠道仅经本地假服务验证，真实 Provider 请求、灾备核对与密钥轮换演练均未运行。

个人资产库的 `library/` 文件必须随完整卷备份，项目导出只包含项目内内容，不导出账号资产库。恢复后先核对 `library_command` 固定输入、独立文件与 pin，再放行本地转存；本地复制重试不触发 Provider。清理队列会删除已确认无需保留的在线文件，备份归档按部署者的独立保留策略处理。完整资产库备份恢复演练尚未运行。

## 退役的开发迁移历史

以下记录仅帮助解释旧备份与旧版本的差异，不适用于当前合并 V1，也不提供旧库到新 V1 的升级路径：

- V34 起记录 ComfyUI 精确地址；更早备份缺失的旧地址不会由恢复程序推断。旧请求始终需要原实例和原请求身份核对。
- V36 曾给整秒镜头追加新版本并使待审批计划/导出提案失效，不自动取整小数秒、不改写已受理任务和历史用量。相关镜头/计划/导出产品范围已退役。
- V40 曾导入 V34 地址和当时的环境配置，保存不可变连接历史；不可映射任务进入 `LEGACY_UNRESOLVED`，不确定提交继续核对。该导入程序和标记表已删除，当前连接、能力与默认值仅通过管理员媒体目录配置。
- V51 曾执行 [ADR 0013](../adr/0013-contract-to-direct-generation.md) 的不可逆产品收缩，删除角色/场景/镜头数据并移除计划、关键帧与媒体导出表。其删除操作不会在当前空库基线重新执行；要恢复此前内容须保留对应旧 Schema、素材和旧镜像。
- V67–V70 曾新增个人资产库，保留既有项目内容并引入服务端专用 `LIBRARY_IMPORT` 来源。V66→V70 的隔离 PostgreSQL 迁移验收仍只说明当时的升级路径，不能作为合并 V1 的验收；当时的旧部署 Compose 升级也未完成演练。

2026-10-04 初始化升级：V9 为安装锁行增加永久完成时间，根据已有账号（含停用账号）回填。恢复时保留该行，删除/停用管理员不会重新开放初始化；未初始化的空库只在本机创建一次账号，再开放公网。此版本不再读取或生成 `AGENVAS_BOOTSTRAP_SECRET`。
