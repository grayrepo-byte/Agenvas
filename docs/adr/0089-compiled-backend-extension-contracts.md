# ADR 0089：编译型后端扩展契约

状态：接受（2026-11-18）。依据：用户要求连续优化后端的可插拔性与扩展性。

## 问题

媒体执行实现已有统一接口，但声明和发布超时仍由中央静态表控制；存储已有三种传输，却由中央类注入具体实现并分派；原生模型目录反复重建并线性检索，缺少明确的冲突检测；Worker 直接识别供应商异常；提示词解析接口依赖具体 HTTP 客户端。扩展一个既有概念需要同时修改与其协议无关的编排代码。

## 决定

保持单个 Spring Boot/Maven 应用、项目维护的固定 Java 协议和显式内置装配。同协议的新模型、工作流和连接优先走版本化配置，特别是 ADR 0025 的已发布输入契约。不增加用户脚本、ServiceLoader、远端插件市场或第三方代码加载。

### 媒体声明与目录

`MediaAdapter.declaration()` 是新编译实现声明平台、输出种类和输入上限的入口，`publicationHttpTimeouts()` 声明新发布能力的 HTTP 默认值。内置声明移至 `BuiltinMediaAdapters`，默认方法沿用原值；缺失执行实现时仍可读取声明，运行时返回既有 `PROVIDER_UNSUPPORTED_CAPABILITY`，不自动换用其他实现。

注册器将声明、执行实现和发布超时构建为不可变索引。重复/非法 ID、缺失声明、覆盖内置契约或内置超时冲突在构造时拒绝；LOCAL/Mock 不接受 HTTP 超时。声明防御复制集合，校验非负上限、时长顺序和默认模式归属。删除没有调用方的 `supports(PortInput)`，目录、草稿和受理校验是实际权威，不维护第二套未使用规则。

历史版本缺少 HTTP 配置时仍返回 null，由各协议使用原有有界默认，不回填 Task 或配置快照。新增独立平台仍需要 Java/OpenAPI/数据库约束/前端协同修改；注册器不是绕过闭合契约的渠道。特殊协议的配置归一化和字段必须显式实现并验证，注册一个 Bean 不会自动产生编辑器支持。

`NativeMediaCatalog` 显式接收已编译的 `NativeMediaProtocol`，固定 adapter/profile 归属和索引，拒绝重复身份。选择默认模型依赖明确的协议/模型顺序，不能依赖 Map 顺序。`NativeMediaProfiles` 保留一个内置装配点；新增原生协议仍需加入该显式组合，不引入全局可变注册或自动加载。

媒体适配器可返回自身定义的公开预检错误码。任务在一个条件更新中核对活动租约、RUNNING、空远端身份、空结果清单及不存在 ProviderAttempt，再按持久取消标志写入 BLOCKED 或 CANCELED。用量模块在同一事务中消费任务模块签发的结果；旧 BLOCKED 记录仍只认既有错误码。

### 已受理请求的恢复

`MediaAdapter.classifyPollFailure()` 返回受限永久码、既有公开诊断和是否可刷新地址。供应商异常解释放回适配器；Worker 统一编排阻断或技术重试，不依赖具体供应商异常类型。`NativeMediaFailure` 是协议无关的永久失败信号；普通未识别异常继续技术重试。

结果地址每次归档最多刷新一次，只查询已经受理的原请求。Task 应用接口核对绑定存在、manifest schema、序号、主输出、媒体类型、节点、ZIP 哈希、元数据、usage、CAS 和租约 fencing；不再额外限制为 RunningHub。刷新后的再次过期不会循环刷新，也不会调用 submit。现有公开诊断结构及其持久化准入范围不扩大；新协议需要新的诊断形态时另行扩展受限数据契约。

提交检查点、固定批次审批、幂等、取消与 UNKNOWN 保持既有状态机。该接口只处理查询/归档，不授予重新生成许可。Worker 的下一次查询时间使用注入的 Clock。

### 永久归档传输

`ArchiveStorageBackend` 扩展既有 `RemoteStorageClient` 并声明 `providers()`；Object/SMB/WebDAV 各自声明映射。`ArchiveStorageClient` 只接收这些 backend 的集合，不作为 backend 被收集，避免自身依赖。所有持久 Provider 必须被唯一且完整覆盖，缺失或重叠使启动失败，不采用顺序覆盖或 fallback。

历史 profile、固定位置和 receipt 仍由 `ConfiguredAssetStorage` 管理；backend 只负责原有 I/O 契约。归档能力不等于中继签名能力，SMB/WebDAV 仍不能签发对象访问链接。替代某个内置 backend 时应替换其 Bean，单独再加一个 `@Primary` Bean 不能解决集合中的重复归属。

### 提示词格式

解析/分页适配器依赖 `ThirdPartyPromptFeed` 的有界文本下载接口，HTTP 实现拥有 URL 结构、传输上限和超时；多页解析器继续检查累计字节、条数和页数。`ThirdPartyPromptRegistry` 注册已编译格式并拒绝冲突。缺失格式在租约失败记录中明确为 `THIRD_PARTY_SOURCE_UNSUPPORTED_FORMAT`，不访问远端，不把本地安装缺失误报成网络故障。


## 保留的边界与代价

- Agent 工具仍由明确组、冻结白名单和持久请求控制；副作用执行 switch 保留，不把 Bean 自动暴露给模型。
- 公开结果元数据继续受字段投影约束，不开放任意 Artifact JSON 写钩子。
- 特殊协议的规范化、公开诊断和元数据仍有显式分派；部分同模块适配器仍直接读取历史配置 Repository。本轮不宣称这些耦合已经全部消除。
- 注册发生于应用装配，不提供热插拔。新增平台/存储类型仍是跨合约变更；新增同协议目标仍是配置。
- ADR 0079 的地址策略继续适用，移除过时指南与注释，不恢复 HTTP、私网、DNS/IP 或主机白名单限制。

## 验证

验证面包括新合成媒体适配器的发布和执行、声明不可变、默认值兼容、冲突拒绝、原生目录隔离、协议无关结果刷新、存储五种 Provider 的全操作路由、Spring 装配，以及提示词下载接口与缺失格式分类。事务、CAS、恢复、存储和工具权限由定向真实 PostgreSQL 测试验证。

各工作项结论、实际命令结果与未运行范围见[首批改造与工作项记录](../backend-extensibility-iterations.md)。合成协议/HTTP 不能证明真实供应商兼容性；无新增 API/schema、迁移、生成源码或依赖变更，不自动合并、发布或部署。
