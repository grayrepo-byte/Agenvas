> 2026-09-30 更新：下文 AI 画笔标注记录属于旧验收，已由 [本地画笔标注](T-brush-markup.md) 与 [ADR 0018](../adr/0018-local-brush-markup.md) 取代，当前画笔标注不调用 AI。

# 图片后处理切片证据（2026-09-29）

## 行为范围

- 本地：裁剪、90° 旋转、水平/垂直镜像、2×/4× 双三次插值、Depth Anything V2 Small ONNX 深度提取。
- 云端：智能编辑、重新打光、扩图、三视图、图层分离、表情调整、文字驱动的画笔标注、移除背景、局部擦除和视角调整；只允许 OpenAI GPT Image 2 或 Google Nano Banana 2 参考图能力。智能编辑另外提供涂抹/框选蒙版、蒙版擦除、笔刷尺寸、撤销/重做、画布固定版本引用、本地参考图上传和提示词；绘制蒙版后只保留声明 `supportsImageMask` 的能力。
- 全部处理固定来源 CanvasItem 的媒体结果，创建独立结果 CanvasItem、可删除媒体派生线、持久图片 Task 和不可变结果记录；来源节点保持原图，去字幕等视频处理不在本轮。

## 实际检查

- `backend ./mvnw -q -Dtest=LocalImageProcessorAdapterTest -Dagenvas.test.depth-model=/tmp/agenvas-depth-anything-v2-small-int8.onnx test`：通过。包含真实 ONNX Runtime 推理、本地放大/裁剪和模型缺失阻断；模型 SHA-256 为 `01aa7a23de3f4a0ee1a2bb9997e6918104c85a9f95dea46d27b9b3fb0c6b9001`。
- `docker compose --env-file .env -f deploy/compose.yaml build server`：2026-09-30 通过。上游下载当时出现 TLS 中断，因此验收构建通过 build arg 使用了先前已完成 SHA-256 校验的同一模型与许可证缓存；Dockerfile 的默认来源仍固定到上游提交。Ubuntu Noble/ARM64 构建阶段用同一模型执行真实 ONNX 推理，Maven 单元测试 142 项通过，镜像构建完成。
- 运行容器核验：`AGENVAS_DEPTH_MODEL=/opt/agenvas/models/depth-anything-v2-small-int8.onnx`；27.3 MB INT8 模型与 Apache-2.0 许可证 SHA-256 均通过，且路径不受 `/opt/agenvas/data` 数据卷遮挡。ONNX Runtime 原生库从只读镜像路径 `/opt/agenvas/lib/onnxruntime` 加载，保留 `/opt/agenvas/tmp` 的 `noexec` 安全限制；Ubuntu Noble `ffmpeg` 为 6.1.1-3ubuntu5，包含 `libx264`/`libx264rgb` 编码器。
- Chrome 实际点击“深度提取”：任务 `SUCCEEDED`，从源资源 `367d8fc5-8270-3dd5-b7ff-f8f673bd686e` 派生新节点和新资源 `4c84b7cc-6156-3c31-b007-c101cd113f01`；产物为 1536×1024 PNG 深度图，源节点未覆盖。服务日志未再出现 ONNX Runtime 原生库加载错误。
- `backend ./mvnw -q -Dtest=MediaCapabilityPostgresIT test`：通过。PostgreSQL 17.11 执行 V1–V57，内置 LOCAL 连接和能力可加载。
- `backend ./mvnw -Dtest=ImageOperationSpecTest test`：3 项通过，覆盖七项扩展的云端分类、结构化参数、透明输出规则和 Provider 指令。
- 三视图细分定向检查：`ImageOperationSpecTest` 3 项通过，覆盖四种类型的参数冻结及差异化 Provider 指令；`frontend pnpm exec vitest run src/features/canvas/MediaCanvasCard.test.tsx` 17 项通过，覆盖二级菜单、脸部类型选择、`threeViewType=FACE` 请求、表情任务请求和图层透明能力筛选。
- `frontend npm run api:generate && npm run typecheck && npm run lint && vitest ... && npm run build`：合约类型生成、类型检查、ESLint、`MediaCanvasCard`/`ArtifactCardFrame`/`MediaSettingsPage` 共 23 项测试和生产构建通过。
- 后端编译通过；OpenAPI TypeScript 类型已从权威合约重新生成。
- `backend ./mvnw -q -Dit.test=DirectMediaGenerationPlacementPostgresIT verify`：2026-09-30 通过。PostgreSQL 17.11 执行 V1–V59，证明空图片节点第一次直接生成绑定当前节点且不创建多余节点；当前节点已有固定结果后，再次生成会在任务仍为 READY 时原子创建新节点与 `MEDIA_DERIVATION`。
- `backend ./mvnw -q -Dtest=ImageGenerationParametersTest -Dit.test=DirectMediaGenerationPlacementPostgresIT,MediaDraftPostgresIT,CanvasMediaContextPostgresIT,ImageOperationDerivationPostgresIT verify`：2026-09-30 通过。PostgreSQL 17.11 执行 V1–V59，证明首次填充原节点、已有结果立即派生、批量与视频生成、图片上传和图片后处理语义兼容；`MEDIA_DERIVATION` 可删除且不删除节点，幂等重放不重复创建。随后单独复验 `CanvasMediaContextPostgresIT`，补充证明空图片节点上传原位落图、无额外节点/派生线且请求重放不追加版本。
- `backend ./mvnw -q -Dit.test=VersionedMediaInputPostgresIT verify`：2026-09-30 通过。旧的媒体版本选择测试已改用单结果节点复制，精确输入引用、断线清理、Agent 图片绑定和视频冻结输入继续通过 PostgreSQL 集成验证。
- `frontend pnpm test -- canvasRelations.test.ts MediaCardUpload.test.tsx MediaDraftEditor.test.tsx ArtifactVersionEditing.test.tsx CanvasRelationDeletion.test.tsx MediaCanvasCard.test.tsx && pnpm typecheck`：37 个测试文件、255 项通过；派生线投影为可选、可删除的粉色实线。相关 ESLint 通过。
- `frontend pnpm exec vitest run src/features/canvas/MediaCanvasCard.test.tsx src/features/canvas/MediaCardUpload.test.tsx`：2026-09-30 共 22 项通过；覆盖空节点上传目标固定为当前节点、响应丢失后稳定重试，以及上传按钮不向卡片点击处理器冒泡。相关 TypeScript 类型检查和定向 ESLint 通过。
- Codex 内置浏览器使用隔离 PostgreSQL 与当前工作区后端，在 1440×900 和 1280×900 视口运行：Mock 图片创建后点击“深度提取”，真实 ONNX 任务成功完成并自动选用新的灰度深度版本；AI 打光面板显示当前图片预览、六种预设、亮度、色温、光源位置、补充描述和 OpenAI/Google 能力选择。首次捕获发现面板受 React Flow 变换祖先裁切，改为页面级 Portal 后复测完整显示；切换“月光”时亮度与色温同步为 -24 / 8200K。浏览器 warning/error 为空。
- 首次浏览器比较发现扩展菜单最后两项被底部 Prompt 编辑器覆盖；图片工具栏改为仅在菜单/处理面板打开时提升 React Flow 浮层层级，复测 13 项完整显示。
- 同一隔离项目补验七个原占位入口：三视图、图层分离、表情调整、画笔标注、移除背景、局部擦除和视角调整均显示为可用 AI 操作；图层分离面板完整显示单层输出选择，并在缺少透明能力时给出准确禁用提示。浏览器 warning/error 为空。
- 使用用户已登录的 Chrome 和当前 Compose 项目点击“水平镜像”：处理前画布有 8 个节点、5 条线；任务完成后可访问树出现第 9 个图片节点及从来源节点指向该节点的第 6 条线。来源节点下载仍指向原 Asset，结果节点指向新 Asset；结果节点具有相同的生成编辑器、扩展工具栏与重新生成入口，卡片详情只显示“已有结果”而没有媒体版本选择。当前工作树重新构建的 server/web 与 PostgreSQL 均为 healthy。
- 智能编辑定向检查：`frontend pnpm exec vitest run src/features/canvas/MediaCanvasCard.test.tsx` 18 项通过；`eslint` 与 `tsc --noEmit` 通过。测试覆盖全屏编辑器入口、画布精确版本参考图、提示词和有序 `referenceVersionIds` 提交。
- OpenAI 蒙版定向检查：`backend ./mvnw -q -Dtest=ImageOperationSpecTest,OpenAiImage2ClientTest,OpenAiImage2AdapterTest test` 22 项通过，覆盖能力声明、multipart `mask` 字段、非法蒙版拒绝与适配器能力边界。随后 server Docker 构建自动运行 Maven 单元测试 143 项并通过；web Docker 生产构建通过。
- 使用用户已登录的 Chrome 复验智能编辑：浮动工具栏、图片居中工作区和底部提示词编辑器完整显示；涂抹与框选均生成粉色蒙版，蒙版状态切换为“透明区域将被编辑”，撤销/重做有效；引用弹层展示 5 个其他 CanvasItem 的固定图片版本，选择后出现 `Image 2` 缩略图；提示词填写后发送按钮可用。本轮未点击发送，未产生 Provider 调用或任务费用。Chrome 扩展调试通道未能加载请求头策略，因此未取得浏览器 console 日志；页面交互与 Compose 日志未出现可见错误。
- 使用用户已登录的 Chrome 和生产 Compose 复验首次填充/后续派生：新建空图片节点 `44d96eac-cc23-4f57-b406-e2e1002e681a` 选择 Mock 能力运行后，Task 的来源与目标均为该节点，项目节点数保持 16 且没有派生线；在该节点已有结果后再次运行，画布在任务仍显示“排队中”时即出现新节点 `b3e4505a-264e-4b8a-905d-c4a6d16aa30c` 及连接两者的 `MEDIA_DERIVATION`，完成后项目节点数为 17。两次 Task 均成功，未调用真实 Provider，服务日志无新增错误。
- 使用同一已登录 Chrome 验收空节点上传：点击节点内“上传图片”立即打开 macOS 文件选择器，右侧上传/编辑抽屉均未出现；选择本地 PNG 后节点内短暂显示“正在上传图片…”，随后原位显示 Asset `84e33536-8315-4550-b2d4-b1eadc9dacad`。数据库核对 CanvasItem `4a77003c-80fc-40b5-911c-284163361d2b` 仍是该 Artifact 唯一节点，相关 `MEDIA_DERIVATION` 为 0，没有“图片上传未完成”提示。

## 未验证限制

- 未调用真实 OpenAI 或 Google 做本轮任何 AI 图片后处理；只复用此前已有的固定图片编辑适配器，因此不同模型对三视图一致性、背景补全、透明边缘和视角保持的实际质量仍待验收。
- 图层分离一次只输出主体层或背景层的普通 PNG/JPEG 版本，不是 PSD 或可组合的多层工程文件；扩展菜单中的“画笔标注”仍是生成型标注操作，与智能编辑里新增的不可见 Provider 输入蒙版是两种不同语义。
- 已在 Linux ARM64/glibc 容器对 4×3 测试图执行真实模型推理；未在 GPU 或大尺寸真实照片上测深度质量、耗时和内存峰值。
- 浏览器使用明确标注的 Mock 源图核对任务和布局，不以该演示素材证明云端 Provider 已接通。
