# T15/T25 Agent 顺序导出提案：阶段证据

2026-09-24。`propose_export` 是第十四个模型可见受控工具，输入为项目画幅及 1–6 个有序镜头/视频精确版本和毫秒区间。服务端逐个检查 Run 可见范围、同项目、产物类型、当前版本与 Artifact CAS，复用 `MediaExportService.preview` 的视频归档、区间、总时长和画幅校验。成功时持久保存 `export_proposal`、内容/项目版本快照、提案哈希及项目事件；工具结果没有 Task ID，不启动 FFmpeg。

V32 新增 `export_proposal` 表，包含 Run 外键、不可变输入与版本 pin、状态、审批用户/时间以及获批后唯一 Task ID。用户经 `/api/v1/projects/{projectId}/export-proposals` 查看内容，通过带 CSRF 的 `/approve` 提交所显示的完整哈希，或 `/reject` 拒绝。审批在项目事件锁与提案行锁下复核项目及所有内容版本，然后通过现有持久任务服务创建一个 `MEDIA_EXPORT` Task；并发或重复同哈希批准返回同一 Task。项目、镜头或视频版本变化返回冲突；拒绝不创建任务。Agent 无批准工具。

`MockStoryboardChatGateway` 在三段 Mock 视频归档后提出有序导出建议，再以一轮模型消息结束；用户批准后才由受限本地 FFmpeg 执行。前端导出面板列出待审批提案、完整哈希、精确镜头/视频版本及区间，并提供批准/拒绝操作；项目 SSE 事件触发列表刷新。既有手工顺序导出不受影响。

合约变更：`contracts/openapi.yaml` 增加提案列表/详情/批准/拒绝路径和 DTO；`frontend/src/shared/api/schema.ts` 由合约重新生成，未手改。V32 只追加迁移，不改旧迁移；开发阶段 API 仍为 `/api/v1`、项目版本 0.1.0，新客户端需使用新增路径，无旧客户端破坏性字段改动。

定向验证：`ExportProposalPostgresIT` 用假 ChatGateway、真实 PostgreSQL 与本地 FFmpeg 验证工具结果先落账、提案时无导出 Task、相同调用重放、未鉴权读取/缺少 CSRF 被拒、错误哈希冲突、批准后唯一 Task、并发批准复用、拒绝无副作用、未绑定视频被拒、错误画幅/区间被拒，以及用户修改镜头后的旧提案不能批准。`MockStoryboardPostgresIT` 覆盖 Mock 三镜头→图片/视频两次人工审批→Agent 导出提案→人工批准→本地无声 MP4 归档与受保护下载。`MediaExportPanel.test.tsx` 通过假 HTTP 服务验证提案未批准前不提交、批准请求发送所显示哈希并刷新状态。

限制：没有真实 LLM/ComfyUI 调用；真实磁盘写满与完整发布门禁尚未验证。本项不能替代 T15/T25 的全部剩余验收。

浏览器补验（2026-09-24）：在独立 `agenvas-proposal-e2e` Compose 项目与独立数据卷中，全新 PostgreSQL 启动日志显示 V1–V32 全部迁移成功。浏览器登录独立测试管理员、创建横屏项目与 Creator Agent，实际完成三镜头规划、三张图片计划批准、逐镜头精确关键帧选择、三段视频计划批准。导出提案出现时，页面显示三段各 1.000 秒的顺序、镜头/视频版本和完整哈希，导出记录仍为空；浏览器点击“批准并开始导出”后显示同一提案“已批准”、一个导出任务“已完成”及下载链接。已登录 HTTP 会话下载返回 200、45,989 字节；FFprobe 检查为 H.264、1280×720、3.000 秒，只有视频流。此为确定性 Mock 媒体＋本地 FFmpeg，不代表真实 Provider。

这次浏览器验收也暴露 SSE 列表陈旧：首轮视频/提案已在数据库中，但页面需刷新才显示。修复后第二个隔离项目未经刷新即显示三段视频、待审批提案、批准状态和导出完成。原因是前端未订阅 `asset.ready`、`export.proposal.changed`，且快照更新会重复重建连接；恢复快照时也未刷新提案/导出列表。服务端在断线错误后再次完成 `SseEmitter` 曾打印 `AsyncContext` 竞态堆栈，现避免重复完成。对应前端事件序列/单连接测试和后端关闭路径测试已增加。第二轮仍观察到一次“正在连接事件流”短暂状态；最终补齐 `asset.ready` 后的构建已健康并在浏览器恢复已完成项目，但未重演新的媒体事件和更长时段断线，不能声称所有浏览器 SSE 故障验收完成。

补验检查：前端 `tsc --noEmit`、ESLint、Vitest（12 文件、37 测试）与 Vite 构建均退出码 0；仍有主 JS chunk 超过 500 kB 告警。后端普通 `./mvnw -q verify`（含 PostgreSQL 集成测试）退出码 0；隔离 Compose 最终构建及三个服务健康，原有 `agenvas` 实例始终未改动。浏览器测试后只删除 `agenvas-proposal-e2e` 的三个容器、网络和两个测试数据卷；隔离测试数据不可恢复，原有 `agenvas` 三服务核对仍在运行。

最终验证：定向 `ExportProposalPostgresIT`、`MockStoryboardPostgresIT` 通过；前端 `tsc --noEmit`、`eslint . --max-warnings=0`、`vitest run`（12 文件、34 测试）、`vite build` 和 `git diff --check` 退出码均为 0。构建仍提示单个 JS chunk 超过 500 kB。首次完整 `./mvnw -q verify` 因九个旧测试把 Flyway 最新版本硬编码为 V31 而失败，同步至 V32 后第二次仅 `ManualUnknownRetryPostgresIT` 在测试方法开始前的临时 PostgreSQL 初始连接出现 EOF；该类独立重跑通过，第三次普通 `./mvnw -q verify` 退出码 0。容器启动偶发波动仍需关注，不能把此前失败写成稳定通过。
