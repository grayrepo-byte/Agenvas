# 图片与视频创作模板

2026-10-02 用户确认的功能：仅在图片与视频节点编辑栏选择模板，明确区分图片模板与视频模板，把固定风格/视频动作提示词和可选图片资源填入草稿；用户可以创建个人模板，管理员可以在系统设置维护系统模板。文字和音频节点不提供模板功能，音频移除原有静态声音模板入口。界面参考用户提供的选择与创建模态框，字体、主题和组件沿用系统现有设计。

## 内容与作用域

- 模板用途为 IMAGE 或 VIDEO，包含名称、普通文字提示词、按顺序保存的参考图片和元数据版本。名称最长 160 字符，提示词最长 20,000 字符，参考图片最多 8 张；实际可应用数量仍由当前生成能力限制。
- PERSONAL 模板只由本人读取和维护；SYSTEM 模板由管理员维护，登录用户可以读取和应用。系统管理写权限由服务端校验，不把请求中的作用域当作权限。
- 从当前草稿保存的参考图片固定精确版本并复制到独立私有归档；上传采用现有实际图片验证。提示词中的结构化图片提及保存为可读文字，模板不持有原草稿标签身份。
- 模板卡片使用首张参考图作封面；无图模板显示提示词摘要。初始模板列表可以为空，由用户或管理员添加内容。

## 应用与编辑

模板选择按当前编辑器图片/视频用途筛选，支持名称/提示词搜索、全部模板和我的模板。用户先查看提示词和图片，再显式应用。纯文字模板只替换提示词并清除旧提及，保留引用；含图片模板明确说明会替换当前引用，再整体应用全部图片。不存在逐图失败后只替换一半草稿的行为。

保留当前能力、参数、时长和风格选择。视频要显式采用当前能力支持的图片输入模式，图片数量超限或能力不支持图片时阻止应用；具名素材槽位继续遵守原输入契约。应用成功后可使用原编辑器替换、移除和重排参考图，生成仍由用户点击运行并完成原有预检。

服务端把模板图片复制为当前项目独立资源及不可变版本，不创建画布节点、连线或生成任务。纯提示词模板整体填入本地草稿，随后沿用现有保存与版本 CAS。带图模板获得全部导入版本后，通过完整输入替换接口原子清理原引用的连线并保存新草稿；替换先取得项目事件锁再修改草稿，遵循普通保存与连线操作相同的锁顺序。普通保存刻意保留连线来源，不能用它模拟引用替换，也不能分多次移除请求。导入或替换失败保留原输入；保存失败或冲突继续保留用户草稿并显示恢复操作。刷新、关闭和失败恢复仍受现有草稿生命周期约束。

## 持久化与接口

模板和图片元数据由独立模块维护，图片文件复用私有媒体归档应用服务，项目导入通过项目媒体与产物应用服务完成。生产持久化使用 jOOQ，schema 由只增 Flyway 迁移建立，再从一次性 PostgreSQL 生成源码。普通构建不连接生成数据库。

公开图片 DTO 只提供安全的同源预览地址与媒体元数据，不提供账号目录、存储键、文件路径或密钥。个人图片读取要检查所有权；系统共享图片读取要检查其被可见系统模板使用。图片复制和导入不调用外部 Provider。未被模板或已受理导入使用的本人新上传图片可以显式清理；已受理导入固定的源图片保留，不实现自动过期/垃圾回收。恢复模式禁止模板写入。

本次增加 Flyway V74，建立模板、有序附件、独立图片元数据、持久导入命令/源图片固定关系，以及项目版本的模板来源记录。项目参考图通过受信任目录导入建立 `LIBRARY_IMPORT` 不可变内容，模板来源单独记录在模板模块；不伪造个人 LibraryEntry。jOOQ 已从一次性 PostgreSQL 17.11 重新生成。

| 接口 | 行为 |
| --- | --- |
| `GET/POST /api/v1/media-templates`、`GET/PATCH/DELETE /api/v1/media-templates/{templateId}` | 可见模板目录，以及本人个人模板的创建、读取、版本化修改和删除。列表可按 targetKind、scope、query 筛选。 |
| `POST /api/v1/settings/media-templates`、`PATCH/DELETE /api/v1/settings/media-templates/{templateId}` | 管理员维护系统模板；另一管理员可以保留已有附件，不能附加他人私有图片。 |
| `POST /api/v1/media-templates/images`、`POST /api/v1/media-templates/images/from-version` | 上传独立参考图片，或复制本人可访问项目的精确图片版本。 |
| `GET /api/v1/media-templates/images/{imageId}/thumbnail`、`GET/HEAD .../content`、`DELETE /api/v1/media-templates/images/{imageId}` | 鉴权预览/读取，以及清理本人未被使用的图片。 |
| `POST /api/v1/projects/{projectId}/media-templates/{templateId}/import` | 以 expectedTemplateVersion 和 commandKey 固定模板，返回提示词、类型与同项目精确图片版本；不保存草稿或生成。 |
| `POST /api/v1/projects/{projectId}/canvas-items/{itemId}/media-draft/replace-inputs` | 以原 SaveMediaDraftRequest/CAS 原子替换完整输入及其连线；前端完成模板预览后显式调用。 |

模板编辑与删除使用 expectedVersion；导入通过项目与 commandKey 保存完整结果，同键同参返回相同版本引用，同键异参返回冲突。模板后续修改或删除不改写已导入的项目资源。系统升级同步部署 Java、OpenAPI、生成 TypeScript、前端与新增迁移；已有项目、草稿和生成结果保留。

## 第三方提示词库

图片与视频模板新增“第三方模板”目录，从 PostgreSQL 缓存分页读取，支持来源和文本筛选。管理员在同一设置页管理来源、手动同步，或添加公开 HTTPS 标准 JSON 数据地址。同步不修改个人模板或已经导入的项目内容。

图片原生格式使用 `id/sourceId/title/prompt/description/coverUrl/referenceImageUrls/tags/author/sourceUrl/createdAt/imageMode/imageModel`，与用户指定格式一致。`imageMode` 为 `generate` 或 `edit`。效果图只作封面，只有明确标注的输入素材进入 `referenceImageUrls`。需要图片但上游未公开输入图的编辑模板，在应用前要求草稿已有图片。

视频有独立结构，共享身份和展示字段，输入使用 `videoMode/videoModel/references/imageGeneration`。`videoMode` 为 `text_to_video`、`image_to_video`、`video_reference`、`omni_reference`（全能参考）或 `text_to_image_to_video`。每个 reference 保存 `kind`（IMAGE/VIDEO/AUDIO）、`role`（REFERENCE/START_FRAME/END_FRAME/VIDEO_REFERENCE/AUDIO_REFERENCE）和 `url`。图生视频必须有明确图片引用；首尾帧与全能参考按各自角色保存，不能用封面替代输入。可选 imageGeneration 保存前置图片提示词、图片模型及图片引用；浏览时展示完整阶段，应用前要求先准备图片，不自动执行生成流水线。

原生视频示例：

```json
{
  "id": "example-video:001",
  "sourceId": "example-video",
  "title": "Product orbit",
  "prompt": "Slowly orbit around the product while keeping its shape consistent.",
  "description": "",
  "coverUrl": "https://example.com/preview.jpg",
  "tags": ["product"],
  "author": "Example Author",
  "sourceUrl": "https://example.com/original",
  "createdAt": "2026-10-08",
  "videoMode": "image_to_video",
  "videoModel": "example-video-model",
  "references": [{"kind": "IMAGE", "role": "START_FRAME", "url": "https://example.com/input.jpg"}],
  "imageGeneration": null
}
```

原生来源返回相应媒体结构的 JSON 数组或 `{ "items": [...] }`，id 必须包含当前 sourceId 前缀。非标准上游通过 `ThirdPartyPromptAdapter` 转换后统一验证。首批来源参考 [Infinite Canvas 的第三方列表](https://docs.canvas.best/docs/overview/third-party-prompt-repositories)：ZeroLu、ImgEdify、YouMind GPT Image 2、YouMind Nano Banana Pro 的 README 和 David 的 prompts.json。YouMind 的 README 只公开部分提示词，缓存范围是实际公开的数据，不把网页总量当作已导入量。

Flyway V15 增加独立来源、缓存及导入命令表，jOOQ 从隔离 PostgreSQL 重新生成。原生源使用上游 ID；Markdown 无原生 ID 时使用发布地址与条目标题的组合，避免同一原帖中的不同提示词被合并。正文或顺序变化不会产生新身份；无上游 ID 的条目改名会新增并保留旧记录。同步仅 upsert，相同内容不增加版本，上游缺失、停用和失败均不删除历史记录。成功后 24 小时再次同步，失败保留上次成功时间并一小时后重试；扫描默认每分钟，首次启动约一分钟后开始。`AGENVAS_PROMPT_SYNC_ENABLED=false` 关闭自动扫描，管理员手动同步仍可用。恢复模式禁止手动同步和后台写入。

同步使用独立有界 worker，避免占用媒体任务恢复的共享调度线程。短事务认领十分钟租约，网络读取在事务外进行；发布前锁定并核对 fencing token，停用源使在途结果失效。异常只保存稳定错误码。HTTPS GET 校验公开 DNS，拒绝地址凭证、私有/本地地址、重定向、隐式重试及超限响应。

导入以项目命令键冻结缓存版本及引用顺序，在事务外归档为项目私有素材，然后原子发布全部不可变版本和事件。同键重放复用原快照与已归档字节，上游改变或下线不影响已导入内容。全能参考中的图片、视频与音频整体替换引用；能力模式、各类数量限制和具名图片槽位在导入前校验。暂不在动态工作流中映射第三方视频/音频槽位，明确阻止应用并保留原草稿。三方模型只作来源信息，不改写当前能力、参数和时长，也不触发生成。

新增接口：`GET /api/v1/media-templates/third-party` 与 `.../sources`；管理员 `POST /api/v1/settings/media-template-sources`、`PATCH .../{sourceId}`、`POST .../{sourceId}/sync`；项目 `POST /api/v1/projects/{projectId}/media-templates/third-party/import`。写操作继续受认证、管理员边界、CSRF 和恢复模式约束。合约和生成 TypeScript 同步更新。

## 验收边界

需定向验证个人隔离、系统写权限与 CSRF、图片上传/固定版本复制、模板 CRUD 的 CAS、导入命令重放与异参冲突、来源删除后独立复用，以及前端提示词/引用整体填充、容量和模式限制、失败保留草稿、不触发生成。实际运行结果记录在开发清单；Mock 与合成图片不证明真实 Provider 接通。全量测试、真实 Provider 与部署验证分别据实记录。
