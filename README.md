# Agenvas

[English](README.en.md)

Agenvas 是一个可自托管的 AI 创作画布。目标是让 Agent 以可操作卡片存在于画布中，在明确的权限、审批、版本和恢复边界内生成三镜头短片。

当前仓库已完成 M0–M2 的基础链路，并实现可运行的 Mock 三镜头、分阶段审批、媒体归档、局部重做与无声导出纵向切片：Vite/React 前端、Spring Boot 模块化单体、PostgreSQL/Flyway、权威 OpenAPI、一次性管理员初始化、数据库会话、CSRF、项目与不可变内容版本、持久画布、Creator Agent、租约与 fencing epoch 任务、事务事件及可补发 SSE。真实 LLM/ComfyUI 接口和生产发布门禁仍未验收，不能把当前版本视为稳定 MVP 成品；逐项状态以[开发清单](docs/DEVELOPMENT-CHECKLIST.md)为准。

## 已实现的最小纵向切片

浏览器 `/setup` → CSRF 与 bootstrap secret 校验 → PostgreSQL 管理员创建 → `/login` → Spring Session JDBC。

- 空库由 Flyway 创建身份、初始化互斥锁与 Spring Session 基线表；20 路并发初始化集成测试只产生一个管理员。
- 前端 API 类型由 `contracts/openapi.yaml` 生成。
- Mock 媒体模式醒目标识；成功、失败和 UNKNOWN fixture 可重复。
- 未授权 API 默认返回 ProblemDetail；写请求需要 CSRF；登录会话在服务重启后仍可恢复。
- 登录后可创建、分页浏览、重命名和归档自己的项目；写操作使用乐观版本避免静默覆盖。
- 六类 Artifact 使用严格内容 Schema；修订只追加不可变版本，语义引用固定到同项目的明确历史版本。
- 项目工作区支持卡片放置、框选、拖拽、缩放、锁定、左对齐和适配视图；布局结束后原子保存，失败草稿保留在 Zustand。
- Creator Agent 是画布上的持久化卡片，可编辑名称/指令、明确绑定或清空选中 ArtifactVersion，并显示独立输出范围；创建卡片不会隐式读取全项目或启动模型。用户输入本次任务后先查看服务端返回的模型状态、精确输入和调用限额，再显式确认运行；配置变化会要求重新检查。停止入口及按 Agent 分页的运行记录、计划与任务摘要已接入。
- Run 创建固定输入与策略快照；同项目活动槽位由 PostgreSQL 行锁串行仲裁，同幂等键精确重放，同键异参冲突，终态释放槽位。
- Task 及依赖持久化在数据库中；Worker 通过 `SKIP LOCKED` 竞争短租约，旧 epoch 不能回写，网络处理在事务外执行，等待 Provider 时释放线程与租约。
- UNKNOWN 任务可按需查看持久提交账本的关联键、attempt 状态与已知 Provider 请求 ID。新 ComfyUI attempt 可显式查询原 prompt：仅在 ID、client ID、endpoint 指纹与配置匹配时恢复原请求轮询；查不到仍为 UNKNOWN，不自动重提。旧 attempt 的关联键不证明已受理，也不具备此自动核对能力。
- 画布侧栏可上传 PNG/JPEG/WebP 参考图：私有 Asset 按实际解码、20 MiB/40 MP 限制和 SHA-256 校验，原图及缩略图归档后才登记 READY；用户上传分支创建真实 IMAGE Artifact 卡片，不伪造生成 Task ID，可选中后绑定为 Agent 精确版本输入。生成视频归档使用 FFprobe/FFmpeg 验证 MP4 与首帧、500 MiB 上限，保存封面；任务键 MP4 归档可恢复。IMAGE/VIDEO 版本只引用同项目真实 Asset。按项目鉴权的原文件 GET/HEAD 支持单段 Range。图片原图、缩略图与 MP4 写入中途失败已有注入测试；真实物理磁盘耗尽及真实 Provider 仍未验证。
- 已批准的 Mock 图片任务由后台调度自动推进；每张演示图都经过持久化提交 checkpoint、Provider attempt 与真实 Asset 归档，并在图像及元数据中明确标记为演示素材。全新输出的 IMAGE Artifact 也会在同一业务事务内放到 Agent 输出组的画布空位，画布默认加载缩略图，原图由用户显式打开。
- 后台 Agent 回合调度会认领持久化 Task；默认确定性演示模型沿同一工具账本创建三个镜头、图片计划、视频计划和顺序导出提案。图片与视频分阶段人工审批；图片完成后逐镜头选定关键帧才提出视频计划。批准后的 Mock 视频从固定图片版本生成实际 H.264 MP4，明确标记非 AI 视频。导出提案固定镜头/视频版本及区间，但不会自动执行；用户在导出面板核对哈希并批准后才创建本地 FFmpeg 任务。模型响应与工具结果先入账，下一回合从账本重建；切换到 `AGENVAS_LLM_MODE=configured` 但未配置 ChatModel 时，Run 会进入 `BLOCKED`。
- Artifact、Canvas、Agent 与 Run 命令写入项目序号事件；事件失败会回滚对应命令。项目快照在同一 PostgreSQL `REPEATABLE READ` 事务中读取画布、Agent、活动 Run/Task 与事件水位。
- 一个项目由一个服务端事件轮询通道补发 SSE，客户端按序号去重并在缺口或过期时重取快照；每个连接的待发送队列与全局连接数都设有上限。
- 默认不需要模型 Key、GPU 或作者账户，不发起真实模型请求。

## 快速启动

需要 Docker Desktop 或兼容的 Docker Engine/Compose。

```sh
cp .env.example .env
# 编辑 .env，为数据库密码和一次性初始化密钥设置随机值。
./deploy/update-local.sh
```

以后在仓库根目录运行 `./deploy/update-local.sh`，即可拉取已锁定的基础镜像、重新构建本地镜像、更新 Compose 容器并等待健康检查。脚本使用仓库根目录的 `.env`，保留数据库和素材卷；构建失败时不会替换正在运行的容器。基础镜像固定了 digest，因此此命令不会自动升级到新的基础镜像版本。

这两项在 `.env.example` 中故意留空；缺失或未填写时 Compose 会拒绝启动。旧部署若使用过早期版本的公开回退值，不能只改 `.env` 中的数据库密码：应在维护窗口同步轮换 PostgreSQL 账户密码与服务端配置，并检查初始化密钥是否仍为已知示例值；不要把实际密钥写进工单、日志或 Git。

需要并行运行隔离验收实例时，可设置 `COMPOSE_PROJECT_NAME`、`AGENVAS_API_PORT` 和 `AGENVAS_WEB_PORT`。它们分别控制 Compose 项目/卷命名与仅绑定本机的 API、Web 端口；默认仍是 8080/8088。隔离实例也应使用独立的数据库密码与 bootstrap secret。

Compose 默认限制 PostgreSQL/server/web 分别使用 768 MiB/1 CPU、1536 MiB/2 CPU、256 MiB/0.5 CPU，并为各服务的 JSON 日志保留最多 3 个 10 MiB 文件。可在 `.env` 中用 `AGENVAS_*_MEMORY_LIMIT`、`AGENVAS_*_CPUS` 按实际机器容量调整；内存上限不是容量性能已验收的证明。server 停机等待最多 45 秒，应用优雅停机阶段为 30 秒；已提交的外部请求仍须按 Provider attempt 核对，不会因为等待期结束就安全重试。

打开 <http://127.0.0.1:8088/setup>，输入 `.env` 中的 `AGENVAS_BOOTSTRAP_SECRET` 创建管理员，然后在 `/login` 登录。也可以检查反代后的 API：

```sh
curl http://127.0.0.1:8088/api/v1/auth/setup-status
```

初始化前的预期响应：

```json
{"setupRequired":true}
```

停止服务：

```sh
docker compose --env-file .env -f deploy/compose.yaml down
```

该命令保留数据库和资产卷；如需清除测试数据，应明确使用 Compose 的卷删除选项，并确认没有需要保留的内容。

## 本地开发

前端要求 Node 24 LTS 与 pnpm 12.5.1：

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
```

后端要求 JDK 21；运行时需要 PostgreSQL：

```sh
cd backend
./mvnw verify
./mvnw spring-boot:run
```

本地 Vite 会把 `/api` 代理到 `http://localhost:8080`。默认数据库连接为 `jdbc:postgresql://localhost:5432/agenvas`，可通过 `AGENVAS_DB_URL`、`AGENVAS_DB_USER` 和 `AGENVAS_DB_PASSWORD` 覆盖；启动后端还必须提供至少 24 字符的 `AGENVAS_BOOTSTRAP_SECRET`。默认 `AGENVAS_LLM_MODE=mock` 运行无外部账户的确定性演示流程；演示视频还需要本机 FFmpeg/FFprobe（自动尝试 `/usr/bin`、Homebrew 路径，可用 `AGENVAS_MEDIA_TOOLS_FFMPEG` 和 `AGENVAS_MEDIA_TOOLS_FFPROBE` 指定绝对路径）。候选真实聊天接入需由部署者同时设置 `AGENVAS_LLM_MODE=configured`、`AGENVAS_LLM_CHAT_ADAPTER=openai`、`AGENVAS_LLM_MODEL`、`AGENVAS_LLM_API_KEY`，可选 `AGENVAS_LLM_BASE_URL`（默认官方 HTTPS 地址）及递增的 `AGENVAS_LLM_CONFIG_VERSION`。具体端点完成真实工具请求→回填→下一轮响应测试后，才设置 `AGENVAS_LLM_TOOL_CALLING_VERIFIED=true` 允许 Agent 运行；默认 false 会阻止把仅有适配器支持误报为模型能力。凭证仅进入服务端运行环境，不要写入仓库或前端配置；端点安全与发布门禁仍需实际验证。

管理员可在“模型配置”页保存 OpenAI 兼容端点、模型 ID 和 API Key。保存功能需要服务端设置 `AGENVAS_CREDENTIAL_MASTER_KEY`（32 字节随机密钥的 Base64 编码，独立于数据库备份保管）；未设置时配置写入返回 503，Mock 模式仍可运行。密钥在数据库中以 AES-256-GCM 加密并保留配置旧版本，API 只返回掩码。切换到 `AGENVAS_LLM_MODE=configured` 后，活动数据库配置优先于上面的环境变量候选适配器；保存后 Agent Run 仍会阻断，直到管理员在设置页明确确认最多两次可能计费请求，并完成“工具请求 → 服务端回填 → 下一轮响应”的诊断。只有当前配置版本通过诊断才开放 Tool Calling；这不证明视觉、输出质量或任何尚未实测的真实 Provider 能力。已创建 Run 固定配置来源与版本，轮换后不会静默改用新模型继续执行。数据库模型请求固定到管理员配置的主机/端口和 Chat Completions 路径，逐次校验 DNS 结果且不跟随重定向。不要将主密钥或 API Key 写入 Git、浏览器存储或日志。若明确需要本机测试端点，可设置 `AGENVAS_LLM_ALLOW_LOOPBACK_HTTP=true`，仅允许精确的 `http://127.0.0.1` 地址；默认不允许本机例外或 HTTP。

Google Nano Banana 2 图片能力也可在“媒体配置”页创建：先配置服务端 `AGENVAS_CREDENTIAL_MASTER_KEY`，再由管理员添加 `Google Gemini` 连接、填写 Google Gemini API Key、发布 `GOOGLE_NANO_BANANA_2` 能力，并在图片计划审批前选择它。固定模型为 `gemini-3.1-flash-image`，支持文字生图和单张已授权参考图；当前仅通过本地假 Google HTTP 服务及 PostgreSQL 验证，真实 Google 调用未运行。API Key 不要写入 `.env` 或聊天内容，应在管理员页面提交并独立保管主密钥。详见 [ADR 0004](docs/adr/0004-google-nano-banana-2-fixed-adapter.md)。

Compose 默认使用 Mock。管理员在“媒体配置”页创建连接；同一 ComfyUI 连接可发布固定图片和视频能力，另可发布 GPT Image 2 图片及火山方舟 Seedance 2 首帧视频能力，并分别设置默认能力。图片或视频计划的每个步骤都显示已选能力，审批前可逐项改选并确认；执行时使用固定的连接与能力版本。云渠道 API Key 加密保存在服务端，界面只显示掩码；GPT Image 2 固定生成/参考图编辑接口，Seedance 固定北京方舟 4–15 整数秒、单首帧、无声 MP4，结果只从已审核的方舟媒体域下载。两种云渠道目前仅通过本地假 HTTP 服务及 PostgreSQL 测试，真实付费调用均未运行，界面显示“未实测”。ComfyUI 只接受已安装的固定模板和模型文件名，不允许上传工作流。当前界面可配置的本机 ComfyUI 地址是精确的 `http://127.0.0.1:<端口>`；容器内的 `127.0.0.1` 不是宿主机，需要可达的显式地址白名单才能部署跨容器 ComfyUI。

从旧版本升级时，V40 首次启动把 V34 保存的 ComfyUI 地址登记为不可变历史连接版本，并将当前旧环境配置导入数据库一次。之后更改 `AGENVAS_PROVIDER_MODE`、地址或模型文件名不会覆盖管理员在数据库中的媒体连接、能力和默认值。已受理的旧请求仅按保存的原地址指纹与请求 ID 核对；无法唯一映射的旧任务保持 UNKNOWN 或标记 `LEGACY_UNRESOLVED`，不会自动重新提交。升级前备份数据库、资产卷和密钥，并在恢复模式核对活动请求。

轮换部署主密钥时，先分别备份数据库与旧主密钥，再生成新的 32 字节随机 Base64 值：把 `AGENVAS_CREDENTIAL_KEY_VERSION` 增加 1，令 `AGENVAS_CREDENTIAL_MASTER_KEY` 指向新值，并把旧值以 `旧版本号=旧Base64` 加入 `AGENVAS_CREDENTIAL_PREVIOUS_KEYS`（多把旧密钥用逗号分隔，例如仅描述格式的 `1=<旧值>,2=<更早值>`）。重启后新配置用新密钥加密，已保存版本仍用其原 keyVersion 解密；在旧 Run、未知任务及备份可能引用旧版本期间不得移除旧密钥。缺失历史密钥会明确返回 `CREDENTIAL_KEY_VERSION_MISSING`，不会改用新密钥尝试解密。数据库中已验证的旧 LLM 配置可供固定该版本的 Run 继续使用；环境变量来源没有历史版本存储，变更后旧 Run 仍明确阻断。此流程尚未完成跨备份恢复演练，不得宣称密钥轮换具备生产发布验收。

## 仓库结构

```text
frontend/       Vite + React + TypeScript SPA
backend/        Java 21 / Spring Boot 4 模块化单体
contracts/      权威 OpenAPI 与后续事件/Artifact Schema
configs/        版本化 Agent、Skill 与受信媒体工作流配置
deploy/         Compose、Nginx 和容器构建
docs/           MVP 规格、依赖基线与开发验收清单
```

核心规格见 [MVP-SPEC.md](docs/MVP-SPEC.md)，执行顺序见 [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md)，实际验证版本见 [dependency-baseline.md](docs/dependency-baseline.md)。贡献前请同时阅读 [AGENTS.md](AGENTS.md)。

旧备份恢复必须先以 `AGENVAS_RECOVERY_MODE=true` 启动；该模式不执行 Flyway 迁移，并暂停项目写入和后台调度。核对原外部请求、数据库与资产后，再决定何时迁移及恢复运行；操作顺序与本地演练边界见 [备份与恢复说明](docs/operations/backup-restore.md)。

## 当前限制

- 已实现单管理员身份闭环，但尚未提供账户找回、多管理员或团队能力。
- 已有 Run 创建、读取和取消 API，受控模型回合、工具账本、后台模型回合调度、只读运行历史，以及图片/视频计划的审批。默认 Mock LLM 可推进三镜头、图片审批、关键帧选择及视频审批，批准后的 Mock 图片和视频由后台 Worker 归档。可修订单个镜头并将共享场景新版本仅重绑该镜头，基于新镜头发起限定范围的 Mock Run，重新经历图片与视频审批。另有项目级无声顺序 MP4 导出、私有下载与脱敏项目 JSON/素材元数据清单；图片/视频 Task 已记录未定价用量的预留和唯一结算。ComfyUI 生图与图生视频候选模板已接入审批 Task、原请求核对与归档，并由假 HTTP 服务＋PostgreSQL 测试；真实模型/模板兼容、完整用量结算和发布门禁尚未完成。
- 没有真实 LLM/ComfyUI/GPT Image 2/Seedance/Nano Banana 2 调用；Spring AI 聊天适配器及固定媒体适配器只经假 HTTP 端点验证协议，本地 FFmpeg 仅用于演示视频编码及媒体验证，这些都不能证明真实模型 Provider 已接通。
- Flyway V1–V35 覆盖身份、项目、产物版本、画布、Agent/Run/Task、事件、工具账本、执行计划/审批、镜头关键帧选择、Provider 明确拒绝记录、私有 Asset/缩略图与视频时长、项目级导出任务、ComfyUI 持久单槽调度与历史地址版本、Provider 核对重试账本、媒体用量账本、版本化加密 LLM 配置、UNKNOWN 原请求核对标识、导出提案及只读任务队列统计索引；迁移仍只增不改。V35 前的视频 Asset 未自动回填时长，需重新归档后才能提交新的顺序导出。详见 `backend/src/main/resources/db/migration/`。

项目目标许可为 Apache-2.0；正式许可证、NOTICE 与第三方/模型许可证清单在 M6/T30 发布门禁完成前仍属于待办事项。安全报告边界见 [SECURITY.md](SECURITY.md)，当前支持范围与升级限制见 [0.1.0 发行说明草案](docs/release-notes/0.1.0-mvp-draft.md)。
