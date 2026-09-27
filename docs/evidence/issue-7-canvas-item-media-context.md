# Issue #7 CanvasItem 媒体工作上下文基础证据

日期：2026-09-27。

## 行为与数据归属

图片与视频的工作中状态现由单张 `CanvasItem` 独占：卡片保存自己的 `selectedVersionId`，媒体草稿以 `canvasItemId` 为身份并使用版本号做 CAS。将同一个 Artifact 放置到多张卡片时，各卡片可以独立选择展示版本和保存草稿；切换卡片版本或向卡片上传新版本都不会修改 Artifact 的资源默认版本。资源库再次放置 Artifact 时，新卡片从资源默认版本初始化展示选择，同时创建空草稿，不继承其他卡片的任务或工作中内容。

Artifact 的共享指针已明确重命名为 `resourceDefaultVersionId`。资源默认版本与卡片展示版本分别通过显式端点修改；项目快照同时返回卡片所选版本身份及其物化版本，相关项目事件分别为 `artifact.resource_default_version.changed`、`canvas.item.selected_version.changed` 和按 CanvasItem 标识的媒体草稿事件。前端使用卡片选择构造预览、版本关系与 Agent 输入绑定，并在草稿保存失败或 409 冲突时保留本地输入。

直接媒体运行请求现在必须带 `canvasItemId`，并从该卡片读取草稿、固定该卡片当前展示的父版本。未终结任务只在同一 CanvasItem 内互斥；同一 Artifact 的不同卡片可以并行运行。成功结果只在发起卡片仍满足固定父版本和草稿版本前提时自动选用，资源默认版本保持不变。完整分支历史和有序多图输入属于后续 Issue，不在本次基础切片内。

## 数据库、合约与生成代码

`V52__canvas_item_media_context.sql` 按已确认的本地开发策略清空项目创作数据及其级联数据，另清空幂等记录；迁移保留管理员、加密主密钥、LLM Provider 配置、媒体 Provider 连接/加密凭据和能力目录。迁移把 Artifact 共享指针改为 `resource_default_version_id`，为 `canvas_item` 增加带项目边界复合外键的 `selected_version_id`，并以 `(project_id, canvas_item_id)` 重建 `media_draft`。升级集成测试还验证 V52 重复执行不破坏保留配置。

`V53__canvas_item_task_ownership_and_asset_reset.sql` 为任务产物目标补充 `canvas_item_id`，并创建一次性清理标记。应用启动时在数据库行锁保护下清理资产根目录中 UUID 命名的旧项目目录，再原子标记完成；未知目录会保留，符号链接不会被跟随。这样 V52 删除 Asset 数据库记录后不会遗留对应项目媒体文件，恢复模式下则跳过这项破坏性清理。`V54__preserve_task_history_after_canvas_item_removal.sql` 让删除卡片时只置空任务的可选卡片引用，保留任务、结果与费用历史。

`contracts/openapi.yaml`、Java DTO/服务/持久层、生成 TypeScript 和 jOOQ 源码已同步。TypeScript 合约生成前后哈希一致；jOOQ 源码通过一次性 PostgreSQL 17 数据库重新生成，没有手改生成文件。

## 验收覆盖

- `CanvasMediaContextPostgresIT`：同一 Artifact 两张卡片分别选择不同版本、保存不同草稿、拒绝陈旧 CAS、保持资源默认版本不变；两张卡片可同时受理独立任务且各自固定父版本，并验证快照与项目事件可恢复卡片身份。
- `CanvasMediaContextUpgradePostgresIT`：从 V51 写入项目、Artifact/版本、Asset、CanvasItem/草稿、AgentRun、Task、事件、用量和幂等记录，再执行 V52；验证创作数据被清空而管理员、加密配置、Provider 连接/凭据、能力目录与 LLM 配置保留。
- `CreativeDataResetPostgresIT`：验证升级后的首次启动删除 UUID 项目资产目录、保留非项目目录，并写入完成标记。
- `MediaDraftPostgresIT` 与真实 PostgreSQL Provider 回归测试：验证生成结果选用发起卡片、资源默认版本不变，以及固定输入版本仍可进入适配器。
- 前端组件和投影测试覆盖卡片版本显示、原子上传与版本切换、媒体草稿 CAS/冲突保留、重复放置、卡片级任务查询/运行请求、SSE 类型化版本水位与选版后的精确草稿失效。

## 实际运行结果

- `backend ./mvnw verify`：通过；125 个单元测试、74 个真实 PostgreSQL 集成测试，失败、错误、跳过均为 0。
- `frontend corepack pnpm lint && corepack pnpm test && corepack pnpm build`：通过；36 个测试文件、232 个用例全部通过，TypeScript 检查与 Vite 生产构建通过。
- `frontend corepack pnpm api:generate`：通过；生成前后合约类型哈希一致。
- jOOQ PostgreSQL 17 代码生成：通过；生成源码已提交。
- `git diff --check`：通过。

## 未验证限制

未在真实浏览器中完成登录工作区端到端操作，未做真实 Provider 调用，也未验证跨 Worker 故障注入。本次只交付 Issue #7 的卡片媒体上下文基础；完整分支历史、有序图片输入和项目导出清单继续保持未完成。
