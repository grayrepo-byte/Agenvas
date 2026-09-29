# 图片后处理切片证据（2026-09-29）

## 行为范围

- 本地：裁剪、90° 旋转、水平/垂直镜像、2×/4× 双三次插值、Depth Anything V2 Small ONNX 深度提取。
- 云端：智能编辑、重新打光、扩图、三视图、图层分离、表情调整、文字驱动的画笔标注、移除背景、局部擦除和视角调整；只允许 OpenAI GPT Image 2 或 Google Nano Banana 2 参考图能力。
- 全部处理固定来源 CanvasItem 的媒体结果，创建独立结果 CanvasItem、可删除媒体派生线、持久图片 Task 和不可变结果记录；来源节点保持原图，去字幕等视频处理不在本轮。

## 实际检查

- `backend ./mvnw -q -Dtest=LocalImageProcessorAdapterTest -Dagenvas.test.depth-model=/tmp/agenvas-depth-anything-v2-small-int8.onnx test`：通过。包含真实 ONNX Runtime 推理、本地放大/裁剪和模型缺失阻断；模型 SHA-256 为 `01aa7a23de3f4a0ee1a2bb9997e6918104c85a9f95dea46d27b9b3fb0c6b9001`。
- `backend ./mvnw -q -Dtest=MediaCapabilityPostgresIT test`：通过。PostgreSQL 17.11 执行 V1–V57，内置 LOCAL 连接和能力可加载。
- `backend ./mvnw -Dtest=ImageOperationSpecTest test`：3 项通过，覆盖七项扩展的云端分类、结构化参数、透明输出规则和 Provider 指令。
- `frontend pnpm exec vitest run src/features/canvas/MediaCanvasCard.test.tsx`：15 项通过，包含七项 AI 入口、表情任务请求和图层透明能力筛选。
- `frontend npm run api:generate && npm run typecheck && npm run lint && vitest ... && npm run build`：合约类型生成、类型检查、ESLint、`MediaCanvasCard`/`ArtifactCardFrame`/`MediaSettingsPage` 共 23 项测试和生产构建通过。
- 后端编译通过；OpenAPI TypeScript 类型已从权威合约重新生成。
- `backend ./mvnw -q -Dtest=ImageGenerationParametersTest -Dit.test=ImageOperationDerivationPostgresIT,CanvasMediaContextPostgresIT,MediaDraftPostgresIT verify`：2026-09-30 通过。PostgreSQL 17.11 执行 V1–V59，证明普通图片/视频运行、上传和图片后处理都创建独立结果节点，`MEDIA_DERIVATION` 可删除且不删除节点，幂等重放不重复创建。
- `backend ./mvnw -q -Dit.test=VersionedMediaInputPostgresIT verify`：2026-09-30 通过。旧的媒体版本选择测试已改用单结果节点复制，精确输入引用、断线清理、Agent 图片绑定和视频冻结输入继续通过 PostgreSQL 集成验证。
- `frontend pnpm test -- canvasRelations.test.ts MediaCardUpload.test.tsx MediaDraftEditor.test.tsx ArtifactVersionEditing.test.tsx CanvasRelationDeletion.test.tsx MediaCanvasCard.test.tsx && pnpm typecheck`：37 个测试文件、255 项通过；派生线投影为可选、可删除的粉色实线。相关 ESLint 通过。
- Codex 内置浏览器使用隔离 PostgreSQL 与当前工作区后端，在 1440×900 和 1280×900 视口运行：Mock 图片创建后点击“深度提取”，真实 ONNX 任务成功完成并自动选用新的灰度深度版本；AI 打光面板显示当前图片预览、六种预设、亮度、色温、光源位置、补充描述和 OpenAI/Google 能力选择。首次捕获发现面板受 React Flow 变换祖先裁切，改为页面级 Portal 后复测完整显示；切换“月光”时亮度与色温同步为 -24 / 8200K。浏览器 warning/error 为空。
- 首次浏览器比较发现扩展菜单最后两项被底部 Prompt 编辑器覆盖；图片工具栏改为仅在菜单/处理面板打开时提升 React Flow 浮层层级，复测 13 项完整显示。
- 同一隔离项目补验七个原占位入口：三视图、图层分离、表情调整、画笔标注、移除背景、局部擦除和视角调整均显示为可用 AI 操作；图层分离面板完整显示单层输出选择，并在缺少透明能力时给出准确禁用提示。浏览器 warning/error 为空。
- 使用用户已登录的 Chrome 和当前 Compose 项目点击“水平镜像”：处理前画布有 8 个节点、5 条线；任务完成后可访问树出现第 9 个图片节点及从来源节点指向该节点的第 6 条线。来源节点下载仍指向原 Asset，结果节点指向新 Asset；结果节点具有相同的生成编辑器、扩展工具栏与重新生成入口，卡片详情只显示“已有结果”而没有媒体版本选择。当前工作树重新构建的 server/web 与 PostgreSQL 均为 healthy。

## 未验证限制

- 未调用真实 OpenAI 或 Google 做本轮任何 AI 图片后处理；只复用此前已有的固定图片编辑适配器，因此不同模型对三视图一致性、背景补全、透明边缘和视角保持的实际质量仍待验收。
- 图层分离一次只输出主体层或背景层的普通 PNG/JPEG 版本，不是 PSD 或可组合的多层工程文件；画笔标注当前按文字说明生成，不包含用户手绘蒙版输入。
- 未在 Linux 容器、GPU 或大尺寸真实照片上测深度质量、耗时和内存峰值。
- 浏览器使用明确标注的 Mock 源图核对任务和布局，不以该演示素材证明云端 Provider 已接通。
