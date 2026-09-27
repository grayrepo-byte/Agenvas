# 自托管备份与恢复（开发期操作说明）

本说明适用于 `deploy/compose.yaml` 的单机 Compose 部署。数据库是业务状态真相；数据库与资产卷必须来自同一个停止写入的时间窗。隔离 Mock 实例已完成数据库＋资产恢复演练；另有 PostgreSQL 集成测试验证加密配置行经 `pg_dump/pg_restore` 后可由恢复的测试密钥解密。**尚未**完成完整部署的密钥交接/轮换演练、真实 Provider 旧请求核对或生产 RPO/RTO 验收。

## 备份范围

1. PostgreSQL 数据库：项目、版本、Run/Task、Provider attempt、媒体连接及不可变能力版本、历史 origin 映射、幂等账本、审批、事件与会话。
2. `asset-data` 卷：原始图片、视频、视频封面帧及任务键归档文件。
3. 当前部署的 `configs/`、`deploy/`、应用镜像或精确 Git revision、`.env` 中非密钥配置及模板/模型文件名和版本。
4. 与该数据库对应的 `AGENVAS_CREDENTIAL_MASTER_KEY`、`AGENVAS_CREDENTIAL_KEY_VERSION`、`AGENVAS_CREDENTIAL_PREVIOUS_KEYS` 和外部 Provider 凭证。媒体连接与 LLM 连接的历史密文都可能引用旧密钥版本；密钥与数据库/资产备份**分开**加密存放，记录版本对应关系；不要把密钥放进项目导出或本仓库。

每天至少备份一次是初始建议，不代表已达到 RPO ≤24 小时或 RTO ≤2 小时。需要先完成正式恢复演练和计时。

## 一致性备份顺序

在维护窗口阻止新用户写入，记录当前版本和所有 `SUBMITTING`、`WAITING_PROVIDER`、`UNKNOWN` 任务及 Provider 原请求 ID；等待安全完成的短事务，对不能安全结束的外部提交保留不确定状态。停止该 Compose 项目的 `web` 与 `server`；**保持 `postgres` 运行**。确认应用容器已停止后，对同一项目执行 `pg_dump -Fc` 并将其 `asset-data` 卷打包。使用带日期、项目名和哈希的独立备份目录，并验证两个备份文件非空且可读取；把配置、模板版本与密钥版本清单一同保存。

仓库提供显式项目名的备份命令；先确认准确的 Compose 项目名和该部署的 env 文件。输出必须是仓库外、父目录已存在的**全新绝对路径**，脚本不会覆盖旧备份。它检查目标 PostgreSQL 和资产卷，记录原本运行的 Web/Server，停止写入后导出数据库、资产卷及仓库中的 `configs/`、`deploy/` 模板，校验归档可读取并写入 SHA-256 清单，最后恢复原本运行的服务。维护窗口内仍应先执行上文所述的外部请求核对和写入隔离；脚本不能替代 Provider 对账。

```sh
deploy/backup-compose.sh <确切Compose项目名> <该项目env文件> <全新绝对备份目录>
```

脚本需要 Docker Compose、`jq`、`sha256sum`、`tar` 和 `git`；`manifest.json` 包含项目名、Git revision、时间和归档哈希，但**不包含** `.env`、主密钥、历史密钥、Provider 凭证，也不保证构建镜像、仓库外模板或模型权重已备份。上述内容须另行加密保管并与此清单关联。脚本失败时尝试恢复原本运行的 Web/Server，失败目录不可作为可恢复备份；必须人工确认服务状态。只有可信、由本部署产生的归档才可恢复；不要从不可信 tar 包提取文件到资产卷。

## 恢复顺序

使用**全新、空的**数据库和资产卷，不要覆盖现有生产卷。先启动新项目的 `postgres`，将可信数据库归档以 `pg_restore --no-owner --no-privileges` 导入空库，再向新项目的资产卷解包。把对应的部署配置、模板、镜像/Git revision 和分开保管的密钥按版本恢复。恢复旧数据库时必须在目标 `.env` 中持久设置 `AGENVAS_RECOVERY_MODE=true`，并在不打印其他环境变量的前提下核对 `server` 的实际配置，再启动 Web/Server；不能只在首次命令临时设置，否则后续 `up`/重启可能回到正常调度。恢复模式跳过 Flyway 迁移和修复；空数据库会拒绝启动。旧 Schema 可能无法满足新二进制的只读查询，因此仍应保留备份对应的应用镜像，并逐项验证所需读取入口。

```sh
docker compose --env-file .env -f deploy/compose.yaml up -d postgres
docker compose --env-file .env -f deploy/compose.yaml exec -T postgres \
  pg_restore -U agenvas -d agenvas --no-owner --no-privileges \
  < "$BACKUP_DIR/database.dump"
docker run --rm -v "${COMPOSE_PROJECT_NAME}_asset-data:/target" \
  -v "$BACKUP_DIR:/backup:ro" postgres:17.11-alpine \
  tar -C /target -xzf /backup/assets.tgz
# 确认目标 .env 已持久设置恢复模式；只输出判定，不打印其他密钥。
docker compose --env-file .env -f deploy/compose.yaml config --format json \
  | jq -e '.services.server.environment.AGENVAS_RECOVERY_MODE == "true"' >/dev/null
docker compose --env-file .env -f deploy/compose.yaml up -d
```

恢复模式下登录、项目、Artifact、Asset 与任务只读查询可用；项目及模型设置写操作返回 `503 RECOVERY_MODE_READ_ONLY`。全部后台调度器不注册，包括模型回合、媒体提交/轮询、导出和过期提交扫描；Flyway 不迁移 Schema，也不登记 ComfyUI 配置版本。它是**核对窗口**，不是自动修复：逐项比对数据库里的 Task/Provider attempt、资产文件及原 Provider 实例的请求状态。V34 起的数据库备份包含旧 ComfyUI 精确地址记录，但原实例仍须可达；V34 之前从未登记的旧地址不会由恢复程序推断。旧快照可能不知道备份之后发生的提交；不能批量把 `RUNNING`/`SUBMITTING` 改成 `READY`，不能因为历史查询暂时为空就重提。原端点或旧密钥不可用时保持阻断/UNKNOWN，并记录人工决定。

只有完成原请求、文件和密钥核对，确认恢复后的调度不会重复计费，才可在维护窗口将 `AGENVAS_RECOVERY_MODE=false` 并重建/重启服务。当前尚无自动化的全量核对/放行命令；这一步必须由了解 Provider 状态的操作员决定。回滚应用之前还要核对旧二进制是否兼容已迁移的数据库 Schema。

从 V35 或更早版本升级到 V36 时，先按上述顺序备份数据库、资产卷和密钥，再启动新版本执行 Flyway。V36 只追加整秒镜头的新内容版本，保留旧版本和引用；旧关键帧选择仍指旧版本，待审批媒体计划及导出提案失效。1250 毫秒等小数秒镜头不自动取整，需用户明确修订后重建计划。已受理任务和历史用量不改写；在恢复模式核对原请求 ID 后再放行调度，不能因任务仍含 v1 毫秒字段而重新提交生成。

升级到 V40 时，先保存 V34 的 `comfyui_config_version`、Provider attempt 与原服务地址。首次正常启动在媒体调度前执行一次导入并写入完成标记；旧待审批计划失效，不能可靠映射的未提交媒体任务进入 `LEGACY_UNRESOLVED`，不确定提交仍待核对。导入后数据库是媒体配置真相，不能通过更改旧环境变量修复或覆盖历史版本。恢复模式不会执行导入；核对原请求与密钥后再允许正常启动。

GPT Image 2 与火山方舟 Seedance 连接共用数据库中的版本化媒体连接和上述密钥环。恢复时须保留每个被 Task 固定的连接/能力版本及相应旧主密钥，不能只恢复当前默认渠道。Seedance 的 `providerRequestId` 是原方舟任务 ID；恢复后只查询该 ID 并重取临时结果 URL。数据库备份里没有临时视频字节，已受理但未归档的任务须先核对原任务及 URL 有效期，过期又无法取得原结果时保持 BLOCKED，不创建第二个生成任务。GPT Image 2 同步请求如已发出但结果未入库且无法核对，应保持 UNKNOWN。这两个云渠道仅经本地假服务验证，真实 Provider 请求、灾备核对与密钥轮换演练均未运行。
