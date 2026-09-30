# T07 图片生成原子参数与批次输出证据

日期：2026-09-29

> 2026-09-30：本文最初记录的可选“生成时新建节点”已由 [ADR 0016](../adr/0016-media-nodes-are-single-results.md) 取代；比例、分辨率、画质、透明背景与生成数量验证仍有效。当前空节点的第一个输出固定使用当前节点，已有结果后的所有输出和空节点批次的其余输出使用独立节点。

## 已实现行为

- 图片 CanvasItem 草稿结构化保存比例、分辨率、画质、透明背景和生成数量；服务端拒绝未知字段、非法枚举和不受所选能力支持的参数。
- 能力 API 返回支持的图片比例、分辨率、画质和透明背景。前端只展示所选能力可用的选项，切换能力导致参数回退时要求用户确认。
- 一次运行按生成数量真实创建 1、2 或 4 个 Task，并在每个 Task 中冻结同一草稿与各自的生成序号。重复命令不会追加兄弟任务。
- 空 CanvasItem 的第一次运行把第一个 Task 绑定到当前节点；批次的其余 Task 使用受理时创建的独立节点。
- 已有固定结果的 CanvasItem 再次运行时，受理事务为每个 Task 创建独立 CanvasItem 工作分支和 `MEDIA_DERIVATION` 并自动避让放置；来源卡片不被任务占用，排队和生成期间即可看到派生线，结果只可能选用到自己的目标分支。
- OpenAI 映射比例/分辨率、低中高画质及透明背景；Google 映射比例与 1K/2K/4K；固定 ComfyUI 只声明并映射模板已有的 AUTO/1:1/9:16/16:9 与 1K；Mock 输出生成相应尺寸、比例和透明背景的演示 PNG。

## 实际检查

- `pnpm run api:generate`：通过，生成 TypeScript 与 OpenAPI 同步。
- `pnpm run typecheck`：通过。
- `pnpm exec vitest run src/features/canvas/MediaDraftEditor.test.tsx src/features/canvas/ProjectWorkspacePage.test.tsx src/features/settings/MediaSettingsPage.test.tsx`：53 项通过。
- 后端 `ImageGenerationParametersTest`、`OpenAiImage2AdapterTest`、`OpenAiImage2ClientTest`、`GoogleNanoBananaClientTest`：通过。
- PostgreSQL `MediaDraftPostgresIT`：2026-09-30 通过，覆盖空节点首次填充、已有结果后的独立节点、批次输出及幂等重放不追加兄弟任务。
- PostgreSQL `DirectMediaGenerationPlacementPostgresIT`：2026-09-30 通过，覆盖空节点首次生成不增加节点，以及已有结果再次生成时在 Task 仍为 READY 的阶段就创建派生节点和 `MEDIA_DERIVATION`。
- PostgreSQL/假 HTTP `MediaCapabilitySettingsPostgresIT`、`MediaCloudCapabilityPostgresIT`、`OpenAiImage2PostgresIT`、`GoogleNanoBananaPostgresIT`：通过。
- `deploy/update-local.sh`：最终重建完成；镜像内后端 131 项单元测试、前端 TypeScript 与生产构建通过，PostgreSQL、Server、Web 三个容器健康，原数据库和素材卷保留。
- 浏览器：先在 Vite 入口完成同屏和 1280px 修正，再重建 Docker，并在 `http://localhost:8088/projects/7416dce1-9e6b-4508-a1c4-1ded65ff868d` 复核最终生产构建。真实图片卡片可展开全部参数；实测选择 `9:16 / 2K / low / 4 / 新节点开启` 后摘要变为 `9:16 · 2K · 低 · 4 张` 并自动保存，随后已恢复原草稿。详细视觉记录见根目录 `design-qa.md`。

## 未验证限制

- 未向 OpenAI、Google 或 ComfyUI 发起真实付费生成，不能据此声称真实 Provider 已完成画质、透明背景、4K 或批次效果验证；现有云渠道检查使用假 HTTP 服务。
- 未在浏览器中点击运行真实批次，避免产生外部成本；Task 数量和新节点行为由真实 PostgreSQL 集成测试验证。
- 费用目录尚无图片参数对应的已知价格，界面继续显示“费用未知”，每个 Task 的用量账本仍独立。
