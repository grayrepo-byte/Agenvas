状态：本计划的25项后续问题已逐项实施、定向验证并独立审查；结合首批5项，实际完成30项。结果和剩余风险见[独立迭代记录](backend-extension-iterations.md)，整体检查另行记录。以下保留原计划，不以候选或测试数量代替实施计数。

# 后端扩展性后续独立迭代计划

首批可计5类实质架构改造；原30项表不作30轮统计。以下是已确认问题的候选实施任务，每项完成且独立审查通过才增加实际迭代数。目标30并非抽象数量配额，剩余问题需继续从代码验证。

## Global Constraints

- 保持公开 API 和数据库兼容，不改历史发布或固定输入，数据库仍为真相。
- 保留固定批次审批、幂等、CAS/租约 fencing；UNKNOWN 不自动重投，恢复仅查询原请求。
- 网络调用不持有业务事务，凭据/私有正文不进入公开结果/事件/导出/日志。
- 保留自托管与Mock、工具按需发现；不加载第三方代码，不恢复ADR0079已移除的HTTP/私网地址限制。
- 仅编译型真实扩展点；至少有真实多实现/调用方/重复逻辑，不新增通用插件框架或无用兼容层。
- 不合并、不发布、不部署；仅隔离分支和草稿PR。
- 每次迭代记录问题、方案、变更、定向测试和剩余风险；验证/审查/文档/环境修复不另计架构轮次。

## Task 6: 提交前失败契约

问题：TaskService PRE_SUBMISSION_BLOCKING_CODES 和 UsageService UNSUBMITTED_MEDIA_BLOCKS重复，MediaAdapter.preflightFailure新码被中央表拒绝。
目标：建立受限的确认未提交失败契约，Task与Usage使用同一结论；支持已编译adapter的新稳定码。只有数据库证明尚未提交、正确租约epoch/worker且无远端身份/提交检查点才能阻断并释放；UNKNOWN/已受理不能伪装未提交。不仅追加白名单。既有历史错误码保持兼容。
主要文件：provider/domain/MediaAdapter.java、task/application/TaskService.java、task/infrastructure/JooqTaskRepository.java、usage/application/UsageService.java及相应领域契约。
验证：新增自定义preflight码、无效码、提交检查点后拒绝、旧epoch/worker拒写与不释放；TaskProviderRecoveryTest、UsageMediaPricingTest、ComfyUiOriginPreflightPostgresIT，必要真实PG专项。

## Task 7: LOCAL执行语义声明

问题：MediaAdapterRegistry.localProcessor静态硬编码五个id，新增platform=LOCAL adapter未获得统一目录/价格语义。
目标：从已注册声明平台派生受控本地处理语义，迁移MediaCapabilityService、ManualUnknownRetryService、DirectMediaTaskService、VideoOperationService和TaskService中的实际调用方。保留既有本地操作权限和任务快照语义，不能让任意输入字符串伪造本地零费用；未知id仍明确拒绝。
验证：新非内置LOCAL adapter注册后目录/价格/默认边界；MediaCapabilityModeTest、MediaFunctionServiceTest、UsageMediaPricingTest、ImageFunctionPostgresIT、VideoOperationPostgresIT。不要只追加id。

## Task 8: 已注册适配器公开诊断策略

问题：PollFailure支持有界ProviderFailure，TaskService.recordProviderFailure仍只有RH和OpenAI moderation白名单。
目标：由已注册adapter受限策略决定诊断准入，Task保留有界领域类型、作用域、fencing。避免ProviderFailure.category通用化时错误映射为RunningHub；保持既有RH/OpenAI JSON及稳定码兼容，未知id拒绝。实现选最小满足真实扩展的契约，不公开原始body。
验证：新已注册adapter可持久化受限诊断、未知不行、旧epoch不行；TaskProviderRecoveryTest、ProviderFailureTest、AgentMediaResultProjectionTest、RunningHubPostgresIT。

## Task 9: 独立深度推理模块

问题：LocalVideoProcessor为了depth与depthModelAvailable注入整个LocalImageProcessorAdapter；后者同时拥有ONNX session与Task协议。
目标：提取真实共享Depth推理模块，统一模型加载/并发/session关闭，图像adapter和视频processor直接注入。保留模型配置、缺模型错误、像素/内存预算和处理结果，不引入模型插件框架。
验证：LocalImageProcessorAdapterTest、LocalVideoProcessorTest及缺模型/资源关闭覆盖；真实模型可选smoke单列，未配置不能宣称通过。

## Task 10: 视频探测测量解析

问题：SeedanceVideoReferenceLoader、MiniMaxH3ReferenceLoader、KlingPreparedMedia重复ffprobe流与fraction FPS解析。
目标：共享严格测量解析类型，至少迁移这3个真实调用方；供应商阈值/错误码/音轨规则留原处，不混成一套规则。多流的选择必须保持既有约定，拒绝非有限数、0/0等畸形值。
验证：新parser缺字段/0除/NaN/23.976/多流测试及MiniMaxH3ReferenceLoaderTest、KlingPreparedMediaTest、SeedanceVideoReferencePostgresIT。

## Task 11: 固定配置读取seam

问题：多个adapter直接依赖JooqMediaCapabilityRepository，重复snapshotAt/hash/adapter身份/历史连接凭据核验。
目标：provider应用内固定配置读取接口集中历史绑定验证，先迁移至少2个实际adapter家族。只取绑定版本，不读当前版本，不跨任务缓存私密配置；错误映射由各adapter保留。遵守已有ComfyUI封装和ADR0025。
验证：NativeMediaAdapterHttpTimeoutsTest、OpenAiImage2AdapterTest、ArkSeedance2AdapterTest、NativeMediaPostgresIT中连接更新原ID恢复；新增错误adapter/hash/版本测试。

## Task 12: 本地图像操作验证执行分派

问题：LocalImageProcessorAdapter的validateParameters和process重复switch，新增操作需同步2处。
目标：局部编译型操作定义统一参数解码校验与执行，adapter保留授权和封装。使用现有多个操作，不为每个enum生成Spring Bean；和Task9深度共享模块协作。保留alpha、尺寸、像素限制与旧错误码。
验证：LocalImageProcessorAdapterTest、ImageOperationValidationTest、ImageOperationSpecTest、ImageOperationDerivationPostgresIT；同参数预检与执行对应。

## Task 13: 对象存储签名协议

问题：ObjectStorageSigner.sign/presignGet和ObjectStorageClient多处按OSS分派，真实有S3/COS SigV4与OSS V4两协议。
目标：两个明确编译型协议对象集中签名/header/metadata/不覆盖差异；调用方选协议后不复制oss条件。保留canonical、HMAC、query/header、虚拟主机和不可覆盖行为。不新增公共插件框架。
验证：ObjectStorageClientTest官方AWS/独立OSS签名向量及presign；ObjectStoragePostgresIT归档恢复。

## Task 14: Task多结果只读身份契约

问题：McpTaskResultListener、McpToolService、TaskOutputReadAccessService、AgentLocalTaskOutcome、AgentMediaResultProjection各自遍历主结果/additionalResults。
目标：task模块只读归档结果identity类型，集中兼容旧JSON/UUID/ordinal/selected遍历；至少MCP与Agent真实调用迁移。授权与各公开投影留原模块，未知字段绝不自动复制公开，内部receipt不公开。
验证：TaskOutputReadAccessServiceTest、AgentMediaResultProjectionTest、AgentLocalTaskOutcomeServiceTest、McpToolsPostgresIT；主结果ordinal非0、晚到、无结果、旧结构、未知字段不泄露。

## Task 15: 画布产物批量投影

问题：CanvasService.list对每个节点经ArtifactService.get/requireVersion查询；ArtifactService.listProject也逐产物get，查询随画布线性增长。
目标：Artifact应用接口提供批量identity/default/selected读取，Canvas批量装配；保序、共享Artifact不同选择、空媒体/CHARACTER路径一致。所有批量SQL保留owner/project条件；不引入跨请求缓存。
验证：CanvasPostgresIT、CanvasMediaContextPostgresIT、CanvasCharacterNodePostgresIT、ProjectEventPostgresIT；新增真实PG查询计数非线性增长与RR一致性。

## Task 16: 导出批量读取

问题：ProjectExportManifestService对每卡片get草稿/listMediaVersions/snapshotForExport，复用style/Artifact重复读取。
目标：所有者模块批量导出读取，保留既有REPEATABLE_READ；仅各节点自己的结果id，风格去重，不扩大成Artifact全历史；沿用Task15的批量投影。
验证：ProjectExportManifestPostgresIT、CanvasMediaVersionsPostgresIT；共享Artifact多分支/同异风格/空媒体/允许字段与查询数。

## Task 17: 发布能力目录批量构建

问题：publishedSnapshots逐connection/capability读取；planningCatalog复读已知snapshot/settings，工具分页40无法消除后端N+1。
目标：受过滤当前发布snapshot集合读取，单次目录装配内复用settings/declaration；不跨请求缓存，不改顺序/默认优先/目录分页或配置变更重新遍历语义。
验证：MediaCapabilityModeTest、MediaCapabilityNormalizationTest、NativeMediaPostgresIT、OnDemandAgentPostgresIT#mediaDiscoveryPagesThroughEveryPublishedCapabilityBeyondForty；新增多连接>40能力和SQL数。


## Task 18: 模型元数据协议

问题：ModelMetadataClient.retrieve/request/cursor和ModelCatalog.parse重复按OLLAMA/GEMINI/ANTHROPIC等6种Provider分派。
目标：将路径/分页游标/认证/元数据测量解析集中到明确编译型协议对象，至少真实Gemini/Anthropic/Ollama三种；有界传输仍只一个，保留2k模型/4页/大小/deadline/取消/凭据回显过滤，未知信息不从名称猜测。保持COMPATIBLE/OPENAI/OPENROUTER全部行为，无第三方加载，不恢复ADR0079地址限制。
验证：ModelMetadataClientTest和ModelCatalogTest，分页面/重复游标/取消、原生path、limit解析、key不进入URL/回显不公开，工具链已有HTTP合成夹具。

## Task 19: 模型诊断执行流程

问题：LlmDiagnosticService.diagnose/diagnoseConnection复制cost确认、全局inFlight、两轮probe和版本标记流程，只有目标查找/CAS/返回投影不同。
目标：同一有界执行流程封装诊断生命周期，两个真实调用方提供受控目标读取/版本提交。全局单flight、最多两次可能计费请求、调用不持有事务、异常后释放gate、已验证短路、连接不切换活动配置及版本变化拒绝保持。不增加公共插件接口。
验证：LlmDiagnosticServiceTest、LlmDiagnosticPostgresIT，两个入口并发互斥、失败后释放、已验证不call、版本更新不误标及日志。

## Task 20: Agent显式工具目录

问题：ToolRegistry schema、AgentToolset组、RunToolPolicy工具集合和InitialModelContextService专业规则需同步维护，新工具存在多处注册漂移风险。
目标：编译型显式工具目录归并definition/组归属及专业规则的关联，真实各消费者复用唯一描述。schema可保留原定义位置但不能复制工具ID集合；固定Run ceiling、legacy完整allowlist、新Run仅loader、NEXT_MODEL_TURN和成功提交后解锁保持。不自动暴露SpringBean，不改变工具schema或增加权限，不增加第三方代码加载。
验证：OnDemandToolsetsTest、RunToolPolicyTest、InitialModelContext相关测试、OnDemandAgentPostgresIT；目录重复/缺项/组成员与schema一致，冻结schema不被当前目录替换。

## Task 21: Agent工具参数读取

问题：Canvas/Storyboard/LocalMedia/Read工具服务复制strict JSON对象字段/UUID/整数读取，扩展字段校验要多处同步。
目标：llm模块受控参数读取原语供至少3个真实工具服务使用，保留每个工具的允许字段、范围、空值、稳定错误码与现有合法输入语义。业务授权留原应用服务，不把MCP独立协议合并，不做全JSONSchema框架。
验证：现有对应工具参数测试及OnDemandAgentPostgresIT、PromptInjectionPostgresIT；未知字段、null、字符串数值、UUID畸形、精确整数边界、无权限输入仍拒绝。

## Task 22: MCP编译handler目录

问题：Mcp contract已是目录，但McpToolPolicy硬编码14项且McpToolService读/写switch分散dispatch。
目标：显式已编译handler集合，启动/构造校验名字与contract恰好匹配，无重复/缺失，集中read/write dispatch元数据。保留MCP独立权限、持久账本、幂等、任务批准和输出投影，不借Agent工具实现或自动扫描Bean获得权限。现有handler可局部方法引用，不生成14个空类。
验证：McpToolPolicyTest、McpToolsPostgresIT、McpHttpPostgresIT；missing/duplicate/extra handler失败、全部contract routes、未授权/重放仍一致。

## Task 23: MCP闭合schema校验器

问题：McpToolPolicy将通用schema子集递归校验和工具业务特例揉在一起，未来未知keyword可能被静默忽略。
目标：提取MCP现有闭合schema子集校验器，启动检查contract所用约束全部受支持；当前注释性关键字支持列表明确，未实现约束拒绝注册。通用结构校验与6类真实业务规则分离，保留目前全部schema、合法参数、错误码、顺序和预算，不依赖外部schema库。
验证：McpToolPolicyTest、真实contract支持/未知约束失败、nested/object/array/uuid/边界/oneOf等实际用到关键字，McpToolsPostgresIT。Task22目录调用新validator。

## Task 24: Asset拥有共享文件与固定pin类型

问题：asset.SkillAssetArchiveService导入LibraryService.PinnedSkillAsset/MediaFile，而library又依赖asset；Skill/CharacterLibrary/LLM等实际共享该文件类型，形成类型方向环。
目标：将asset自身需要的受限文件/固定pin值类型移到asset应用接口并迁移真实调用方，LibraryService只负责库用例。不扩大文件访问，不改变固定JSON字段/历史pin/路径安全及私有素材规则，不增无需求兼容别名。
验证：SkillAssetArchivePostgresIT、LibraryPostgresIT、SkillPostgresIT、CharacterLibraryPostgresIT及相关单元，旧固定pin与新类型可读、路径权限不变；编译跨模块全部源码。

## Task 25: 有界后台Worker执行原语

问题：LibraryWorker、SkillWorker、CharacterLibraryWorker重复singleflight/shutdown gate/execute/catch/finally/关闭等待生命周期。
目标：共享真实横切有界执行原语，三个worker保留各自业务tick/清理/重试顺序；原语负责一次一个工作、拒绝接收、异常后释放及有界关闭。保留ShutdownGate、线程名称、时限、错误日志语义；不合并字幕不同关闭语义，不构建调度框架。
验证：新增原语通过公开执行/close观察，三worker相关LibraryCleanupPostgresIT、LibraryRecoveryPostgresIT、SkillPostgresIT、CharacterLibraryPostgresIT；失败释放、并发拒绝、关闭不新接收及原恢复流程。

## Task 26: Task拥有统一事件投影

问题：TaskService.taskEvent与TaskHistoryCleanupService.stopFor分别构造Task状态事件，已漂移：字幕Task清理取消缺subtitleId，前端无法刷新对应草稿。
目标：task模块统一受限状态事件投影供两真实写入口使用，保留普通路径既有字段与不可公开字段白名单；清理路径补字幕关联但不改变费用不确定/UNKNOWN处理。权限/事务/事件序号留应用服务，不引入通用eventcontributor。
验证：TaskHistoryCleanupServiceTest、Task相关事件测试和CallLogPostgresIT；字幕清理有subtitleId、私密input/provider诊断body不泄露、取消/UNKNOWN/费用语义不变。

## Task 27: 本地媒体私有scratch生命周期

问题：音频trim、视频depth/silent、composition/preview重复0700目录创建和逆序删除finally，新增本地处理器容易泄漏临时文件。
目标：AutoCloseable私有workspace集中目录权限和失败清理，至少3个真实媒体调用方复用；协议错误/预算/进程控制仍留调用方。保留0700、唯一目录、结果在close前完成读取、符号链接不逃逸删除、异常时清理。不得清理用户素材目录。
验证：新workspace成功/异常/权限/嵌套资源清理，以及LocalAudioTrimAdapterTest、LocalVideoProcessorTest、LocalVideoCompositionRendererTest/preview现有测试。仅合成素材。

## Task 28: 提示词mention扫描

问题：DirectMediaTaskService.renderNativePrompt/renderKlingPrompt/renderPrompt复制U+FFFC与mention数量严格匹配的遍历，只有alias解析不同。
目标：一个严格有序扫描器，原协议提供alias resolver；保留错位/缺失/多余mention错误码、原序、协议专用音视频别名及用户字面内容，不改变冻结prompt配置。
验证：现有DirectMediaPrompt相关测试及CanvasPromptInputPostgresIT、RunningHubPostgresIT、KlingPostgresIT；缺失/多余/空alias/多码点/原内容无占位符和混合mentions。

## Task 29: 直接文字任务purpose策略

问题：DirectTextGenerationWorker对普通文字、图像反推、视频优化的消息/hydrate/输出验证/发布多点if分派，新purpose要同步多个分支。
目标：三个真实purpose的编译策略归并相关流程，Worker保留唯一模型检查点、持久状态与fencing。原purpose选择来自固定Task，不被模型覆盖；同一模型调用次数、恢复、图片授权和输出格式保持。无框架/新运行依赖。
验证：DirectTextGenerationWorkerTest、DirectTextGenerationPostgresIT、ImagePromptPostgresIT、VideoPromptPostgresIT；各purpose选择/输出、失败/UNKNOWN/原检查点恢复/取消语义。

## Task 30: 过期执行清理应用协调

问题：audit.infrastructure.JooqCallLogRepository.purgeExpired编排run.stopFor/task.stopFor/LLM ledger/provider attempt清理并持多表锁，基础设施依赖业务应用且新增账本要改Repository。
目标：锁定完整过期execution batch→停止业务状态→各账本清理→删除日志的事务协调迁到audit应用服务，Repository只做候选/锁定/删除持久化。完整组、项目→run→task锁顺序、SKIP LOCKED、更新水位、1000×10批、失败全回滚及费用语义保持，网络不入事务。不做通用插件加载，不增加表。
验证：CallLogPostgresIT全部40项/现有retention测试，TaskHistoryCleanupServiceTest/RunHistoryCleanupServiceTest；真实PG故障注入回滚/锁定/完整组/边界/并发/更新水位保持。
