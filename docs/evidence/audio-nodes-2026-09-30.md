# 音频节点与全能参考专项证据

2026-09-30 开始，2026-10-01 收尾。实现基于 `b028ceb`，提交记录以 Git 历史为准，未发布 PR。产品决定见 [ADR 0021](../adr/0021-audio-nodes-and-mixed-references.md)，视觉对照见 [design-qa](../../design-qa.md)。

## 行为与代码范围

新增 AUDIO 产物、Asset、节点草稿/版本、直接任务、费用预检、调用日志和默认能力。支持实际解码上传、鉴权 Range 播放、真实波形/进度、下载、节点内重新生成、版本选用、替换上传派生节点。空态与内容态按设计区分，主题沿用项目系统。

Seed Audio 1.0 使用固定官方接口 `openspeech.bytedance.com/api/v3/tts/create`，单 API Key 服务端加密且在受理时冻结；支持音色、语速/音量/音高、音频参考或单图参考。响应丢失、重定向或非法成功响应保持 UNKNOWN，不自动重复付费提交；字节已安装时只恢复归档。官方音色选择保存 speaker ID，明确生成试听才创建独立音频任务/节点。Mock 只生成提示音并禁用音色试听。

全能参考保存图片与音频精确版本，分别编号和渲染结构化提及，接受连线、资源或上传来源。模式切换确认清除不兼容引用，能力变化不静默裁剪。MV 制作固定当前选用音频版本，新建视频草稿，用户显式运行。Seedance 2.0 提交官方混合协议并启用音轨；Mock 视频实际合成参考音频。

模板、展开、提示词助手和翻译复用现有组件/文字任务，先预览后明确应用并保护变化后的原草稿，不向文字模型发送音频。内容态 Agent 入口复用既有卡片，固定选用版本，只读取元数据与文字上下文，打开不自动运行。

实现集中于 `backend/src/main/java/dev/agenvas/{artifact,asset,canvas,provider,task}`、`frontend/src/features/canvas/{AudioPlayer,VoiceLibrary,AudioPromptTools,MediaDraftEditor,MediaCanvasCard,ProjectWorkspacePage}`；配置与日志页面位于 `frontend/src/features/settings/`。沿用任务租约/fencing、幂等、SSE、项目鉴权、不可变版本和选择 CAS。

## 合约、迁移与升级

- `contracts/openapi.yaml` 增加 AUDIO、VOLCENGINE、上传与音频参数/上限；输入字段统一 `mediaInputs`，删除路由改为 `/media-draft/media-inputs/{versionId}/remove`。TS schema 通过生成器更新，未手改生成类型。
- 新增 `V63__audio_media_support.sql`，扩展约束、输入角色与 Mock 默认音频能力。V1..V62 未改；独立 PostgreSQL 17.11 成功执行迁移至 V63，据此重新生成 `backend/src/jooq/java/`，构建期不连库。
- 导出 schemaVersion 4 包含音频和混合引用。前后端及导入方需同步升级，不保留旧 `imageInputs` 兼容字段。部署前备份数据库与文件卷。
- 同步 AGENTS、CONTEXT、README、MVP 规格、开发清单和 ADR 0021，记录用户确认的四类产物扩展。

## 实际检查

后端 Java 21、Maven Wrapper、Spring Boot 4、真实 PostgreSQL 17.11 Testcontainers，本机 FFmpeg/ffprobe。浏览器使用独立测试库/文件目录和 Mock 能力，后端 18081、Vite 15173，未改用户原运行服务。HTTP 测试仅用本地假服务；Seed 适配器集成测试拦截传输，使用测试 Key。

| 后端定向类 | 例数 | 结果 |
| --- | ---: | --- |
| SeedAudioClientTest | 4 | 通过：正文/认证、非法响应、重定向和丢失响应不重发 |
| ArkSeedanceClientTest | 4 | 通过：混合协议、音轨及既有请求 |
| MediaAdapterRegistryVideoModesTest / MediaCapabilityConfigurationTest | 8 | 通过：能力声明、模态上限和校验 |
| AudioMediaPostgresIT | 1 | 通过：音频生命周期、真实解码/数据库、被拦截的 Seed 适配器 |
| VersionedMediaInputPostgresIT / CanvasMediaVersionsPostgresIT / MediaDraftPostgresIT | 9 | 通过：引用、节点历史、选择/草稿 |
| ProjectExportManifestPostgresIT | 1 | 通过：schema 4 |
| DirectMediaGenerationPlacementPostgresIT / ImageOperationDerivationPostgresIT / CanvasMediaContextPostgresIT | 3 | 通过：图片放置、派生、上下文回归 |

共 12 类、30 例；最终对应 Surefire XML 均 0 failure / 0 error / 0 skipped。分别用 `./mvnw -q -Dtest=<指定类> test` 运行，不是全量测试。初次部分批次发现测试身份缺管理员角色与旧删除路由，更新 fixture/断言后复跑通过。新增 Seed 适配器测试补齐测试加密主密钥及固定模型的空配置后最终通过。

AudioMediaPostgresIT 包含：真实 WAV/误导文件名的实际 MIME、非法字节 422；Range 206、跨项目 404；不可变上传/重新生成与选择冲突；ffprobe 验证混合 Mock 视频 AAC 音轨；UNKNOWN 明确新尝试/幂等/保留旧尝试；取消后不执行；归档失败恢复字节不重生成；Seed 配置解密、参数/提及/精确字节、非 Mock 归档及 UNKNOWN 不自动重发。此类的 Seed 传输由 Mockito 拦截；真实 HTTP 协议另由 SeedAudioClientTest 验证，均不能证明云端已接通。

前端定向入口（实际分批执行）：

```sh
cd frontend
pnpm exec vitest run src/features/canvas/AudioPromptTools.test.tsx \
  src/features/canvas/MediaDraftEditor.test.tsx \
  src/features/canvas/MediaCanvasCard.test.tsx \
  src/features/canvas/ProjectWorkspacePage.test.tsx \
  src/features/settings/MediaSettingsPage.test.tsx \
  src/features/canvas/CanvasConnectionDrop.test.tsx \
  src/features/canvas/CanvasRelationDeletion.test.tsx \
  src/features/canvas/CanvasSelectionClearing.test.tsx \
  src/features/canvas/ProjectWorkspaceImageLayout.test.tsx \
  src/features/canvas/canvasRelations.test.ts
pnpm typecheck
pnpm lint
pnpm build
```

10 文件、186 例通过。最终音色库布局修正后 MediaDraftEditor/MediaCanvasCard 两文件 86 例复跑通过，是前述子集，不重复计数。最终类型检查、lint（含主题检查）、生产构建通过。构建提示大 chunk，jsdom 有 pause 未实现提示；实际浏览器播放/暂停已验证，未屏蔽日志。git diff --check 无空白错误。

日志在本机 `/tmp/agenvas-audio-{targeted-final,popover-final,production-build,seed-adapter-final}.log` 等专项文件；Surefire 在 `backend/target/surefire-reports/`。这些临时输出不作为提交证据，上述记录保留命令、覆盖与实际结果。

## 浏览器证据

| 实测 | 证据与结果 |
| --- | --- |
| 创建音频/空态 | [empty.jpg](audio-ui-2026-09-30/empty.jpg)：上传可用、隐藏空态 Agent 入口 |
| Mock 生成、播放暂停/进度/真实波形 | [content.jpg](audio-ui-2026-09-30/content.jpg)：3 秒 WAV、默认暂停、明确非语音合成 |
| 下载再上传、全局导入/空节点上传 | [uploaded.jpg](audio-ui-2026-09-30/uploaded.jpg)：实际 AUDIO 归档与播放 |
| 音色搜索、英文筛选、收藏和 Vivi 选择 | [voices.jpg](audio-ui-2026-09-30/voices.jpg)：双列官方目录，Mock 试听禁用并说明费用 |
| 模板、展开、助手预览/应用 | [prompt-assistant-mock.jpg](audio-ui-2026-09-30/prompt-assistant-mock.jpg)：正常文字任务 Mock 返回，非真实助手质量 |
| 翻译 | 独立文字节点/英语目标任务可见；未保存翻译预览截图，不声称真实翻译效果 |
| MV 草稿/显式运行 | [mv-draft.jpg](audio-ui-2026-09-30/mv-draft.jpg)：固定音频版本、全能参考；随后生成 5 秒带音轨 Mock 视频 |
| v2 重新生成后选回 v1 | [versions.jpg](audio-ui-2026-09-30/versions.jpg)：字节切换、草稿与音色保留 |
| Agent 对话 | [agent-conversation.jpg](audio-ui-2026-09-30/agent-conversation.jpg)：标准 Agent 卡片精确绑定并保持空闲；单测另断言零 Run |
| 刷新 | 选用结果/草稿持久化，最终内容截图来自数据库状态 |

模式清除、冲突/失败、UNKNOWN、拖动隐藏工具栏和批量布局保存由单元/数据库测试覆盖，未在浏览器逐个注入所有故障。设计全图/局部并排和修正历史见根目录 design-qa.md。

## 未验证范围

真实 Seed Audio 生成/官方音色试听、真实 Seedance 音频参考未运行，未使用用户云端 Key 或产生云端费用。完成的是生产协议、配置、任务、归档代码及假 HTTP/真实 PostgreSQL/Mock 验证，不能用提示音证明云端效果。真实语音、助手/翻译质量、实际账单、长音频/大量节点性能和移动编辑未验证；按项目要求未运行全量测试。临时验收服务与配置收尾清理，不作为交付入口。
