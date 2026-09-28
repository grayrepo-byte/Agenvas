# T20 ComfyUI 通信边界：部分进展

2026-09-23。仓库新增了未经过真实模型验证的 `image-v1` 候选模板；随后增加默认关闭的 `image-to-video-v1` 候选模板（见 `T23-comfyui-video-candidate.md`）。当前本机未发现监听 8188 的 ComfyUI。此证据**不证明真实生图或图生视频已完成**。

2026-09-28 更新：本文下述 V23 单槽行为是当时的历史证据，已由 V56 删除。当前 ComfyUI 没有全局产品并发门禁；`ComfyUiImagePostgresIT` 已改为验证第一个请求仍活动时第二个任务也能提交。真实 ComfyUI 的并发资源表现仍未验证。

新增 `ComfyUiClient`，仅在 `agenvas.provider.mode=comfyui` 时启用。服务端配置精确 IPv4 origin，不接受用户或模型传入 URL；拒绝 DNS 主机、userinfo、路径、查询和片段，拒绝链路本地地址，公共地址需要 HTTPS，关闭 HTTP 代理与重定向。协议只访问固定 `/prompt`、`/history/{prompt_id}`、`/upload/image` 和 `/view` 路由。提交返回的 prompt_id 被解析为 UUID；查询空历史保留“尚未确定”语义，不触发重提。上传文件名由服务端生成并校验返回值；下载只允许安全根目录文件名，流式读取且有 500 MiB 上限。5xx/读写中断标成不确定传输错误，不能当作确定失败自动重试提交。`ComfyUiHistory` 对原 prompt_id 与模板固定输出节点解析历史：空历史仍等待，记录的执行错误（包括 `completed=false`）是确定失败，成功时只接受根输出目录的一张安全图片；不信任任意输出路径。

`ComfyUiClientTest` 使用本地假 HTTP 服务覆盖提交/原 ID 查询、图片上传、同源输出、重定向拒绝、5xx 分类和危险 endpoint 拒绝；`ComfyUiHistoryTest` 覆盖空历史、完成/失败和不安全路径。持久任务层只认领已记录 providerRequestId 的 WAITING_PROVIDER 核对租约，支持延后核对和按 lease epoch 归档，不经过新提交检查点；`TaskRecoveryPostgresIT` 与 `TaskStaleShotPostgresIT` 在真实 PostgreSQL 验证重复核对不会增加 provider_attempt，旧轮询者不能写入，核对后的归档可完成原任务，取消后的晚到结果留在历史。

`PlanWorkflowPolicy` 在 ComfyUI 模式放行固定 `image-v1` 候选模板；视频阶段仅在管理员启用并配置四个模型文件名后放行固定 `image-to-video-v1` 候选模板。模板哈希和管理员模型文件名纳入审批版本。`ComfyUiImageWorker` 在审批后的持久 SUBMITTING checkpoint 之后，把钉住的同项目参考图 Asset 上传并填入 `LoadImage → VAEEncode → KSampler` 固定路径；无参考图则使用本地空白图，随后提交一次，保存 prompt_id，后台轮询原 ID，下载并通过 AssetService 归档。V23 `provider_dispatch_gate` 行锁保护跨应用实例的一个 ComfyUI 外部槽位；已受理的轮询任务即使租约过期，也不会被新提交 Worker 认领。图像轮询器不认领视频任务；配置版本变化时保留原 requestId 并将旧任务明确置为 BLOCKED，而不是用新模板查询或重新提交。`ComfyUiImagePostgresIT` 用假 HTTP 服务和真实 PostgreSQL 验证审批前零提交、参考图与无参考图映射、空历史等待、原 ID 归档、两个竞争提交者只成功一个、下个任务等待槽位及配置漂移阻断；**没有实际 GPU/模型生成**。候选模板的哈希与缺失发布信息见 `docs/comfyui-image-v1-candidate.md`。

归档恢复补充验证：`ComfyUiImagePostgresIT` 在原 prompt 的历史报告完成后，首次 `/view` 返回不可解码字节；本地图片验证拒绝该输出，数据库没有为坏图片创建 Asset，Task 保留原 providerRequestId。V24 `task_provider_poll_retry` 记录连续技术失败；前五次查询/下载/归档失败按带抖动的指数退避核对原请求，第六次转 BLOCKED 并保留 requestId，不重提生成。成功查询或归档清零连续失败计数。`TaskRecoveryPostgresIT` 验证五次退避、第六次阻断和 provider_attempt 始终只有一条；假 ComfyUI 测试验证失败后再次下载有效 PNG、归档成功且清零账本。ComfyUI 明确的协议/4xx 错误直接 BLOCKED，不进入技术重试。`AssetService.archiveTaskImage` 使用由 Task ID 派生的固定资产 ID；已有 READY 资产会重新校验本地字节哈希并复用，不再次下载。没有 READY 记录时，先检查该任务固定路径上的原图与缩略图，核对图像解码、大小、哈希后补齐记录；缺失的缩略图可从原图重建。归档操作由共享本地卷上的任务锁文件串行化，不在下载期间持有数据库事务。`AssetPostgresIT` 在真实 PostgreSQL 与本地文件卷上模拟原图已经安装、缩略图和 READY 行尚未完成的崩溃窗口，验证恢复与再次调用均不触发下载，也不创建第二个 Asset；同进程双 Worker 竞争测试验证只下载一次、只写一个 READY Asset 与一条 asset.ready 事件。这些测试使用假 HTTP/媒体字节，尚未进行独立进程共享卷的故障注入，不证明真实 ComfyUI 的归档可靠性。

仍需：真实 ComfyUI 与模型联调并冻结版本/模型许可证、参考图真实输出验证、持久化文件恢复的多进程/交错写入故障注入、已受理任务的跨实例故障注入、视频固定模板真实运行、Key 配置与诊断。清单 T20–T23 继续未勾选。

2026-09-23 恢复关联补充：新的 ComfyUI 图片/视频提交把已在 `provider_attempt` checkpoint 中持久化的 `request_key` 同时放入 `client_id` 与调用方指定的 `prompt_id`。只有回执返回完全相同的 UUID 才进入 WAITING_PROVIDER；不一致/无效回执按歧义提交保留，不能重发。假 HTTP＋真实 PostgreSQL 集成测试验证请求体、账本关联键与已保存的 Provider ID 一致；单元测试验证不一致回执只发出一次请求。所依据的 [ComfyUI 官方服务器实现](https://github.com/Comfy-Org/ComfyUI/blob/master/server.py)接收经 UUID 校验的调用方 `prompt_id`，[官方示例](https://github.com/Comfy-Org/ComfyUI/blob/master/script_examples/websockets_api_example.py)也发送该字段。这里没有验证目标 ComfyUI 实例/版本，也没有实现 UNKNOWN 的原请求自动核对；历史为空仍不能证明没有执行，`prompt_id` 仍不是幂等键。

本改动实际检查：`./mvnw -q -Dtest=ComfyUiClientTest -Dit.test=ComfyUiImagePostgresIT,ComfyUiVideoPostgresIT verify` 通过；首次 `./mvnw -q verify` 中一个无关的 `StoryboardToolsPostgresIT` 在 PostgreSQL 连接读取超时时未能加载上下文，单独重跑通过；随后第二次 `./mvnw -q verify` 通过，报告合计 109 个测试、0 failure、0 error。`git diff --check` 通过。未进行真实 ComfyUI/GPU 调用。

V29/V30 后续：`provider_attempt` 追加可为空的 `candidate_request_id` 与 `candidate_origin_sha256`，旧记录保持 NULL，不会被误当成可按随机关联键核对。新 ComfyUI 提交记录精确 endpoint 指纹。`ComfyUiClient.originalPromptExists` 只读查询指定 ID 的历史和当前队列，并核验条目的 prompt/client ID；空结果不判定失败。项目授权后的核对 API 只在工作流配置及原 endpoint 指纹一致、Provider 返回同一身份时，把原 Task 与 attempt 原子恢复为 `WAITING_PROVIDER`/`ACCEPTED`，Run 无其他阻断任务时回到 `WAITING_TASKS`；查询外部不持有数据库事务。假 HTTP＋PostgreSQL 覆盖空历史/队列、身份不匹配、endpoint 漂移、旧 attempt、取消和无新增提交；还未在实际 ComfyUI 版本上测试此语义。首次完整 `./mvnw -q verify` 的 109 项结果发生于 V29/V30 之前，不能作为此补充的全量验证证据。

V30 后完整验证：`./mvnw -q verify` 在更新九处 Flyway 最新版本断言后通过，Surefire/Failsafe XML 合计 113 个测试、0 failure、0 error；前端 28 个测试、类型检查、lint 与生产构建通过。首次全量运行的九处 V28 断言失败已记录并修正，不据此声称首次运行通过。真实 ComfyUI/GPU、历史清理后的人工对账与跨实例生产演练仍缺失。

恢复 CAS 后续增加 Run 非取消/非终态条件，避免 Run 状态与 Task 标志竞争时恢复旧请求；最终代码状态再次完成全量 `./mvnw -q verify`，113 个测试均通过。
