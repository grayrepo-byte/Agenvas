# 画笔标注修正验收（2026-09-30）

## 行为与改动

按用户设计实现直接图片编辑，八种颜色、画笔、橡皮、矩形、箭头、文字、笔刷大小、撤销/重做。预览标注为独立层，擦除不碰原图；保存按原像素尺寸合成 PNG。浏览器草稿保留至保存成功，失败可重试，来源版本固定于编辑开始。

服务端使用现有私有 Asset 上传、CanvasItem 上传版本与派生流程，新增 `MediaUploadPurpose`。保存 BRUSH_MARKUP 需独立目标 ID、精确来源图片版本和节点 CAS；创建不可变 USER 版本，记录 baseVersionId 与 frozenInput，创建空白媒体草稿和 MEDIA_DERIVATION。重放验证相同内容、用途和来源；不会调用 Provider 或创建 Task。普通上传行为继续保持。

变更文件包括 BrushMarkupEditor.tsx/.css、brushMarkup.ts、MediaCanvasCard.tsx，CanvasItemController、CanvasService、ArtifactService、MediaUploadPurpose 和 ImageOperation/DirectMediaTaskService，以及对应测试。OpenAPI 从生成命令移除 BRUSH_MARKUP，上传请求增加 purpose 与 sourceVersionId；已重新生成 TS，前后端同时升级，无数据库迁移。决定见 [ADR 0018](../adr/0018-local-brush-markup.md)。

## 实际检查

- 前端 BrushMarkupEditor、MediaCanvasCard、MediaCardUpload：60 测试通过。Canvas API/绘图环境使用显式 Mock，覆盖撤销重做分支、文字、尺寸入口、保存失败复用文件与目标 ID、刷新固定原图、409 保留标注、载入失败与关闭。
- 后端 ImageOperationSpecTest 3 项、CanvasMediaDerivationDraftTest 5 项通过。
- CanvasMediaContextPostgresIT 在真实 PostgreSQL 17.11/Testcontainers 通过，覆盖普通上传、标注派生、空白草稿、来源不变、USER/精确来源记录、幂等重放、来源冲突及旧 AI 标注命令 400。
- TypeScript 检查、相关文件 ESLint、Vite 生产构建、git diff --check 通过。
- Chrome 实测完整绘制与保存路径，刷新后两个节点仍存在。独立数据库最终 2 节点、1 派生线、0 Task。
- 导出 PNG 与源图均 161 × 286；未标注底部区域逐像素相同。
- 截图与设计对比见 [design-qa](../../design-qa.md)。

## 限制

没有运行全量测试；没有真实 Provider 调用，标注也不需要 Provider。没有进行大图压力测试、多浏览器矩阵或真实外部断网测试；响应丢失/冲突由组件与 PostgreSQL 专项检查覆盖。保存是扁平图片，不能恢复本次绘制图层。既有 8088 Compose 服务未重建，Chrome 本地预览为独立测试环境。
