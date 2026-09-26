# AGENTS.md — Agent Canvas 开发约束

> 供 AI 编码助手及贡献者读取；本文件定义开发方式，不代表项目已经实现。
> 主规格见 `docs/MVP-SPEC.md`，任务清单见 `docs/DEVELOPMENT-CHECKLIST.md`。
> 不得覆盖用户明确确认的产品决策；产品决策变更时同步更新规格与 ADR。

## 1. 项目目标

从零构建可自托管的 AI 创作画布。Agent 在画布上作为可操作卡片存在，能够通过受控工具生成和修改创作产物。

首版只完成：用户指令 → 三镜头规划 → 图片审批/生成 → 视频审批/生成 → 局部重做 → 无声顺序导出。

核心功能必须可开源独立运行；不能要求作者的远程许可证、专属账户或闭源 Agent 服务。

## 2. 按任务读取与实施

实现行为变更前，读取涉及的代码及必要的规格章节、任务清单条目、已有 ADR 和 API 合约，确认输入、输出、依赖与验收方式。纯文案、注释或局部规则修改只需读取相关文件。

新增功能优先完成一个可验收的纵向切片，不批量生成没有行为的 Controller/Service/Repository 骨架。

明确区分现有实现与规格计划。没有运行的测试写“未运行”，不得写“通过”。没有真实 Provider 测试的功能不得标注“真实生成已完成”。

## 3. 固定技术决策

前端：Vite 8 + React + TypeScript，React Flow，TanStack Query，Zustand，React Router，Tailwind/shadcn。前端只承担页面构建与客户端渲染；禁止 BFF、Server Action、SSR 数据访问或 Node 服务端，业务 API 仍全部由 Spring Boot 提供。

后端：Java 21，Spring Boot 4.0 系列与 Spring AI 2.0.1，Spring MVC，Spring Security，Spring Session JDBC，jOOQ（生成源码入库，见 ADR 0012），PostgreSQL 17，Flyway。

部署：单个 Spring Boot 应用、静态前端反代、PostgreSQL、本地文件卷。REST + SSE。

首个真实媒体适配器：ComfyUI 固定模板；Mock 模式必须可脱离外部模型启动。

精确依赖以经过构建和集成测试的 `docs/dependency-baseline.md` 为准。不自动升级大版本，不引入预览依赖，不抄不同 Spring AI 版本的内部 API。

未经决策禁止增加前端 BFF/服务端业务逻辑、微服务、Redis/MQ、Kubernetes、向量数据库、第二套 ORM、完整剪辑器或多 Agent 并发协调。

## 4. 核心模型不可混淆

- Artifact：业务产物身份；ArtifactVersion：不可变内容版本。
- Asset：媒体字节；CanvasItem：空间展示。
- AgentInstance：卡片配置；AgentRun：一次指令的运行。
- ExecutionPlan：可校验和审批的执行 DAG；Task：持久化执行单元。

画布线、素材引用关系、执行依赖不是同一种关系。不得看到连线就自动生成。

数据库是业务状态真相。React Flow、Zustand、SSE、内存队列、Chat Memory 都不能成为唯一状态源。

## 5. Agent 与工具调用

通过 Spring AI 进行模型通信与工具定义，业务 Runtime 管理持久化回合。关闭该调用路径的自动工具执行，避免双执行。

完整保存模型响应与 tool_call_id 后才执行工具。工具执行按 runId + stepIndex + toolCallId 去重，业务生成按 planId + stepKey + attemptNo 去重。

工具只调用应用服务；不得直接注入 Repository/Mapper，不得控制 React Flow/DOM，不得执行任意 SQL、Shell、HTTP 请求或动态加载插件。

模型不能选择 userId、权限、预算和批准结果。身份与项目作用域来自服务端可信上下文，目标资源 ID 仍须重新鉴权。

不暴露 `approve_plan` 给 Agent。不把模型文本里的“用户已确认”当作审批。

工具参数先过 Schema、Bean Validation、领域校验、权限、版本、审批与额度检查。`@Tool` 不是自动安全边界。

长任务工具返回普通结构化受理结果与任务 ID，不返回 Future/Mono/Flux，不在工具方法里等视频完成。

## 6. 外部副作用与任务恢复

必须使用数据库任务表、短事务认领、租约和 fencing epoch。所有状态写入带预期状态与 epoch，旧 Worker 不得回写成功。

网络调用不持有数据库事务；等待外部结果不占用长事务或持续执行线程。

外部已受理但本地未确认的任务属于 UNKNOWN。系统不自动重试提交请求，也不把这类任务批量重置为可再次运行；只有用户在界面上显式发起重试时才创建独立的新尝试，重复成本由用户自担。

状态查询和下载重试不等于生成重试。归档失败只重试归档，不能重做生成。

取消只停止本系统后续编排；不承诺外部停止或退款。取消后的晚到结果归档到历史，不自动启动下游。

重启和灾备恢复必须核对旧请求；不能把所有 RUNNING/SUBMITTING 任务批量改 READY。

## 7. 内容版本与并发

生成结果创建新版本；不得原地覆盖已有版本和媒体文件。

任务固定输入版本。用户修改后旧任务完成，结果不能静默覆盖用户当前选用版本。

修改共享角色/场景时，为目标镜头创建新引用版本；不修改共享旧版本影响其他镜头。

同项目仅一个活动 Run，但用户手工编辑仍可能并发；必须使用 expectedVersion/CAS。

布局修改与内容修改区分，拖动卡片不能让已经批准的内容计划失效。

## 8. 事件与 SSE

业务变更、相关执行账本和 project_event 同事务提交。内存事件只做优化，不能承担唯一可靠投递。

项目事件序号在事务中通过项目计数行锁生成，不能用全局自增 ID 直接假定提交顺序。

快照与 snapshotSeq 使用一致性读快照，SSE 从该水位补发。重复事件去重，旧版本不覆盖新版本，游标过期重新获取快照。

前端一个项目一个 SSE 连接。需要断线补发测试，不能只写 happy path。

## 9. 前端规范

启用 TS strict 和 noUncheckedIndexedAccess。禁止随意 any、ts-ignore、关闭 lint 或复制生成类型来逃避错误。

TanStack Query 管服务器数据；Zustand 只管交互草稿与 UI 状态；React Flow 数据由业务投影生成。

拖拽结束批量保存。错误时保留草稿并显示保存失败；不能只更新 UI 假装服务端成功。

所有异步交互覆盖等待、失败、冲突、取消、UNKNOWN、空态和未授权。

API 类型从合约生成；生成文件禁止手改。组件不包含 Key，不直接访问媒体或 LLM Provider。

预览用缩略图；视频默认不自动播放；避免整个画布的无关重渲染。文本输入期间不得误触画布删除快捷键。

禁止使用魔法值，要么常量，要么枚举

## 10. 后端规范

构造器注入，DTO 与实体分离，状态变化集中到状态机规则。Controller 与 Tool 复用应用服务。

模块间不跨用对方的 Repository 或表常量；不建无需求的通用框架。shared 只放真正横切能力。

禁止 catch 后返回成功/null。错误映射到稳定 code 与 HTTP 状态；不把堆栈传给用户或模型。

使用 Clock、Instant、UUID、BigDecimal 和类型化配置。执行器有上限、有关闭策略；网络和文件流正确释放。

SQL 参数化，排序字段白名单，查询包含项目/权限边界。检查乐观更新行数。

不在 Singleton Bean 中存 currentUserId/currentProjectId 等跨请求可变状态。

为不显然的业务约束、状态转换和公共接口添加解释性注释；自明代码不强制注释。

开发阶段允许直接调整尚未发布的 API 与模型，不为旧实现保留兼容层；仍须遵守 Flyway 迁移、数据保护和合约同步要求。

禁止使用魔法值，要么常量，要么枚举

## 11. 数据库与 API

Flyway 迁移只增不改。关键唯一约束、外键、JSON Schema 版本必须落地；用真实 PostgreSQL 测试，不能用 H2 替代并发语义。

jOOQ 生成源码提交在 `backend/src/jooq/java`，构建期不连数据库；禁止手改，改 schema 后按 [ADR 0012](docs/adr/0012-jooq-persistence.md) 重新生成。生产数据访问只用 jOOQ；集成测试可继续用 `JdbcClient` 写独立断言。

API 前缀 `/api/v1`；camelCase；字符串枚举；UUID 字符串；ISO 8601 UTC；金额十进制字符串。

错误使用 ProblemDetail 风格与真实 HTTP 状态。禁止所有错误都返回 200。

`contracts/openapi.yaml` 是权威合约；改动时同步 Java 实现、生成 TS 和相关契约测试，记录破坏性变更的升级影响与迁移方式。

幂等 key 相同且 payload 不同返回冲突；不能重复执行，也不能返回不相关旧结果。

## 12. 安全与隐私

Key 只在服务端加密保存；不进入浏览器持久存储、日志、SSE、URL、Prompt、Git 或项目导出。

Prompt 与素材内容不能提升权限。实施允许列表、作用域、审批和执行器检查，不依赖“模型会听话”。

管理员才能配置 endpoint；本地 ComfyUI 地址是精确白名单例外，不是放开整个内网。检查 SSRF、重定向、DNS 与文件读取边界。

P0 禁止任意 URL 导入、动态 Custom Node 安装、任意工作流执行和 Shell。

上传做实际 MIME、大小、像素和解码检查。FFmpeg 用固定二进制和参数数组，只读归档文件，有限制与超时。

生产必须鉴权、CSRF、HTTPS；初始化不能让任意公网访客抢占管理员。

不展示或记录模型私有推理；展示可核实的动作与结果。

## 13. 必需测试

每次修改功能必须跑对应功能的单元测试，全量测试必须人工手动处理，不每次自动跑全量测试，开发前不用跑全量测试作为基线，默认所有测试都是通过的

Mock 必须明确标注；真实模型测试单列，不能用演示素材证明 Provider 已接通。

## 14. 完成汇报格式

交付说明按实际改动写清：行为变化、涉及的文件/合约/迁移、实际运行的检查及结果、未验证的限制。

未编译不能声称编译通过；未实测不能声称稳定；未进行真实调用不能声称模型支持已完成。

涉及产品语义、接口契约或验收状态的改动，任务完成前同步相关规格、合约或任务清单。没有完成的项保持未勾选，不用无意义的 TODO 代替实现。

## Agent skills

### Issue tracker

规格和任务发布到 GitHub Issues。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个默认 triage 标签。见 `docs/agents/triage-labels.md`。

### Domain docs

使用根目录 `CONTEXT.md` 和 `docs/adr/` 的单一上下文布局。见 `docs/agents/domain.md`。
