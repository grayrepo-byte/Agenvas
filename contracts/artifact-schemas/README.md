# Artifact content schemas

本目录是 ArtifactVersion `schemaVersion: 1` 的内容合约。文件使用 JSON Schema 2020-12；服务端在写入不可变版本前执行同等的字段、长度、枚举、UUID 与语义引用校验。Schema 不包含 `ownerId`、存储路径、审批结果等受保护字段。

- `text-v1.schema.json`：文字正文允许空字符串，作为直接创建文字节点的初始不可变版本；format 与 text 字段仍必填。
- `image-v1.schema.json`
- `video-v1.schema.json`

IMAGE/VIDEO 中的 `assetId` 先验证 UUID 形状，再由服务层核验同项目、媒体类型匹配且已归档可读。IMAGE 的 `sourceType: UPLOAD` 分支仅包含已归档 `assetId`，用于用户上传图片，不伪造 Provider 配置或 Task ID；生成分支保留可验证的工作流元数据。`sourceTaskId` 由 Task 成功路径校验与当前 Task 一致。媒体的图片来源不再写入 IMAGE/VIDEO 内容字段；Task 和成功 ArtifactVersion 通过同一份 `frozenInput` 保存有序精确版本、角色、模式与结构化标签。
