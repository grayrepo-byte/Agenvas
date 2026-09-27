# Issue #6 版本化媒体输入与 CanvasItem 分支证据

日期：2026-09-28。

## 已交付行为

本轮完成 Issue #6 的核心纵向切片，但没有声称覆盖该 Issue 的全部 128 条用户故事。

- 图片/视频草稿的提示词、参数、能力、视频输入模式、有序精确图片版本、角色、稳定颜色和结构化提及归属单张 `CanvasItem`；同一 Artifact 的多张卡片可保存不同工作上下文。
- 持久化 `CanvasConnection` 固定来源卡片当时展示的图片版本。媒体目标按精确版本合并手动与多条连线来源；断开一条线只移除对应来源，最后来源消失时才移除图片输入。图片到 Agent 的连线建立精确版本绑定，不进入媒体草稿。
- 普通草稿自动保存保留既有来源集合，不会把仅由连线维持的图片偷偷变成手动来源。Agent 的 IMAGE 绑定只能由画布连线创建和移除，普通 Agent 设置保存会保留这部分拓扑投影。
- 仅由连线维持的图片在编辑器中不能用缩略图删除键制造无效操作，并提示用户断开对应画布连线。
- 卡片复制保留展示版本、草稿、图片顺序/颜色/标签，并把图片来源转换为手动来源；不复制任务或连线。服务端以来源卡片和草稿 CAS 拒绝过期复制。
- 直接媒体任务冻结父版本、能力版本、模式、提示词、参数、有序图片版本/角色与结构化标签。成功版本记录 `baseVersionId` 和同一份 `frozenInput`；自动选用只比较父版本、模式及最终图片顺序/角色，不比较来源数量。
- 历史版本可显式“使用此版本输入”；确认后完整替换目标草稿、清除目标现有媒体连线，并把历史图片恢复为手动来源，不恢复旧拓扑。
- 能力目录公开图片数量上限、视频输入模式、默认模式和尾帧支持；执行前再次按固定能力版本校验。已升级的固定适配器从冻结的有序输入读取图片，未升级的真实图片适配器仍声明最多一张。
- 项目导出清单升级为 schema v2，包含 Artifact 版本分支/资源默认版本、CanvasItem 展示选择与布局、完整媒体草稿/输入/来源/颜色/标签及持久化连线；密钥仍不进入导出。
- 项目快照包含持久化连线；SSE 订阅连接创建/删除事件，并同时刷新连线和目标媒体草稿，断线恢复不依赖内存事件。
- 权威 OpenAPI、Java 实现、生成 TypeScript、Flyway V55 和生成 jOOQ 源码保持同步。V55 按 ADR 0014 的已确认开发期策略清空项目创作数据，重新触发私有资源卷清理，并保留安装、身份、加密与 Provider/模型配置。

## 主要接口与持久化变化

- 新增 `GET/POST /api/v1/projects/{projectId}/canvas/connections` 与 `POST /api/v1/projects/{projectId}/canvas/connections/{connectionId}/disconnect`。
- 新增 `POST /api/v1/projects/{projectId}/canvas/items/{sourceItemId}/duplicate`。
- 新增 `POST /api/v1/projects/{projectId}/canvas-items/{canvasItemId}/media-draft/restore-version-inputs`。
- `artifact_version` 新增 `base_version_id`、`frozen_input_json`；新增 `canvas_item_media_input`、`canvas_item_media_input_source`、`canvas_connection`，媒体草稿增加参数、模式、能力与结构化标签字段。
- Artifact 内容 Schema 删除旧 `keyframeVersionId`；任务、版本来源和导出不再双写旧单图片字段。

## 自动化检查

定向检查：

- 后端编译和测试编译通过。
- `VersionedMediaInputPostgresIT` 通过，覆盖精确版本输入、多来源引用计数、持久化连线、Agent 图片绑定、复制、历史输入恢复和拓扑清理。
- `MediaDraftPostgresIT`、`ArtifactPostgresIT`、`TaskArtifactSelectionPostgresIT`、`ProjectExportManifestPostgresIT` 通过。
- ComfyUI、OpenAI、Google、Ark、归档与 UNKNOWN 相关的 10 个定向 Provider 集成测试类通过；均使用 Mock 或本地假服务。
- `MediaDraftEditor.test.tsx` 15 项、画布连线/删除 20 项、版本编辑与工作区 26 项定向前端测试通过。
- 前端 `corepack pnpm typecheck`、`corepack pnpm lint`、`corepack pnpm build` 通过。

最终全量检查：

- 前端最终 `corepack pnpm test`：36 个测试文件、234 项测试通过；`typecheck`、`lint` 和生产构建通过。
- 后端完整 `./mvnw verify`：125 个单元测试通过；75 个 PostgreSQL 集成测试中 74 个通过，1 个因首次响应与 JSONB 重放响应的对象字段顺序不同而失败。实现随后改为首个幂等响应也回读持久化快照；`ArtifactCreateIdempotencyPostgresIT` 定向复跑通过。按仓库规则未再次启动完整套件。
- 最终变更后的 `VersionedMediaInputPostgresIT,ProjectEventPostgresIT` 以及后续 `VersionedMediaInputPostgresIT,CreativeDataResetPostgresIT` 定向复跑均通过（每组 2 项、0 失败），相应 Maven 生命周期中的 125 个单元测试也通过。
- 最终 UI 修正后 `MediaDraftEditor.test.tsx,projectEvents.test.ts` 定向运行 28 项通过。
- `git diff --check`：通过。

## 尚未完成或未验证

- 没有真实 Provider 调用；浏览器端到端交互和人工视觉验收未运行。
- 图片栏尚无多文件上传、逐文件失败/重试、资源库原子多选和上传前容量阻断。
- 有键盘左右重排，但尚无拖拽重排；历史来源版本尚无显式同步/降级流程。
- 模型/模式切换尚无影响预览和原子清理；草稿保存/连线使用全局 8 张保护上限，而非所选能力的精确上限，精确能力校验发生在运行前。
- Agent 图片别名聚合、归档字节去重，以及按模型限制图片数量、单图大小和总大小的发送前校验尚未实现。同一 IMAGE Artifact 的两个不同版本目前不能同时成为一个 Agent 的独立绑定。
- 前端复制依赖自动保存后的已持久化草稿；尚未显式等待正在进行的保存请求。服务端 CAS 会阻止过期或部分分支创建。
- 综合清单条目包含上述后续能力，因此继续保持未勾选。
