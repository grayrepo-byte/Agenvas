# 自托管备份与恢复（开发期操作说明）

本说明适用于 `deploy/compose.yaml` 的单机 Compose 部署。数据库是业务状态真相；数据库与资产卷必须来自同一个停止写入的时间窗。隔离 Mock 实例已完成数据库＋资产恢复演练；另有 PostgreSQL 集成测试验证加密配置行经 `pg_dump/pg_restore` 后可由恢复的测试密钥解密。**尚未**完成完整部署的密钥交接/轮换演练、真实 Provider 旧请求核对或生产 RPO/RTO 验收。

## 备份范围

1. PostgreSQL 数据库：项目、版本、Run/Task、Provider attempt、幂等账本、审批、事件与会话。
2. `asset-data` 卷：原图、视频、缩略图及任务键归档文件。
3. 当前部署的 `configs/`、`deploy/`、应用镜像或精确 Git revision、`.env` 中非密钥配置及模板/模型文件名和版本。
4. 与该数据库对应的 `AGENVAS_CREDENTIAL_MASTER_KEY`、`AGENVAS_CREDENTIAL_KEY_VERSION`、`AGENVAS_CREDENTIAL_PREVIOUS_KEYS` 和外部 Provider 凭证。密钥与数据库/资产备份**分开**加密存放，记录版本对应关系；不要把密钥放进项目导出或本仓库。

每天至少备份一次是初始建议，不代表已达到 RPO ≤24 小时或 RTO ≤2 小时。需要先完成正式恢复演练和计时。

## 一致性备份顺序

在维护窗口阻止新用户写入，记录当前版本和所有 `SUBMITTING`、`WAITING_PROVIDER`、`UNKNOWN` 任务及 Provider 原请求 ID；等待安全完成的短事务，对不能安全结束的外部提交保留不确定状态。停止该 Compose 项目的 `web` 与 `server`；**保持 `postgres` 运行**。确认应用容器已停止后，对同一项目执行 `pg_dump -Fc` 并将其 `asset-data` 卷打包。使用带日期、项目名和哈希的独立备份目录，并验证两个备份文件非空且可读取；把配置、模板版本与密钥版本清单一同保存。

以下命令仅展示经隔离演练验证的核心步骤。先把 `COMPOSE_PROJECT_NAME` 设置为**确切的目标项目名**，把 `BACKUP_DIR` 设置为新建、受限权限的具体目录；不要对默认项目或宽泛路径直接套用。

```sh
docker compose -f deploy/compose.yaml stop web server
docker compose -f deploy/compose.yaml exec -T postgres \
  pg_dump -U agenvas -d agenvas -Fc > "$BACKUP_DIR/database.dump"
docker run --rm -v "${COMPOSE_PROJECT_NAME}_asset-data:/source:ro" \
  -v "$BACKUP_DIR:/backup" postgres:17.11-alpine \
  tar -C /source -czf /backup/assets.tgz .
sha256sum "$BACKUP_DIR/database.dump" "$BACKUP_DIR/assets.tgz"
```

这里的 `BACKUP_DIR` 是本说明的任务专用变量，不要把主目录或仓库根目录当作备份输出目标。只有可信、由本部署产生的归档才可恢复；不要从不可信 tar 包提取文件到资产卷。

## 恢复顺序

使用**全新、空的**数据库和资产卷，不要覆盖现有生产卷。先启动新项目的 `postgres`，将可信数据库归档以 `pg_restore --no-owner --no-privileges` 导入空库，再向新项目的资产卷解包。把对应的部署配置、模板、镜像/Git revision 和分开保管的密钥按版本恢复。恢复旧数据库时必须在目标 `.env` 中持久设置 `AGENVAS_RECOVERY_MODE=true`，确认该值进入 `server` 容器，然后才启动 Web/Server；不能只在首次命令临时设置，否则后续 `up`/重启可能回到正常调度。恢复模式跳过 Flyway 迁移和修复；空数据库会拒绝启动。旧 Schema 可能无法满足新二进制的只读查询，因此仍应保留备份对应的应用镜像，并逐项验证所需读取入口。

```sh
docker compose -f deploy/compose.yaml up -d postgres
docker compose -f deploy/compose.yaml exec -T postgres \
  pg_restore -U agenvas -d agenvas --no-owner --no-privileges \
  < "$BACKUP_DIR/database.dump"
docker run --rm -v "${COMPOSE_PROJECT_NAME}_asset-data:/target" \
  -v "$BACKUP_DIR:/backup:ro" postgres:17.11-alpine \
  tar -C /target -xzf /backup/assets.tgz
AGENVAS_RECOVERY_MODE=true docker compose -f deploy/compose.yaml up -d
```

恢复模式下登录、项目、Artifact、Asset 与任务只读查询可用；项目及模型设置写操作返回 `503 RECOVERY_MODE_READ_ONLY`。全部后台调度器不注册，包括模型回合、媒体提交/轮询、导出和过期提交扫描；Flyway 不迁移 Schema，也不登记 ComfyUI 配置版本。它是**核对窗口**，不是自动修复：逐项比对数据库里的 Task/Provider attempt、资产文件及原 Provider 实例的请求状态。V34 起的数据库备份包含旧 ComfyUI 精确地址记录，但原实例仍须可达；V34 之前从未登记的旧地址不会由恢复程序推断。旧快照可能不知道备份之后发生的提交；不能批量把 `RUNNING`/`SUBMITTING` 改成 `READY`，不能因为历史查询暂时为空就重提。原端点或旧密钥不可用时保持阻断/UNKNOWN，并记录人工决定。

只有完成原请求、文件和密钥核对，确认恢复后的调度不会重复计费，才可在维护窗口将 `AGENVAS_RECOVERY_MODE=false` 并重建/重启服务。当前尚无自动化的全量核对/放行命令；这一步必须由了解 Provider 状态的操作员决定。回滚应用之前还要核对旧二进制是否兼容已迁移的数据库 Schema。
