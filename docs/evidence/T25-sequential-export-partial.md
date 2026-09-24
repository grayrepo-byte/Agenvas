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
