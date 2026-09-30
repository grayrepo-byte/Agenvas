# 按操作区分媒体节点版本的验证

日期：2026-09-30。规格与任务已记录为 [Issue #20](https://github.com/grayrepo-byte/Agenvas/issues/20)。产品决定见 [ADR 0017](../adr/0017-operation-specific-media-versioning.md)。

图片和视频生成/重新生成的首个输出绑定当前节点，成功归档后追加节点历史；编辑与后处理仍创建独立派生节点。工具栏可读历史并以节点 CAS 选用；草稿、既有输入和资源默认版本独立保留。额外批量输出仍绑定独立节点。

V60 新增 `canvas_item_media_version` 与内容选择 epoch；迁移保持原有节点，回填当前选用结果及任务输出，不清空数据或合并派生节点。jOOQ 在一次性 PostgreSQL 17.11 上通过官方 codegen profile 重新生成，未连接开发库。导出 schema v3 的 `mediaVersionIds` 保留节点历史归属，消费者需识别新增版本；OpenAPI 与生成 TypeScript 已同步。

## 实际运行的检查

- 后端单元测试：`./mvnw -Dtest=CanvasTaskResultSelectionTest,ImageOperationSpecTest test`，6 项通过。覆盖布局变化允许选用、切走再切回拒绝自动覆盖、草稿变化保护和图片操作参数。
- 后端 PostgreSQL：先对 `CanvasMediaVersionsPostgresIT,CanvasMediaContextPostgresIT,MediaDraftPostgresIT` 运行定向 `verify`，9 项通过；再对 `CanvasMediaVersionsUpgradePostgresIT,DirectMediaGenerationPlacementPostgresIT,ImageOperationDerivationPostgresIT,ProjectExportManifestPostgresIT` 运行定向 `verify`，4 项通过。两次都通过 `-Dtest=CanvasTaskResultSelectionTest,ImageOperationSpecTest -Dit.test=...` 限定范围，未运行全量测试。
- 13 项 PostgreSQL 测试覆盖图片/视频两次生成留在同节点、旧结果选用、节点历史隔离、布局与内容选择分离、生成中修改草稿及切走再切回、已有引用固定、复制只继承选用版本、排队取消、删除节点后的晚到归档、鉴权/CSRF/CAS、已有图片编辑派生、批量目标数量、V59→V60 数据保持及回填、未完成派生节点的临时父记录不进入历史、项目导出 schema v3。
- 前端：`vitest run` 定向运行 `MediaVersionPicker.test.tsx,MediaCanvasCard.test.tsx,MediaDraftEditor.test.tsx,MediaCardUpload.test.tsx`，4 个文件、53 项通过。包括节点 CAS 请求、切换失败保留选择、历史读取失败重试、空历史、Escape，以及既有运行/UNKNOWN/上传/图片编辑交互。
- 前端 `tsc --noEmit`、修改的 TS/TSX 文件 `eslint --max-warnings=0`、`vite build` 通过。API 生成器及 `git diff --check` 通过。

## 派生节点空白草稿补验

同日按用户修订，新建图片编辑、后处理及替换上传的派生节点不复制来源草稿，而是按新节点初始化：空提示词、空参数、未选择能力/时长/视频输入模式、无图片输入或标签。结果来源仍保存操作的精确输入。批量额外输出及普通复制保留各自复制语义；既有节点不自动清空，避免删除用户后续编辑。本次无新增数据库迁移或 API 字段，仅同步行为描述及生成类型。

- `./mvnw -Dtest=CanvasMediaDerivationDraftTest,CanvasTaskResultSelectionTest,ImageOperationSpecTest -Dit.test=ImageOperationDerivationPostgresIT,CanvasMediaContextPostgresIT,CanvasMediaVersionsPostgresIT,MediaDraftPostgresIT verify`：9 项单元测试、10 项 PostgreSQL 测试通过。图片操作来源草稿含非空提示词、参数、能力及参考图/标签，目标在受理与完成后均为空白，来源草稿保持不变。单元测试覆盖批量输出继续复制以及过期草稿拒绝创建派生节点。
- 补充替换上传 API 空白草稿断言后，用相同 `-Dtest` 加 `-Dit.test=CanvasMediaContextPostgresIT` 定向 `verify`，9 项单元测试和 1 项 PostgreSQL 测试通过。上传保持来源草稿、资源默认版本及幂等行为。首次新增断言错误地比较了此前运行前的显示状态，已改为比较上传前快照后重跑通过。
- OpenAPI TypeScript 生成器通过；`tsc --noEmit` 通过。生成类型约束暴露版本选择器测试数据的 `schemaVersion` 被推断为宽泛 `number`，已使用生成的 `ArtifactVersionList` 元素类型修正。`vitest run src/features/canvas/MediaVersionPicker.test.tsx` 4 项通过，修改测试文件 `eslint --max-warnings=0` 通过；`git diff --check` 通过。
- 本次未运行前端生产构建、浏览器端到端、全量测试或真实 Provider，未部署。已有派生节点的草稿不做自动迁移。

## 派生节点标题补验

新建派生节点在受理时保存「来源节点当前显示名称 · 操作名称」。例如深度提取为「原图 · 深度图」，裁剪为「原图 · 裁剪」，替换上传为「原图 · 上传」。三视图区分角色、脸部、道具和场景宫格，图层区分主体/背景。标题过长时保留操作后缀、截短来源名称，不拆开 emoji 代理对；任务完成及重放不重命名用户已修改的结果。既有节点与共享资源名称不自动修改，无新迁移或 API 字段，OpenAPI 行为描述与生成 TypeScript 已同步。

- 实际运行 `./mvnw -Dtest=CanvasMediaDerivationDraftTest,CanvasTaskResultSelectionTest,ImageOperationSpecTest -Dit.test=ImageOperationDerivationPostgresIT,CanvasMediaContextPostgresIT verify`：10 项单元测试、2 项 PostgreSQL 测试通过。
- 本地裁剪集成测试把来源节点改为与资源不同的名称，验证受理时保存派生名；随后手工修改派生名，任务成功后仍保留该名称，原节点与资源名称保持各自身份。上传接口与重放保持同一派生名。单元测试覆盖深度图后缀、长名称和 emoji 边界，以及三视图/图层的参数命名；空白草稿、来源保护与批量输出原有命名回归通过。
- OpenAPI TypeScript 生成器、`tsc --noEmit` 和 `git diff --check` 通过。本轮未运行前端组件、全量测试、浏览器端到端、真实 Provider 或生产构建，未部署；裁剪验证使用本地图片处理。

## 限制

媒体生成流程使用明确的 Mock Provider，视频包含实际归档 MP4；图片派生使用本地裁剪。未调用真实外部 Provider，未运行浏览器端到端、全量测试或部署升级。迁移在真实 PostgreSQL 的构造旧数据上验证，未对用户部署数据执行迁移。工作区原有图片蒙版、模型和其他改动保持原样。
