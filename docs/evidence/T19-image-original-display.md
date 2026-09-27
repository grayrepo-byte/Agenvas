# 图片卡片改显归档原图

2026-09-27。对应主规格 §8 媒体检查、§16.3 下载与播放、§19.3 画布性能。

- 变更行为：图片卡片、关键帧选择面板和视频首帧参考图改为直接读取归档原文件。图片缩略图仍然制作并保存（最长边 480 像素 PNG，入库时生成、崩溃恢复时按需重建），与视频封面共用 `asset.thumbnail_*` 与 `/assets/{assetId}/thumbnail`；只是这三个界面不再加载它。保留预览的原因是后续有需要低带宽/低解码成本的列表功能要接入。视频行为完全不变：仍提取封面帧，卡片先显示封面、用户显式播放才加载原视频。
- 读取：画布图片改走 `/content`，即原有的私有 GET/HEAD/Range 通道，鉴权、Content-Type 与流式读取语义不变；`/thumbnail` 对图片仍返回 200。前端仅 `MediaCanvasCard` 的视频分支保留 `assetThumbnailUrl`，图片分支与另外两处预览改用 `assetContentUrl`。
- 合约/迁移：`contracts/openapi.yaml` 只改 `getAssetThumbnail` 的 summary/description，`Asset` schema 本就不暴露 `thumbnail_*`，重新生成的 `schema.ts` 只改注释、无类型变化。V48 曾按“图片不再保留预览”改写列注释，该迁移已应用到本地库，因此不改写它，另加 V50 把注释改回图片预览与视频封面并存的语义。两版迁移都只动注释，列、唯一约束与既有素材的 `.thumb.png` 均未改动。
- 实际检查：`./mvnw -Dit.test=AssetPostgresIT,AssetDiskFullPostgresIT verify` 通过（V50 已应用；图片仍生成 480×240 缩略图、`/thumbnail` 对图片返回 200、任务键恢复会重建缺失缩略图、`.thumb-` 前缀 ENOSPC 注入覆盖缩略图写入失败）。`./mvnw -Dit.test=MockStoryboardPostgresIT,ProjectExportManifestPostgresIT,ResourceScopePostgresIT,TaskRecoveryPostgresIT,ComfyUiImagePostgresIT,OpenAiImage2PostgresIT,GoogleNanoBananaPostgresIT verify` 通过。前端 `tsc --noEmit`、`eslint --max-warnings=0`、`vitest run` 受影响 4 个文件 48 项通过；`api:generate` 重新生成后仅注释变化。
- 未完成：未做浏览器实测（卡片放大后是否确实清晰、原图加载的带宽与内存表现均未观测）。原 T28 画布容量结论以缩略图加载为前提，改用原图后需要重新测量才能继续引用，见 `T28-canvas-capacity.md`。

## 两次提交

`1579513` 先把图片卡片、关键帧面板与视频首帧参考图改成读取归档原图，同时停掉图片缩略图生成与保存；随后按“后续功能仍需要缩略图”的决定恢复生成与保存，界面继续使用原图。恢复后 `LocalAssetStorage`、`AssetService`、`Asset`、`AssetController` 与两个集成测试回到 `1579513` 之前的状态，额外的只有 `writeThumbnail` 上说明用途的注释、前端三处注释，以及 V50 的列注释修正。
