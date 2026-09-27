# 图片卡片改显归档原图

2026-09-27。对应主规格 §8 媒体检查、§16.3 下载与播放、§19.3 画布性能。

- 变更行为：图片卡片、关键帧选择面板和视频首帧参考图改为直接读取归档原文件。归档图片时不再生成最长边 480 像素的 PNG 预览副本：`LocalAssetStorage.storeImage` 只安装原图，`StoredImage` 不再携带缩略图键/大小/摘要，`AssetService.publishImage` 为 `thumbnail_*` 写 NULL，`recoverImage` 不再重建预览、也不再因孤立的 `.thumb.png` 报错。视频行为不变：`storeVideo`/`recoverVideo` 继续用受限 FFmpeg 提取 `scale=480:-2` 的封面帧，视频卡片仍先显示封面、用户显式播放才加载原视频。
- 读取：`/assets/{assetId}/thumbnail` 保留但只服务视频封面；图片素材因 `thumbnail_key` 为空返回 404 `ASSET_THUMBNAIL_NOT_FOUND`。图片卡片改用 `/content`，即原有的私有 GET/HEAD/Range 通道，鉴权、Content-Type 与流式读取语义不变。前端仅 `MediaCanvasCard` 的视频分支、`assetContentUrl` 的调用点与三处图片预览 URL 发生变化。
- 合约/迁移：`contracts/openapi.yaml` 只改 `getAssetThumbnail` 的 summary/description，`Asset` schema 本就不暴露 `thumbnail_*`，重新生成的 `schema.ts` 只改注释、无类型变化。Flyway V48 只 `COMMENT ON COLUMN` 更新 `asset.thumbnail_key`/`thumbnail_byte_size`/`thumbnail_sha256` 的说明，不删列、不回填、不改约束；V21 的列与唯一约束仍然有效，视频封面继续使用。既有图片素材遗留的 `.thumb.png` 成为未登记孤儿文件，不参与 READY 判定。
- 实际检查：`./mvnw -Dit.test=AssetPostgresIT,AssetDiskFullPostgresIT verify` 通过（含 V48 迁移；图片无 `.thumb.png`、`getThumbnail` 抛 `ASSET_THUMBNAIL_NOT_FOUND`、HTTP 404/401 边界、任务键原图恢复与双 Worker 竞争）。`./mvnw -Dit.test=MockStoryboardPostgresIT,ProjectExportManifestPostgresIT,ResourceScopePostgresIT,TaskRecoveryPostgresIT,ComfyUiImagePostgresIT,OpenAiImage2PostgresIT,GoogleNanoBananaPostgresIT verify` 通过（7 个 IT、125 个单元测试）。前端 `tsc --noEmit`、`eslint --max-warnings=0`、`vitest run` 受影响 6 个文件 61 项通过；`api:generate` 重新生成后类型无差异。`AssetDiskFullPostgresIT` 的原图 ENOSPC 注入从 `.thumb-` 前缀改为 `.ingest-` 前缀，覆盖原图/MP4/导出三段写入失败。
- 未完成：未做浏览器实测（卡片放大后是否确实清晰、原图加载的带宽与内存表现均未观测）。原 T28 画布容量结论以缩略图加载为前提，改用原图后需要重新测量才能继续引用，见 `T28-canvas-capacity.md`。未验证多张高分辨率原图同屏时的解码内存与滚动性能，也未做真实 Provider 大尺寸输出的画布实测。
