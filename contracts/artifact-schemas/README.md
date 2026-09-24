# Artifact content schemas

本目录是 ArtifactVersion `schemaVersion: 1` 的内容合约。文件使用 JSON Schema 2020-12；服务端在写入不可变版本前执行同等的字段、长度、枚举、UUID 与语义引用校验。Schema 不包含 `ownerId`、存储路径、审批结果等受保护字段。

- `text-v1.schema.json`
- `character-v1.schema.json`
- `scene-v1.schema.json`
- `shot-v1.schema.json`
- `image-v1.schema.json`
- `video-v1.schema.json`

IMAGE/VIDEO 中的 `assetId` 先验证 UUID 形状，再由服务层核验同项目、媒体类型匹配且已归档可读。IMAGE 新增互斥的 `sourceType: UPLOAD` 分支，仅包含已归档 `assetId`，用于用户上传参考图，不伪造 Provider 配置或 Task ID；现有生成分支原样保留。`sourceTaskId` 由 Task 成功路径校验与当前 Task 一致；普通手工生成形状版本仍只做 UUID 形状校验。VIDEO 可记录精确 `keyframeVersionId`，服务端抽取为 IMAGE 类型引用并由组合外键校验同项目与存在性。此字段是 0.1.0 开发期的加法变更，旧内容不强制补写；Mock 视频新版本总会填写。
