# T07：黑色画布显示重构（2026-09-26）

## 行为与范围

- 媒体节点独立为 `MediaCanvasCard`：空图片图标/上传、已归档缩略图、原图显式打开、视频显式播放、Mock 标记、真实 Task 加载/失败/取消/UNKNOWN 状态。
- 单选显示顶部浮动工具栏；多选沿用批量操作。图片扩展菜单按图稿排列，未接入项目明确禁用。详情抽屉保留版本、引用、锁定与移除，相关操作显示等待与失败。各抽屉互斥，避免被旧面板遮盖。
- `MediaCardUpload` 追加同一 Artifact 的 UPLOAD 版本并显式选用。选用失败复用已上传 Asset 和已确认版本；修订响应丢失时先核对当前版本与 Asset，不重复创建。真实 CAS 冲突保留文件并提供读取最新版本入口。
- `MediaDraftEditor` 为约 680×275px 的 Prompt 面板，含参考版本入口、已启用模型菜单、尺寸/画质摘要、未知费用和圆形运行按钮；保留自动保存、CAS、幂等任务、队列取消和 UNKNOWN 核对。
- 当前草稿合约无独立的尺寸/画质参数；只读展示已有能力参数/模型默认。未实现视频上传、图片参考编辑、裁剪/抠图等扩展，不把禁用入口标为功能已完成。
- `JdbcArtifactRepository.updateMediaDraft` 不再强制切 DRAFT；保存草稿保持所选 RESULT，运行受理才切草稿占位。数据库模式及 OpenAPI 无变化，不需要迁移或重新生成 API 类型。
- 新增 `@phosphor-icons/react@2.1.10`，锁文件和依赖基线同步。动画使用 [Beautiful UI Loading State](https://github.com/slev12397/beautiful-ui/blob/main/components/primitives/LoadingState.tsx) 示例与配套 CSS，组件旁保留 MIT 许可；支持 reduced-motion，不展示虚构进度。

## 已执行验证

- `pnpm typecheck`：通过。
- `pnpm lint`：通过。
- `pnpm build`：Next.js 16.3.6 静态导出成功。
- 定向 Vitest：工作区、版本编辑、语义连线、媒体卡片、同卡片上传、加载态、媒体编辑器，7 个文件共 43 项通过。命令为 `pnpm exec vitest run` 后明确列出这 7 个 `.test.tsx` 文件。
- PostgreSQL：`./mvnw --batch-mode --no-transfer-progress -q -Dit.test=MediaDraftPostgresIT test-compile failsafe:integration-test failsafe:verify`。先复现 RESULT→DRAFT 错误，再修复并通过 1 项集成测试（0 failures/errors/skipped）；使用 PostgreSQL 17.11 Testcontainers，未操作部署数据库。
- `git diff --check`：通过。

一次子任务将 `pnpm test -- <file>` 误用作定向命令，Vitest 实际执行了全部前端文件；旧有文件通过，新编辑器测试有初始 fixture 等待失败。随后纠正为 `pnpm exec vitest run <file>`，仅对受影响文件验证，不把该次执行当作全量验收。

## 未验证限制

本轮无真实 Provider 调用，无部署和生产数据迁移。浏览器工具对 localhost/127.0.0.1 返回 `net::ERR_BLOCKED_BY_CLIENT`，原生 Chrome 返回未授权电脑控制；无法截图比较、检查控制台或执行真实指针交互。[design-qa.md](../../design-qa.md) 明确为 `blocked`，开发清单的视觉验收项未勾选。类型检查、静态构建和组件测试不能替代视觉验收。
