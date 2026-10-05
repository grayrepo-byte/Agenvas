# ADR 0001：用户上传图片与生成图片的来源分支

状态：接受（2026-09-23，MVP 开发期）

## 背景

MVP 允许用户上传参考图并绑定给 Agent。此前 IMAGE 内容只支持生成产物字段，强制要求 `sourceTaskId`、Provider 配置与工作流版本；普通上传只产生 Asset，没有生成 Task。用随机 Task ID 填充这些字段会制造虚假来源，且无法向用户解释。

## 决策

- IMAGE 内容保留现有生成分支；新增互斥的用户上传分支 `{ "sourceType": "UPLOAD", "assetId": "UUID" }`。上传分支不得携带 `sourceTaskId`、Provider 配置、工作流或 Prompt。
- 服务端仍核验 `assetId` 是同项目、已归档、可读的 IMAGE Asset；上传分支只允许 `CreatedByKind.USER`。Agent 和生成 Task 不能声明用户上传来源。
- 上传先产生 Asset，再由用户命令创建 IMAGE Artifact/Version 和 CanvasItem。失败时不得伪装为整个操作成功；文件/标题草稿保留。相同文件与标题重试时复用本页面已确认的 Asset、Artifact 和卡片 ID；请求结果不明时仍须先刷新核对。后续孤儿 Asset 清理另行设计。
- 这是开发期对 `image-v1` 内容集合的加法扩展；旧生成内容和历史版本无需迁移。OpenAPI 外部 Schema 与生成 TS 类型同改；不增加第二套媒体身份。

## 后果

用户上传图成为可绑定的精确 ArtifactVersion，默认模型规划仍只读取必要内容 JSON，不自动发送图片字节。`sourceType` 表示用户声明和创建的来源分支；当前没有独立的 Asset 级来源证明。浏览器 multipart、三步写入失败后的孤儿核对和完整浏览器端到端验收仍需单独验证。
