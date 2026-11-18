# 后端可插拔性与扩展性：30 轮实施记录

基点：`104483fd`（main）；develop `97f54baf` 与其代码一致。0.0.12 后的内置 Skill 并发注册修复已包含。原工作区无未提交修改；本次使用独立工作区及 `codex/backend-extensibility-30` 分支。

目标：在单个 Spring Boot 应用中降低编译型扩展的接入成本，保持固定协议、版本化配置、持久 Task 和按需工具权限。用户已要求连续执行；不等待逐轮批准，不自动合并或发布。

## 执行计划与验收面

每轮以一个可独立评估的扩展问题为单位。审查后保留现状也是结论，但须指出当前实现与验证证据，不把环境准备或格式化计作架构成果。实现轮次先补契约测试，再改实现；定向测试遵守 AGENTS.md，不运行全量套件。

| 轮次 | 目标与交付 | 验证面 | 状态 |
|---|---|---|---|
| 01 | 核对已有扩展机制与历史优化，避免重复重构 | 分支、近期提交、ADR、开发清单、代码调用关系 | 已审查 |
| 02 | 媒体实现自行声明能力，注册器不再要求修改中央清单 | 新编译适配器、内置值兼容、未安装错误 | 已实现 |
| 03 | 声明保持不可变并拒绝自相矛盾的能力契约 | 集合别名、范围、默认输入模式 | 已实现 |
| 04 | 删除未被调用的 supports/PortInput 扩展负担 | 全仓引用、生产与测试源码编译 | 已实现 |
| 05 | 新媒体实现可声明发布超时默认值 | 旧默认值、新实现、历史缺省不回填 | 已实现 |
| 06 | 原生协议目录支持显式装配及稳定索引 | 三个既有协议、新协议、冲突与隔离 | 已实现 |
| 07 | 失败分类归适配器所有，Worker 不依赖供应商异常类型 | 既有永久/可重试失败、诊断、UNKNOWN | 已实现 |
| 08 | 结果地址刷新使用协议无关信号 | 单次刷新、只查询原请求、不重新提交 | 已实现 |
| 09 | 存储传输按声明发现，移除中央具体实现分派 | 五个 Provider、所有操作、重复映射 | 已实现 |
| 10 | 提示词解析扩展面移除具体 HTTP 客户端依赖 | 默认获取、多页获取、下载上限 | 已实现 |
| 11 | 提示词格式注册显式拒绝冲突与缺失 | 不执行 HTTP、错误分类、现有解析器 | 已实现 |
| 12 | Worker 时间来源符合可注入时钟约定 | 固定时钟下轮询时间 | 已实现 |
| 13 | 复核 Spring 实际装配及扩展 Bean 的启动验证 | 独立上下文，无远端调用 | 已实现 |
| 14 | 审查媒体能力规范化与模型选择的可扩展边界 | 发布、输入校验、固定协议特例 | 已审查 |
| 15 | 审查冻结能力与恢复是否依赖当前扩展状态 | 固定版本、原请求、未安装适配器 | 已审查 |
| 16 | 审查归档元数据扩展是否可安全开放 | 允许公开的字段、凭据与私有推理 | 已审查 |
| 17 | 审查存储中继与永久归档的契约差异 | SMB/WebDAV 不具备对象签名能力 | 已审查 |
| 18 | 审查存储位置与默认切换的兼容性 | 固定 profile、历史资源定位 | 已审查 |
| 19 | 审查 Agent 工具发现、定义与执行权限的一致性 | 按需加载、冻结白名单、禁止自动执行 | 已审查 |
| 20 | 审查工具恢复与副作用 handler 是否适合自动发现 | 持久账本、幂等、同批权限 | 已审查 |
| 21 | 审查 LLM 连接与模型构造扩展面 | Spring AI、命名连接、固定 Run 配置 | 已审查 |
| 22 | 审查调度器、执行器与租约对扩展的约束 | 有界线程、心跳、关闭与 fencing | 已审查 |
| 23 | 审查扩展是否改变事务与跨模块依赖 | 网络在事务外、应用接口协作 | 已审查 |
| 24 | 审查结果数量与多媒体类型的契约兼容 | 有序 manifest、刷新身份、音轨 | 已审查 |
| 25 | 审查配置扩展是否需要 API/schema 迁移 | OpenAPI、闭合平台枚举、Flyway | 已审查 |
| 26 | 审查自托管与 Mock 的独立运行能力 | 无外部账户、无动态脚本依赖 | 已审查 |
| 27 | 审查出站地址策略与接入指南是否保留已撤回规则 | ADR 0079、HTTP/私网不回退 | 已审查 |
| 28 | 建立扩展接入文档与架构决策 | 实际接口、失败语义、兼容性限制 | 已实现 |
| 29 | 审查整个差异并处理发现 | Standards 与用户目标两轴审查 | 已审查 |
| 30 | 执行最终定向回归并据实交付 | 编译、定向单元/集成、格式、差异 | 已验证 |

## 验证记录

执行结果与审查证据在完成对应轮次后追加；不以旧开发清单的历史通过记录代替本次验证。真实 Provider、付费生成、部署及全量套件不自动执行。

### 01–05：媒体扩展契约

- 01：核对近期记录，既有 ComfyUI 固定快照工厂、RunningHub 结果选择和 Task/LLM Worker 维护重构已完成，不重复建设。main 与 develop 的 tree 相同；没有打开的 PR 或本地未完成改动。
- 02：`MediaAdapter.declaration()` 为新编译实现提供声明入口；内置声明独立为 `BuiltinMediaAdapters`，缺失运行实现仍返回原稳定错误。重复 ID、非法 ID、与内置契约冲突在构造时拒绝。
- 03：`Declaration` 防御复制全部集合，并拒绝负范围、倒置时长和不属于支持集合的默认模式。
- 04：全仓引用审查后删除 `supports(PortInput)`、注册器同名方法与 `PortInput`。约 25 个实现不再被迫维护无调用方的第二套能力检查；目录、草稿和受理校验继续执行。
- 05：`publicationHttpTimeouts()` 扩展新发布默认值；目录服务通过注册器返回合并默认值。内置值不变；LOCAL/Mock 不能声明 HTTP 超时，历史缺省 `configured({})` 仍为 null，不回填冻结版本。
- 红绿证据：新适配器最初被旧中央表拒绝；声明集合可被外部修改及无效范围被接受的测试先失败；超时目录扩展先因缺少接口编译失败。实现后定向 `MediaAdapterRegistrationTest,MediaDeclarationContractTest,MediaAdapterRegistryVideoModesTest,MediaCapabilityConfigurationTest,MediaExecutionWorkerTest,MediaHttpTimeoutsTest,MediaCapabilityHttpTimeoutsTest` 共 **110 项通过，0 失败/错误/跳过**。另初始定向基线 41 项通过。编译包含全部生产与测试源码，未执行全量测试。

### 06：显式装配的原生协议目录

`NativeMediaCatalog` 接受编译型协议列表，固定 profile 顺序并建立 adapter 索引；拒绝重复适配器、重复模型/操作身份、无 profile 和错误归属。`NativeMediaProfiles` 保留静态调用兼容性，生产仅有一个明确的内置装配点，不扫描 classpath、不加载第三方代码；新增内置协议仍须显式加入装配点。每次 resolve 不再重建全协议列表，也不依赖 Map 迭代顺序选默认模型。新增目录可独立装配用于验证，不改变全局状态。

新接口测试先因目录不存在失败。`NativeMediaCatalogTest,NativeMediaProfilesTest,WanModelsTest,SeedreamModelsTest,GoogleVideoModelsTest` **30 项通过**。同协议工作流仍由发布配置扩展，未引入新协议加载机制。

### 06–09、12：目录、恢复与归档传输

- 06：`NativeMediaCatalog` 显式接收已编译协议，构造不可变 adapter/profile 索引，校验重复及归属，保留协议和默认模型顺序。`NativeMediaProfiles` 保留一个明确内置装配入口，不增加 ServiceLoader 或第三方代码加载。新增目录及既有 Native/WAN/Seedream/Google 模型测试 **30 项通过**。
- 07：`MediaAdapter.classifyPollFailure()` 返回受限的永久码、既有公开诊断和刷新许可；Kling/RunningHub 各自解释基础设施异常。Worker 不再导入供应商异常，不改变提交检查点和 UNKNOWN。查询、payload 与 manifest 归档共用同一失败处理。
- 08：归档最多刷新一次结果地址，第二次过期直接按适配器分类处理；保持原请求 ID，不调用 submit。审查还发现 Task 层的 RunningHub 专属限制，补非 RunningHub 合成绑定测试先复现拒绝后移除该特例；保留绑定存在、完整 manifest 身份、CAS 和 fencing。Worker/Task 恢复 **40 项通过**，供应商与 Worker 专项此前 **58 项通过**。
- 09：`ArchiveStorageBackend.providers()` 是永久传输发现入口；注册器从集合构建不可变映射，五个持久 Provider 必须唯一且完整覆盖，避免歧义回退。Object/SMB/WebDAV 实现自行声明，路由器不被自身发现。新增五种 Provider 的全操作往返和 Spring 集合装配测试；存储相关 **40 项通过**。既有集成测试的替代传输改为明确同名 Bean 替换，避免 @Primary 产生重复注册。
- 12：媒体 Worker 注入 `Clock`，提交及兼容轮询的下一查询时间均由该时钟确定。固定时间测试通过，所有直接构造处同步更新。

## 14–27：扩展边界复核结论（验证结果另列）

| 轮次 | 实际审查及决定 | 代码与后续验证依据 |
|---|---|---|
| 14 | 保留协议专用的发布/输入规范化。普通新适配器可使用共同契约；新原生字段仍须明确扩展规范化，不能宣称注册一个 Bean 就自动支持任意协议。 | `MediaCapabilityService.spec/declaration/normalizeAdapterSettings`、`NativeMediaProfiles.normalizeSettings`；能力规范化及模型测试 |
| 15 | 恢复必须使用原适配器与版本，卸载实现时明确拒绝；不回退到另一个模型。 | `pinnedSnapshot/settings/httpTimeouts`、ComfyUI/WAN/RunningHub `reconcile/snapshot`；`ComfyUiPinnedConfigurationTest` 与版本/恢复 PostgreSQL 专项 |
| 16 | 保留受限公开元数据投影。暂不增加允许扩展任意写 Artifact JSON 的钩子，避免凭据、私有推理和不受限正文进入公开内容。 | Worker `addNativeMetadata/safeLayerBox/archive`；`MediaResultTest`、`OpenAiImage2AdapterTest`、Native 多结果集成测试 |
| 17 | 归档传输与可签名中继继续使用不同契约。SMB/WebDAV 不因实现归档接口获得对象签名能力。 | `Provider.supportsRelay`、`ConfiguredAssetStorage.cloudObject`、`MediaRelayService.pinProfile/signedMedia`；存储/中继测试 |
| 18 | 新传输仅负责 I/O，不决定历史资源位置。默认切换不改已有 profile/receipt；已引用位置禁止变更。 | `ConfiguredAssetStorage.store/finish/recover/remote`、`StorageSettingsService.update`；存储位置 PostgreSQL 专项 |
| 19 | 保留显式按需工具组与冻结上限，禁止把所有 Bean 自动暴露给模型。 | `ToolRegistry.initialDefinitions/continuationDefinitions`、`AgentToolset.load`、`ToolExecutionService.executeLocked`；`OnDemandToolsetsTest`、`RunToolPolicyTest` |
| 20 | 保留持久副作用执行的显式 switch。拆 handler 必须整体保留参数 hash、账本重放、同批事务和持久请求权限；本轮不增加另一套自动执行机制。 | `ToolExecutionService.executeLocked`、`ToolBatchExecutionService.executeLeased`；按需工具 PostgreSQL 专项 |
| 21 | 保留集中 Spring AI 工厂。当前仅一个 OpenAI-compatible 构造族，无第二个原生协议作为新增泛化工厂的依据；命名连接继续从 Run 固定版本读取。 | `StoredChatModelFactory.create`、`ConfiguredChatGateway.pinnedConfig`；Gateway 定向测试 |
| 22 | 保留有界执行器与独立媒体心跳，不把进程资源槽变成产品配额。记录 3 submit + 1 poll 与 4 heartbeat scopes 跨文件手工同步的限制；未来调整容量需统一来源。 | `MediaExecutionScheduler`、`MediaLeaseHeartbeat`；Worker/heartbeat 定向测试 |
| 23 | 本轮不改变事务归属；网络仍在事务外，适配器不写业务表。记录既有 Wan/RunningHub 对本模块 jOOQ 快照查询的耦合，未宣称全面清除跨层依赖。 | Worker `submitOnce/pollOnce`、`TaskService` 短事务、`MediaRelayService` 活动事务拒绝；租约/超时 PostgreSQL 专项 |
| 24 | 保留固定 manifest 的顺序、主输出、媒体种类、usage、ZIP hash 和 metadata。刷新只改 URL。视频音轨照常保留；仅用户明确音频分离任务创建静音副本。 | `checkpointProviderResults/refreshProviderResultUrls`、`archiveResults/withSilentVideo`；RunningHub 多结果/刷新与真实媒体专项 |
| 25 | 本轮无 API 字段、平台或 schema 变更。新独立平台仍需 Java/OpenAPI/Flyway/前端协调；编译型注册不绕开闭合平台契约。 | `MediaPlatform`、OpenAPI 平台枚举、数据库 CHECK；最终核对合约/迁移/生成代码差异为空 |
| 26 | 保留单 Maven/Spring Boot 应用与显式内置装配。无微服务、插件市场、远端许可证或用户代码加载。Mock 不要求外部模型账户；未把 Mock 测试当真实 Provider 证据。 | `AgenvasApplication`、`NativeMediaProfiles.CATALOG`、Mock gateway/adapters；Mock 定向测试 |
| 27 | 修复指南中“公网 DNS/拒绝 fake-IP”的过时要求，更新 HTTP 超时与声明入口；旧存储 ADR 标明地址部分已被 0079 替代。运行行为保持 URL/凭据作用域与有界传输，不恢复地址白名单。 | `ServiceEndpointPolicy`、`LlmEndpointPolicy`、Provider 接入指南、ADR 0079；地址兼容性定向测试 |

上述保留结论是有边界的架构审查，不表示未来无需演进，也不声称所有潜在耦合已经清除。固定协议的新增目标仍优先采用版本化配置；固定媒体批次审批、幂等和 UNKNOWN 不重投遵守 ADR 0025 与媒体审批设计。

### 10–11、13：解析器与完整装配

- 10：`ThirdPartyPromptFeed` 承载有界文字下载；默认格式、Markdown 附加清单和 Gallery 多页均改为此接口，保持累计字节、条数及页数限制，生产下载仍由既有 HTTP 客户端实现。初次编译发现 Markdown 的专用 fetch 覆盖也需要同步，已修正。
- 11：提示词格式从应用编排中独立为 `ThirdPartyPromptRegistry`，明确拒绝重复与缺失。缺失格式测试先复现 NullPointerException 被误记为 `THIRD_PARTY_SYNC_FAILED`，修复后记录 `THIRD_PARTY_SOURCE_UNSUPPORTED_FORMAT`，并确认没有 HTTP 调用。提示词相关 **36 项通过**。
- 13：`MediaAdapterExtensionPostgresIT` 在实际 Spring 应用和 PostgreSQL 17 中安装一个合成 Java 适配器。无需修改生产中央表即可发布能力、创建草稿与 Task、提交一次、查询一次、刷新一次并归档成功；实际下载两次，没有第二次提交。**1 项通过，0 失败/错误/跳过**。此为合成协议和真实数据库，不代表真实 Provider 或进程重启验收。

### 10–11、13、28：格式扩展与明确的接入边界
+
+- 10：默认、Markdown 附加 manifest 和 Gallery 分页都依赖 `ThirdPartyPromptFeed`。真实 HTTP 实现保留每次有界下载，分页仍累计检查 32 MiB、条数及页数，不增加 HTTP 客户端继承要求。
+- 11：格式注册拒绝重复；缺失格式的同步先复现旧 NPE/通用失败分类，再以明确错误结束租约且不调用远端。解析、分页、HTTP、调度及同步故障七个测试类 **36 项通过**。
+- 13：合成新适配器通过 Spring 全应用装配与真实 PostgreSQL 完整链路，不仅测试手动构造的注册表；内置 Bean 同样完成启动验证。
+- 28：ADR 0089、规格 §20.1 与 Provider 接入指南同步实际契约和限制；旧 ADR 0046 的地址部分显式标为被替代。没有新增运行依赖、API/schema、Flyway 或生成文件变更。
+- 复核追加：声明不能把 AGENT_TURN/TEXT_GENERATION 注册成媒体；profile 输出种类必须与声明相同且同 adapter 一致。两项测试先失败后修正，相关 7 类 **38 项通过**。28 项内置声明与基点忽略空白逐项相同。
+
+### 最终定向单元回归（第 30 轮证据）
+
+使用 Java 21 和仓库 Maven 3.9.12，通过环境现有 Maven settings/cache 执行 `./mvnw -Dtest=<下列 48 类> test`；**419 项通过，0 失败/错误/跳过**。该命令编译全部手写与生成生产源码、全部测试源码，但只运行选定测试，没有执行全量套件。
+
+```text
+MediaAdapterRegistrationTest, MediaDeclarationContractTest, NativeMediaCatalogTest,
+NativeMediaProfilesTest, MediaAdapterRegistryVideoModesTest, MediaCapabilityConfigurationTest,
+MediaCapabilityHttpTimeoutsTest, MediaHttpTimeoutsTest, MediaCapabilityNormalizationTest,
+MediaCapabilityModeTest, OpenAiImageOptionsTest, ComfyUiPinnedConfigurationTest,
+MediaExecutionWorkerTest, RunningHubAdapterTest, KlingVideo3AdapterTest, WanProtocolAdapterTest,
+GoogleVideoAdapterTest, OpenAiImage2AdapterTest, ArchiveStorageClientTest, ObjectStorageClientTest,
+SmbStorageClientTest, WebDavStorageClientTest, ConfiguredAssetStorageTest, StorageSettingsServiceTest,
+MediaRelayServiceTest, HttpStoragePolicyTest, ThirdPartyPromptExtensionTest, ThirdPartyPromptAdapterTest,
+VideoPromptAdapterTest, ImagePromptGalleryAdapterTest, ThirdPartyPromptHttpClientTest,
+ThirdPartyPromptSyncFailureTest, ThirdPartyPromptSchedulingTest, TaskProviderRecoveryTest,
+OnDemandToolsetsTest, RunToolPolicyTest, SpringAiChatGatewayTest, TaskClaimTest,
+ServiceEndpointPolicyTest, LlmEndpointPolicyTest, DownloadAddressCompatibilityTest, MediaResultTest,
+MockChatGatewayTest, MockGenerationGatewayTest, WanModelsTest, SeedreamModelsTest,
+GoogleVideoModelsTest, MediaLeaseHeartbeatTest
+```
+
+Spotless `apply` 后再次 `apply + check` 通过，全部 Java 文件内容哈希不变，证实格式化幂等。保留既有 Spring AI varargs、测试 deprecated/unchecked 和 Mockito 动态 agent 警告；没有以关闭检查消除它们。

### 29：双轴独立审查
+
+审查基点 `104483fd`，实施提交截至 `bd92233e`。Standards 轴读取 AGENTS、CONTRIBUTING、规格 §20 和相关 ADR，检查装配、职责、状态、线程/事务及常见代码问题：**0 项硬违反、0 项须修复的 smell**。Spec 轴对照本次目标、ADR 0025、媒体审批设计和 ADR 0089：**0 项须修复的缺失、越界或实现错误**。两位审查者均只读，不把其审查当作测试运行；最终测试由主执行过程单独完成。
+
+### 30：真实 PostgreSQL、最终检查与交付范围
+
+同一工具链显式选定 `./mvnw -Dtest=<以下选择器> test`，12 个测试类共 **59 项通过，0 失败/错误/跳过**。使用真实 PostgreSQL 17.11、实际 Spring 装配及合成 HTTP/媒体；覆盖按需权限和同批回滚、提示词租约/同步开关、能力版本、原 ID 恢复与 CAS/fencing、混合结果、晚到结果、音轨、独立中继和历史存储位置。不是 H2、真实付费 Provider 或部署验收。
+
+```text
+MediaAdapterExtensionPostgresIT, RunningHubPostgresIT, RemoteFileStoragePostgresIT,
+ObjectStoragePostgresIT, SeedanceVideoReferencePostgresIT, ThirdPartyPromptPostgresIT,
+ThirdPartyPromptSyncSettingsPostgresIT, MediaHttpTimeoutsPostgresIT, OnDemandAgentPostgresIT,
+ArkSeedanceArchivePostgresIT, MiniMaxH3ArchivePostgresIT,
+NativeMediaPostgresIT#seventeenLayerResultsAndMetadataCommitTogetherAndCancellationKeepsHistoryUnselected
+  +copiedGoogleFilesMetadataCannotTurnAUserUploadIntoAnExtendableVeoResult
+```
+
+- 最终定向验证合计 **478 项**（419 单元 + 59 PostgreSQL），不叠加早期重复执行的计数。未运行的其他单元/集成测试不能据此视为通过。
+- 编译全部生产/测试源码；Spotless 幂等及 check、`git diff --check` 通过。差异中 OpenAPI、Flyway、jOOQ、生成 TypeScript、POM 和前端均为空；28 个内置声明与基点一致。
+- 已复核新增文本与差异中的凭据模式，未发现真实凭据；新增输入全部为明确合成值。原始运行日志留在仓库外，不提交。
+- 仍保留的演进边界：原生协议显式装配、特殊参数/公开元数据/诊断仍需受控扩展；部分同模块历史配置查询继续使用现有 Repository；调度的 3+1 slots 与四个 heartbeat scope 仍须同步维护。没有为本轮再加入容量配置或动态加载。
+- **未运行**：全量测试、真实供应商/真实云/NAS 兼容性或付费生成、浏览器端到端、部署与真实进程重启。保留现有编译/Mockito 警告，未关闭检查。
+- 当前无阻塞。工作在隔离分支完成并提交；没有推送远端、合并、打标签、发布或部署。原工作区改动未被覆盖。
