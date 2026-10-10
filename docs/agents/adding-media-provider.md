# 媒体 Provider 接入与能力切换

新增媒体 Provider、扩展适配器协议或修改节点能力切换时，按本指南确定改动范围与验收场景。项目代码使用 `Provider`；接入完成包括管理员配置、画布创作、与既有能力双向切换、任务执行和恢复。

功能规则以 [MVP-SPEC](../MVP-SPEC.md)、[OpenAPI 合约](../../contracts/openapi.yaml)和现行 ADR 为准，本文件提供实施与检查入口。先读规格 §13、[ADR 0002](../adr/0002-fixed-media-adapters-before-workflow-platforms.md)和 [ADR 0019](../adr/0019-media-capability-defaults-limits-and-pricing.md)，再按输入类型与执行方式阅读 AGENTS.md 中对应资料。

## 1. 确定接入范围

| 请求 | 实施范围 |
| --- | --- |
| 现有协议下增加连接或兼容模型 | 优先使用已有地址、凭据和模型配置；核对实际请求与能力边界 |
| 增加 RunningHub、ComfyUI 或 AutoDL 同协议工作流 | 使用已有导入、映射和发布能力；输入或输出超出已支持协议时再扩展实现 |
| 新增独立平台或协议 | 增加固定 Java 适配器、客户端、能力声明，并检查下文全部链路 |
| 修改能力参数或输入限制 | 检查配置发布、草稿兼容性、显式切换、运行冻结与历史任务恢复 |

LLM Provider 走独立入口：规格 §13.3、`settings/application/LlmProviderConfigService.java` 与 `llm/infrastructure/StoredChatModelFactory.java`。当前保存配置构造 OpenAI 协议客户端；其他原生协议需要另行核对配置模型、Spring AI Gateway、流式处理、诊断及 LLM 设置页。

先形成一份接入说明，写明以下内容，再开始修改：

- 平台、协议、模型或工作流身份，以及此次支持的输出类型。
- 地址策略、凭据形态、输入模式、参数集合、默认值、可配置范围、必填素材与限制。
- 同步或异步返回、远程任务 ID、查询、幂等、取消、结果有效期及下载方式。
- 选取哪些现有能力作为双向切换对象，以及每类参数和素材的保留、回退、移除或阻断行为。

以供应商正式协议和本次验证确定能力；管理员配置只能在适配器支持的范围内收紧限制。完成条件：每项能力都有实现与验收方式，未实现的能力明确关闭。

## 2. 检查后端、合约与数据库

下表是检查范围；能由已有通用机制承载的文件可复用，实际修改范围取决于新协议差异。

下列 Java 路径相对 `backend/src/main/java/dev/agenvas/`。

| 入口 | 检查与实施内容 |
| --- | --- |
| [MediaPlatform.java](../../backend/src/main/java/dev/agenvas/provider/domain/MediaPlatform.java) | 独立平台增加枚举；既有平台的新适配器沿用原平台身份 |
| [MediaAdapterRegistry.java](../../backend/src/main/java/dev/agenvas/provider/domain/MediaAdapterRegistry.java) | 增加稳定 adapterId、平台归属与实际输入能力声明 |
| `provider/application/XxxAdapter.java` | 实现 [MediaAdapter](../../backend/src/main/java/dev/agenvas/provider/domain/MediaAdapter.java)，作为 Spring Bean 注册；加载固定能力、连接版本及已授权素材，提供预检、提交、查询和结果下载 |
| `provider/infrastructure/XxxClient.java` | 实现固定 HTTP 协议、认证、超时和错误分类；验证出站地址、DNS、重定向、下载来源、媒体类型和大小；凭据只用于授权请求 |
| [MediaCapabilityService.java](../../backend/src/main/java/dev/agenvas/provider/application/MediaCapabilityService.java) | 平台地址和凭据校验、适配器归属、配置字段白名单、归一化和模型元数据；发布与更新使用不可变版本、摘要、幂等及 CAS |
| [MediaCapabilityConfiguration.java](../../backend/src/main/java/dev/agenvas/provider/domain/MediaCapabilityConfiguration.java) | 默认值、可收紧限制与价格；新参数超出现有配置语义时同步扩展 |
| [MediaDraftService.java](../../backend/src/main/java/dev/agenvas/artifact/application/MediaDraftService.java) | 草稿参数、输入角色、精确版本授权与结构化标签校验；允许保存尚未满足运行条件的草稿 |
| [DirectMediaTaskService.java](../../backend/src/main/java/dev/agenvas/task/application/DirectMediaTaskService.java) | 运行前校验完整输入、供应商专项限制，固定有效参数、素材、能力/连接版本及价格；检查已有 adapterId 分支 |
| [MediaFunctionService.java](../../backend/src/main/java/dev/agenvas/provider/application/MediaFunctionService.java) | 接入图片/视频后处理时核对功能兼容白名单、透明背景和工作流输入条件；支持蒙版时同时核对直接任务的蒙版预检与适配器请求 |
| [MediaDraftRestoreService.java](../../backend/src/main/java/dev/agenvas/canvas/application/MediaDraftRestoreService.java)、[CanvasConnectionService.java](../../backend/src/main/java/dev/agenvas/canvas/application/CanvasConnectionService.java) | 能力切换时保留有效素材来源连线，草稿与拓扑更新使用同一项目锁、事务及 CAS |
| [MediaExecutionWorker.java](../../backend/src/main/java/dev/agenvas/provider/application/MediaExecutionWorker.java) | 通过注册表分派适配器并归一化结果，复用任务状态转换、调用日志和归档；核对新协议能否表达为现有 `Submission` 结果 |
| [ProviderFailureCodes.java](../../backend/src/main/java/dev/agenvas/shared/error/ProviderFailureCodes.java)、`task/application/TaskService.java`、`usage/application/UsageService.java` | 复用合适的稳定错误码；新增错误时同步公开提示、可恢复性与可能产生外部费用的判断，特别区分提交前失败与受理不确定 |
| [OpenAPI](../../contracts/openapi.yaml) | 独立平台同步连接响应、创建请求的 platform 枚举；新参数、输入语义或响应字段同步 Java 与合约测试 |
| `backend/src/main/resources/db/migration/` | 独立平台需要扩展 `ck_media_connection_platform`；新增 Flyway 迁移并保护现有连接与任务。schema 变化按 [ADR 0012](../adr/0012-jooq-persistence.md)重新生成 jOOQ |

适配器返回执行结果，由应用服务写业务状态。网络请求在数据库事务外执行；远程受理不确定时保留 UNKNOWN。查询、下载和归档恢复只操作原请求，重新生成由用户显式创建新尝试。详细边界见规格 §11–12 及 [ADR 0006](../adr/0006-direct-media-task-boundary.md)。

## 3. 检查前端配置与画布

下列路径相对 `frontend/src/`。

| 入口 | 检查与实施内容 |
| --- | --- |
| [mediaAdapterCatalog.ts](../../frontend/src/features/settings/mediaAdapterCatalog.ts) | 展示名称、模型元数据、平台到适配器的映射；与服务端声明核对 |
| [MediaSettingsPage.tsx](../../frontend/src/features/settings/MediaSettingsPage.tsx)、[MediaConnectionAddressField.tsx](../../frontend/src/features/settings/MediaConnectionAddressField.tsx) | 平台选项、地址输入、凭据表单、创建/更新 payload、模型或工作流配置与保存反馈 |
| [CapabilityConfigurationFields.tsx](../../frontend/src/features/settings/CapabilityConfigurationFields.tsx) | 默认参数、限制与价格；核对透明背景、视频/音频参考等按 adapterId 判断的控件 |
| [MediaDraftEditor.tsx](../../frontend/src/features/canvas/MediaDraftEditor.tsx) | 能力目录、选择入口、参数控件、素材添加、必填条件、运行校验、只读状态、自动保存及失败/冲突反馈 |
| [mediaDraftCapability.ts](../../frontend/src/features/canvas/mediaDraftCapability.ts) | `planMediaCapabilityChange` 计算一次完整切换：参数、输入模式、素材角色、数量与标签共同迁移 |
| [workflowDraft.ts](../../frontend/src/features/canvas/workflowDraft.ts)、[mediaPrompt.ts](../../frontend/src/features/canvas/mediaPrompt.ts) | 工作流字段与具名槽位、默认值和条件、精确素材分配，以及提示词标签的删除和角色重绑 |
| [mediaPricing.ts](../../frontend/src/shared/mediaPricing.ts) | 新能力与有效参数的估算；缺失价格保持未知，核对分档价格与统一价格的优先级 |
| [mediaFunctions.ts](../../frontend/src/shared/mediaFunctions.ts) | 接入图片/视频后处理时同步前后端功能兼容规则，检查功能设置候选与卡片工具可用性 |
| [taskErrorMessages.ts](../../frontend/src/features/canvas/taskErrorMessages.ts)、`shared/i18n/locales/` | 错误提示及四种语言的配置说明；新增服务端公开异常同时补后端 i18n 资源 |
| [schema.ts](../../frontend/src/shared/api/schema.ts) | 从 OpenAPI 重新生成；新增平台或参数后检查类型与调用 payload |

同一种媒体输出沿用已有 CanvasItem、卡片与编辑器，使用能力契约驱动显示。检查现有供应商分支，优先让新规则进入通用能力声明；无法由现有合约表达时，再同步扩展合约、后端校验和前端策略。

完成条件：管理员能配置并发布能力，创作者能选择、编辑和保存对应草稿；刷新后配置与草稿一致，失败保留输入且可恢复。

## 4. 验收双向能力切换

新增适配器需要同时检查“已有能力 → 新能力”和“新能力 → 已有能力”。按能力差异选择场景：普通模型、纯文本/首尾帧/全能参考、RunningHub/ComfyUI 具名槽位、不同容量与必填条件。每种不同迁移规则都要有对应验收。

| 维度 | 需要覆盖的行为 |
| --- | --- |
| 节点与结果 | 切换保留节点身份和当前历史结果，更新本次草稿；下一次任务使用新选择 |
| 参数与默认值 | 兼容参数保留，不兼容字段按现行规则回退或移除；空字段正确使用新能力默认值；新范围不满足时显示具体原因 |
| 时长 | 覆盖合法、越界、空值，以及工作流是否声明时长来源；明确保留、清空或阻断的现行规则 |
| 输入模式与素材角色 | 覆盖 TEXT、START_END、GENERAL_REFERENCE 的兼容选择；首尾帧与普通参考角色转换，尾帧支持及必填帧变化 |
| 素材兼容性 | 覆盖图片/视频/音频类型、上限、最小数量、格式、大小、实际时长，以及音频依赖视觉素材等条件 |
| 工作流槽位 | 保留字段键、类型与映射兼容的活动分配，其余素材按类型和发布顺序填入可用槽位；覆盖条件变化、同版本复用及无匹配槽位 |
| 标签与连线 | 保留素材的精确版本、顺序、颜色和来源；移除素材时同步清理标签与相应媒体输入连线 |
| 保存与并发 | 草稿与连线变更原子提交；失败/冲突保留本地草稿、数据库整体回滚；保存成功刷新连线投影 |
| 价格与执行 | 估算使用目标能力及有效参数；已受理任务保留原能力、输入和价格快照；执行中的编辑锁与取消沿用现有规则 |

显式能力切换沿用规格 §6.13 的直接切换规则；手动输入模式转换和复用历史输入按各自既有确认规则处理。后台更新能力限制时，保留已有草稿及素材，显示不兼容状态并阻止运行，直到用户明确调整。详见 [ADR 0019](../adr/0019-media-capability-defaults-limits-and-pricing.md)、[ADR 0024](../adr/0024-autodl-comfyui-workflows.md)及规格 §6.10、§6.13。

### 示例：MiniMax / AutoDL 与 Seedance

MiniMax 官方 H3 使用 `MINIMAX_H3`，AutoDL 的 H3 工作流使用 `AUTODL_COMFY_VIDEO`，Seedance 使用 `ARK_SEEDANCE_2_I2V`。该示例用于构造验收输入，具体范围从本次能力版本读取；官方 H3 的范围见 [ADR 0042](../adr/0042-minimax-h3-official-api.md)。

| 场景 | 当前行为与检查重点 |
| --- | --- |
| AutoDL / 官方 H3 带分辨率档位 → Seedance | 清除目标不支持的 `videoResolution`，切换后保存和运行均使用整理后的参数 |
| Seedance / AutoDL → 官方 H3 | 保留 H3 支持的 768p/1440p，其余回退到目标默认档位；保留数量与角色兼容的图片/视频/音频及标签、连线 |
| 官方 H3 音频独立参考 → Seedance | 保留可编辑草稿；Seedance 仍要求视觉素材，运行前补充图片或视频 |
| AutoDL 的 1–3 秒草稿 → Seedance | 普通视频能力切换保留原时长；当前 Seedance 编译范围为 4–15 秒，因此草稿可保存，运行需用户调整；不自动改变时长 |
| 图片/音频混合参考 → Seedance | 保留数量与角色兼容的素材，另行校验 Seedance 的格式、时长与视觉参考条件；素材数量满足不能代替协议兼容 |
| Seedance 带视频参考 → MiniMax 无视频输入工作流 | 移除不支持的视频引用，并同步清理相应标签和连线；按目标模式整理保留图片/音频 |
| Seedance → MiniMax 纯文本工作流 | 选择 TEXT，清理媒体引用、标签和连线，保存成功后刷新投影 |
| Seedance → MiniMax 要求图片和音频的工作流 | 检查目标工作流必填数量；缺失时保留可编辑草稿并阻止运行，直到用户补齐 |

## 5. 验证、同步与交付

测试命令从当前 `frontend/package.json`、`backend/pom.xml` 和相关测试文件确定，按实际改动运行对应测试。

| 验证层 | 覆盖内容与参考测试 |
| --- | --- |
| 协议与适配器单元测试 | 新 Client/Adapter 的请求格式、素材顺序、结果解析、预检、错误分类；覆盖确定拒绝、超时、响应丢失、协议异常和受控下载 |
| 配置与任务集成测试 | 使用真实 PostgreSQL 验证平台约束、发布、CAS、固定版本、任务执行与归档；参考 `MediaCloudCapabilityPostgresIT`、`MediaCapabilityConfigurationPostgresIT`、`ConfiguredMediaPostgresIT` |
| 切换与前端交互测试 | 按上一节的差异矩阵覆盖双向迁移；参考 `mediaDraftCapability.test.ts`、`MediaDraftEditor.test.tsx`、`workflowDraft.test.ts`、`MediaSettingsPage.test.tsx`；参数或价格变化覆盖对应测试 |
| 草稿与连线事务测试 | 使用真实 PostgreSQL 验证连线保留/清理、CAS 冲突和整体回滚；参考 `MediaDraftReplacementPostgresIT` |
| 恢复专项 | 异步协议验证按原任务 ID 查询、重启恢复、下载失败/结果过期、UNKNOWN、取消及晚到结果；已有任务固定原版本，等待提交的任务遵守能力变更预检 |
| 类型与合约检查 | 重新生成 TypeScript，运行相关类型、lint/i18n 与契约检查；schema 变化重新生成并核对 jOOQ |
| 真实 Provider 验证 | 与 Mock/假 HTTP 验证分开记录；真实素材、凭据及证据按 [开发资料与公开内容](private-materials.md)处理 |

完成条件：配置、创作、双向切换、执行和恢复都有与本次差异匹配的验证结果；产品语义、接口或验收状态变化同步规格与开发清单，有决策变更时同步 ADR。交付列出实际修改的前后端文件、合约/迁移、检查结果和未验证范围；未完成的验收保留未勾选。
