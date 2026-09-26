# T07：其他画布节点体验统一（2026-09-26）

## 行为变化

- 新增 `ContentCanvasCard`：文字、角色、场景、镜头共用黑色表面、选中浮动工具栏、类型/版本/引用标签；可选择和滚动完整正文，场景与镜头按真实字段呈现，不展示序列化 JSON。
- `ProjectWorkspacePage` 将内容卡片的版本历史、精确引用移除、布局锁定和移除迁到详情抽屉；引用移除仍等待 CAS 保存成功才变更画布。双击卡片聚焦底部编辑器，空选或关闭后隐藏。
- `StructuredArtifactEditor` 和 `ShotRedoEditor` 使用约 680×275px 的黑色面板、内部滚动与固定操作区。显式保存创建新版本；远端更新保留有修改的输入与原 CAS 基准，冲突时显式载入最新版本才替换草稿。等待时禁用重复保存，失败保留输入。镜头局部修订仍保留历史媒体和审批边界。
- `MediaDraftEditor` 视频首帧选择使用同项目归档图片的精确版本和真实缩略图，支持旧版本、替换、清除、加载失败重试；不拿当前版本冒充所选旧版本。模型缺失或读取失败时禁止运行，时长遵循所选能力范围。任务行来自真实持久状态。
- 媒体草稿的后台刷新只更新干净输入，拒绝晚到的旧版本；运行响应丢失后，重试固定原幂等键和草稿版本，即使后台已读到服务端的新版本也不变更原请求。
- `MediaCanvasCard` 视频默认只加载封面，用户点击后才创建播放器；支持缓冲加载、播放错误/重试、关闭返回封面。UNKNOWN/阻断入口指向任务处理，不展示再次生成的主操作。
- 实际读取并适配 Beautiful UI 的 ContextCards、SelectionActions、PromptBar、ChatComposer、TaskRows 与既有 LoadingState，源码旁保留 MIT 归属；没有示例计时器、虚构进度或模型私有推理。

## 合约与边界

纯前端变更；OpenAPI、生成类型、数据库、依赖版本不变，无迁移。规格同步至 `docs/MVP-SPEC.md` 与开发清单。没有新增音频/3D、视频上传或额外 Provider 能力。尺寸/画质仍为既有能力摘要。卡片 Task 查询沿用 USER_DIRECT 接口，不表示新增 Agent 来源任务查询。

## 已执行检查

- 7 个定向 Vitest 文件初次集成共 63 项通过：`MediaCanvasCard`、`ProjectWorkspacePage`、`CanvasSemanticConnection`、`ContentCanvasCard`、`ArtifactVersionEditing`、`ShotRedoEditor`、`MediaDraftEditor`。执行 `corepack pnpm exec vitest run` 并显式列出这 7 个文件。
- 并发补验后 `MediaDraftEditor.test.tsx` 单独重跑 15 项通过，覆盖运行后继续编辑、旧 GET 晚到与已受理/丢响应后的原请求幂等重试；`MediaCanvasCard.test.tsx` 在修正完整合约 fixture 后重跑 7 项通过。本次共 64 个相关测试用例通过，未运行全量测试。
- `corepack pnpm lint`：通过。
- `corepack pnpm typecheck`：通过；最初发现视频测试 fixture 缺少合约必填字段，补齐后通过。
- `corepack pnpm build`：通过，Next.js 16.3.6 静态导出成功，包含最终并发修复的 TypeScript 检查。
- 最终媒体草稿文件定向 ESLint 与 `git diff --check`：通过。

未运行全量测试、后端测试或真实 Provider 调用。DOM 测试中的视频事件不等于实际媒体解码验证。

## 浏览器限制

已确认开发服务在 5173 监听；内置浏览器访问 `http://localhost:5173` 返回 `net::ERR_BLOCKED_BY_CLIENT`。未取得实现截图，未完成真实指针、控制台和视觉对照验收。详见 [design-qa.md](../../design-qa.md) 的其他节点增量，视觉结果保持 `blocked`。
