# T25 顺序导出：当前部分验收

2026-09-23。此记录只描述已落地行为，不表示 M5 已完成。

## 已实现

- 项目级 `MEDIA_EXPORT` Task 不依附已结束的 Agent Run；V22 迁移只允许这种 Task 的 `run_id` 为空。一个项目幂等键只绑定一个导出输入，重放相同输入返回原 Task，不同输入返回冲突。
- 提交时固定有序 VIDEO ArtifactVersion、归档 Asset ID/SHA-256、毫秒裁剪区间、项目画幅；最多 6 段、总长 60 秒。Worker 从归档文件读取，FFmpeg 通过固定二进制及参数数组执行，按项目画幅等比例适配并补边，规范为无声 720p/24fps/H.264 MP4，最终作为私有 Asset 归档。
- 前端可选择当前视频版本、上下移动、设置区间、提交、查看持久状态、取消及从同源受保护 URL 下载。默认 5 秒终点只是编辑初值，用户需核对实际时长；越过素材时长会由 Worker 判失败。
- 本地导出使用租约、epoch、心跳与取消检查；导出进程有超时和输出大小上限。临时文件在正常与异常路径尝试清理。

2026-09-24 崩溃残留清理补充：导出工作目录现持有跨进程 `.active.lock` 文件锁，编码/归档结束后释放并尝试立即删除。新的每小时维护任务仅检查项目 UUID 目录下超过 24 小时的 `.export-*`，只在锁可非阻塞取得且目录仅有本应用已知文件时删除；单次最多清理 100 个。`LocalAssetStorageScratchTest` 覆盖旧孤儿删除、仍活动任务不删除、近期目录不删除、未知内容不删除及稳定资产保留；`ExportScratchJanitorTest` 验证 24 小时水位与批量上限；`MediaExportPostgresIT` 验证 FFmpeg 导出路径仍可完成。后端 `./mvnw -q verify` 与最后新增的定向 Janitor 测试通过。未模拟真实杀进程后等待 24 小时，也未注入真实磁盘写满；T25 的故障验收仍未勾选。

## 验证与限制

2026-09-24 画幅补验：`MediaExportPostgresIT` 在真实 PostgreSQL 与 FFmpeg 上增加横向 640×360/15fps 输入导出到纵向 720×1280，以及纵向 360×640/30fps 输入导出到方形 720×720。两者输出均为 24fps；重新解码的中心像素保留来源红/蓝主体，纵向输出顶部和方形输出左侧均为黑色补边，证明这两类默认等比例适配没有中心裁切。对应定向测试返回 0；这不是浏览器播放器的端到端验收。

最终源码的 `./mvnw --batch-mode --no-transfer-progress -q verify` 返回 0；`MediaExportPostgresIT` 的 Failsafe 报告为 1 test、0 failures、0 errors，当前 Surefire/Failsafe 共 111 个测试套件报告无失败/错误，`git diff --check` 无输出。本轮没有迁移、API 合约或前端改动，未运行前端测试；仍未进行真实浏览器播放或磁盘满演练。

- `MediaExportPostgresIT` 使用 PostgreSQL 17 Testcontainers 和本机 FFmpeg，测试两段不同分辨率/帧率素材拼接、1280×720/24fps/约 2 秒输出，以及首段提交后改为蓝色视频仍导出旧红色版本；还测试幂等键冲突、排队取消。
- 新增运行中取消和工具故障注入：受控编码器在任务已认领后观察取消，Task 最终为 CANCELED、无新增 READY Asset、导出临时目录清除；模拟编码器超时/磁盘类非零故障时 Task 为 FAILED/`EXPORT_TOOL_FAILED`、无 READY Asset、临时目录清除。`MediaToolRunnerTest` 用固定本地进程验证取消、超时后强制终止并等待退出，以及导出非零退出不被误分类为“输入素材无效”。这是受控故障注入，不等同于真实磁盘满或浏览器播放测试。
- 本轮运行 `./mvnw -q test`、`./mvnw -q -Dtest='*IT' test`（22 个 PostgreSQL 集成测试）、导出单独集成测试；均通过。前端运行本地 `tsc --noEmit`、`eslint . --max-warnings=0`、`vitest run`（16 个测试）、`vite build`；均通过，构建提示单个 JS chunk 超过 500 kB。首次全量集成测试发现旧迁移版本断言及 MockMvc 异步流读取竞态，修正后重跑全套通过。
- 尚未做真实浏览器播放器端到端检查、真实磁盘满故障注入、导出进程崩溃恢复与旧临时目录清扫、不同画幅回归测试。不能据此勾选 T25 的全部验收项。
- 该阶段只实现顺序视频导出；后续 T26 项目 JSON/素材清单及用量显示、Mock 局部重做已另行落地。真实 ComfyUI 视频与完整真实 Provider 三镜头闭环仍未验证。

2026-09-23 补充：`MockStoryboardPostgresIT` 在同一个 PostgreSQL 项目中完成三镜头规划、图片审批与生成、逐镜头关键帧选择、视频审批与生成后，按镜头顺序固定三段 VIDEO 版本并创建项目导出。真实本机 FFmpeg 输出约 3 秒的 1280×720 H.264 无声 MP4，归档为私有 Asset；同一测试通过受保护 HTTP Range 下载原文件 `ftyp` 字节，并确认未登录请求返回 401。`./mvnw -q -Dtest=MockStoryboardPostgresIT test` 已通过。首次 `./mvnw -q verify` 在其他测试的 PostgreSQL 容器启动期间遇到连接读超时；失败的 `ComfyUiImagePostgresIT` 单独重跑通过，第二次完整 `verify` 退出码为 0。此环境偶发连接故障仍需关注。这是 Mock 媒体＋真实 PostgreSQL/FFmpeg 的黄金路径，不等于真实 LLM/ComfyUI 或浏览器下载验收。

## 合约与迁移影响

`contracts/openapi.yaml` 新增项目导出的创建、列表、详情和取消路径，以及输入区间类型；生成的 `frontend/src/shared/api/schema.ts` 已同步。`Task.runId` 从必填非空改为必填可空，仅项目级 `MEDIA_EXPORT` 为 null；现有客户端若无条件解引用该字段需要调整。服务仍为 `/api/v1`、开发阶段 0.1.0，不承诺旧客户端兼容。V22 只追加迁移，不改写旧版本。

2026-09-24 导出提案前置校验补充：`MediaExportService.preview` 现复用手动导出的同一套项目权限、视频版本、归档 Asset、段数、区间、总时长和画幅校验，返回精确输入快照及项目 CAS 版本，但不创建 Task 或启动 FFmpeg。手动 `create` 在幂等重放检查后使用该预览结果入队。扩展的 `MediaExportPostgresIT` 验证预览时项目无 Task，随后创建 Task 的快照与预览一致。Agent `propose_export` 的持久提案、人工确认入口与 UI 尚未实现；不能将这个预览方法称为已完成导出提案。无 HTTP 合约或 Flyway 迁移变化。

该预览切片的 `MediaExportPostgresIT` 定向测试与后端 `./mvnw -q verify` 均退出码 0；`git diff --check` 退出码 0。未重跑前端或浏览器测试，亦未进行真实 Provider 调用。

2026-09-24 Task 键导出恢复补充：导出 Worker 现使用 `AssetService.archiveTaskVideo`，以导出 Task ID 导出固定资产 ID。重新认领时先校验已归档原 MP4、海报、大小及 SHA-256；若同一 Task 已有 READY 资产，则不创建临时工作目录、不调用 FFmpeg，直接将该 Asset ID 写入原 Task 的成功结果，不另建随机 Asset。首次编码的目录与锁由归档输入流持有，归档读完或失败关闭流时再清理。新增 PostgreSQL/真实 FFmpeg 测试在“MP4 已归档但 Task 尚未成功”窗口使旧租约过期，新 Worker 使用禁止编码的假执行器仍完成同一 Task；核对资产总数不增加、用量只结算一次、无临时工作目录。最终代码上的 `./mvnw -q -Dit.test=MediaExportPostgresIT verify` 与 `./mvnw -q verify` 均通过。未进行杀进程后实际重启或真实磁盘满演练，因此 T25 总验收仍未完成。

2026-09-24 浏览器播放补验：导出记录现仅在已完成且有私有 Asset 时显示“播放导出”，用户点击后才挂载带 controls/metadata preload 的 `<video>`，关闭预览即卸载；原“下载 MP4”入口保留。`MediaExportPanel.test.tsx` 验证默认不加载、点击后使用同源受保护 Asset URL、关闭后卸载。隔离 `agenvas-export-playback-e2e` Compose 项目从当前工作树构建，postgres/server/web 健康；`AGENVAS_E2E_REDO=1 AGENVAS_E2E_EXPORT=1 node frontend/e2e/manual-storyboard-browser.mjs` 在真实 Chrome 153 中完成 Mock 单镜头视频生成后，手动选择其精确版本、设置 0–0.5 秒区间、提交本地 FFmpeg 导出。浏览器经已认证 API 核对 `MEDIA_EXPORT` 成功、输入仅该精确版本、私有下载链接可见、HEAD 为 `200 video/mp4` 且长度非零；预览点击前无 `<video>`。脚本用真实指针点击“播放导出”后读取到 1280×720、非零时长，再启动播放并观察 `currentTime > 0.05`、可用媒体数据且无媒体错误；第一次仅用合成 DOM 点击时 Chrome 拒绝无用户激活的 `play()`，改为真实指针手势后通过。脚本输出 `T25 browser smoke passed: manual export, private download and MP4 playback`，退出码 0。`npm run typecheck`、`npm run lint`、`npm test`（19 文件/69 项）和容器前端生产构建均通过；本轮未修改后端、合约或迁移，后端 `verify` 未重跑。此检查是单段 Mock 素材的浏览器播放，不替代多分辨率混合输入的浏览器端到端、真实磁盘满或杀进程恢复演练。

2026-09-24 子进程边界补验：`MediaToolRunner` 现为每个媒体子进程显式设置工作目录；导出使用本任务在资产卷中的私有锁定 `.export-*` 目录，其他探测/缩略图进程使用 JVM 指定的本地临时目录。导出拒绝不存在、相对或符号链接工作目录。启动进程前清空继承环境，只设置工作目录对应的 `TMPDIR`，避免把数据库、bootstrap 或 Provider 密钥传给 FFmpeg/ffprobe。`MediaToolRunnerTest` 用真实 `touch` 子进程证明相对输出只落在指定目录，用 `printenv PATH` 证明应用环境没有被继承，并保留取消、超时、非零退出测试；`MediaExportPostgresIT` 用真实 PostgreSQL 与 FFmpeg 通过。最终源码的后端 `./mvnw --batch-mode --no-transfer-progress -q verify` 退出码 0，Surefire/Failsafe 报告未发现失败或错误，`git diff --check` 退出码 0。本轮未改 HTTP 合约、迁移或前端；未跑新的浏览器测试，也未执行真实磁盘满或进程崩溃演练。

2026-09-24 导出归档故障补验：`AssetDiskFullPostgresIT` 现让真实 FFmpeg 完成一段 MP4 导出，再对归档目标写入实施一次 16 字节后中断的 ENOSPC 等价注入。失败 Task 不出现 READY Asset、`asset.ready` 事件或用量结算，预留释放一次，导出工作目录与部分写入文件清除；解除注入后的新导出成功。定向测试与后端全量 `verify` 均退出码 0。见 `docs/evidence/T27-disk-full-injection.md`；它不替代物理磁盘满或杀进程演练。

2026-09-24 真实服务进程中断补验：`MediaExportPostgresIT` 使用同一个 Testcontainers PostgreSQL 和私有资产目录，打包后的 Spring Boot 应用作为独立 JVM 认领六段、总长 15 秒的实际 FFmpeg 导出。测试等待 Task 已为 RUNNING 且 `.export-*/silent-export.mp4` 已写出非零字节，强制杀掉第一服务进程；此时 Task 仍为 RUNNING、无 READY 导出 Asset。启动第二个真实服务进程后，在原租约过期时重新认领，epoch 增加，同一 Task 最终 SUCCEEDED；任务键 Asset、`asset.ready` 事件和用量结算各只有一份。测试再用已实现的 Janitor 清理旧进程留下的无锁临时目录，并确认目录消失。定向 `./mvnw --batch-mode --no-transfer-progress -q -Dit.test=MediaExportPostgresIT verify` 与加入该场景后的后端全量 `./mvnw --batch-mode --no-transfer-progress -q verify` 均退出码 0。该演练强杀本机子 JVM，并非 Compose 容器或宿主机整体崩溃；没有证明所有 OS 上孤儿 FFmpeg 子进程立即终止，也未做物理磁盘满测试。

2026-09-24 视频时长与导出预检补验：V35 在新归档视频的 Asset 上保存 FFprobe 已验证的 `duration_ms`，图片为 null；历史视频行可为 null，手动导出将明确拒绝这种缺少可验证时长的旧素材，需要重新归档。新增受项目鉴权的 `GET /api/v1/projects/{projectId}/assets/{assetId}` 只返回元数据，不暴露存储键。手工导出编辑器选择视频后按需读取元数据，把默认终点限制为片源时长与 5 秒的较小值，显示片源时长，并在编辑时禁用越界提交；服务端 `MediaExportService.preview` 对手工提交及 Agent 导出提案复用同一时长上限检查，越界在创建 Task 前返回 `EXPORT_INPUT_INVALID`。`AssetPostgresIT` 验证 1 秒视频时长、私有元数据 401/404 与不泄露对象键；`MediaExportPostgresIT` 验证越界及旧素材缺时长被拒绝；前端测试验证 2 秒/1.25 秒片源的默认区间、越界禁用与旧素材提示。五组 PostgreSQL/FFmpeg 定向测试和后端全量 `verify`、前端类型检查、lint、70 项测试及生产构建均通过。隔离 `agenvas-duration-e2e` Compose 项目由 V35 工作树构建，首次因 Maven Central TLS 握手中断失败，原命令重试后构建成功且三个服务健康；真实 Chrome 运行 `AGENVAS_E2E_REDO=1 AGENVAS_E2E_EXPORT=1 node frontend/e2e/manual-storyboard-browser.mjs` 退出码 0，输出 M1、T24、T25 三项通过，包含元数据获取后的手动导出、私有下载与 MP4 播放。OpenAPI 增加元数据 GET、Asset/ManifestAsset 可空时长字段及视频原文件 MIME，生成 TS 已同步。旧客户端若严格拒绝新增 JSON 字段需更新；V35 不自动探测旧视频行。无真实 Provider 调用。
