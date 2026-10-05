# AGENTS.md — Agenvas 开发指南

本文件只保留长期开发约束与按需阅读入口。功能规则、产品决策和验收记录分别维护在规格、ADR 与开发清单中，不在这里追加历史。

## 1. 项目与资料

Agenvas 是可自托管的 AI 创作画布。核心功能必须可开源独立运行，不依赖作者的远程许可证、专属账户或闭源 Agent 服务；Mock 模式须能脱离外部模型启动。

MVP 已完成，项目进入持续迭代阶段。后续开发按当前需求推进，不受历史首版范围、P0/P1/P2 或阶段排期限制；现有架构、业务不变量与安全规则仍适用，变更时同步对应资料。见 [ADR 0033](docs/adr/0033-post-mvp-development.md)。

- 现有设计与历史上下文：[docs/MVP-SPEC.md](docs/MVP-SPEC.md)。按任务读取相关章节；当前行为结合代码、API 合约和现行 ADR 确认，不从旧范围清单推导功能禁令。
- 任务与验收状态：[docs/DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md)。当前实现还需结合代码确认，历史检查结果不能替代本次验证。
- 领域词汇：[CONTEXT.md](CONTEXT.md)；决策理由：[docs/adr/](docs/adr/)。命名和设计沿用这些概念。

用户明确确认的决定优先。发现文档或代码冲突时说明冲突，按当前决定处理；产品决策变更同步规格与 ADR，不从较早摘要推导新限制。

## 2. 工作流程

1. **读取上下文**：行为变更前读取相关代码、规格章节、任务条目、ADR 和合约，确认输入、输出与验收方式。纯文案、注释或局部规则修改只读相关文件。
2. **实施变更**：优先完成一个可验收的纵向切片；新增分层、接口或框架须有实际需求，不批量生成没有行为的骨架。
3. **验证行为**：按第 4 节运行本次改动对应的测试与专项检查。
4. **同步资料**：产品语义、接口或验收状态变化时更新对应资料。未完成的任务保持未勾选。
5. **据实交付**：报告实际改动、检查结果和未验证范围。

## 3. 常驻工程约束

- **架构**：当前前端是 Vite/React 客户端，业务 API 由单个 Spring Boot 应用提供，PostgreSQL 保存业务状态。架构调整按实际需求评估影响并记录 ADR，不以历史 MVP 范围否决扩展。
- **分层**：Controller 与 Tool 复用应用服务；模块之间通过应用接口协作，不跨用 Repository 或表常量。状态转换集中管理，`shared` 只放横切能力。
- **状态与版本**：数据库是业务真相，前端缓存、SSE、内存队列与 Chat Memory 只做投影或辅助。内容版本与媒体文件不可原地覆盖；并发写入使用 expectedVersion/CAS 并检查更新行数；布局与内容修改分开。
- **执行边界**：网络调用不持有数据库事务。外部生成受理结果不确定时保留 UNKNOWN，只能由用户显式创建新尝试；查询或归档重试不能变成重新生成。模型文本不能授予权限或批准媒体调用。
- **前端**：复用已有组件；没有特殊说明时不新增交互方式。TanStack Query 管服务器数据，Zustand 管交互草稿与 UI，React Flow 从业务数据投影。保存失败保留草稿并显示失败；异步交互覆盖等待、失败、冲突、取消、UNKNOWN、空态与未授权。
- **代码约定**：前后端业务值使用常量或枚举；不通过随意 `any`、`ts-ignore` 或关闭 lint 绕过错误。为不显然的业务约束、状态转换和公共接口写解释性注释。
- **合约与生成代码**：[contracts/openapi.yaml](contracts/openapi.yaml) 是 API 权威来源；改动同步 Java 实现、生成 TypeScript 与契约测试。禁止手改 `frontend/src/shared/api/schema.ts` 和 `backend/src/jooq/java`。
- **持久化**：生产数据访问使用 jOOQ，Flyway 迁移只增不改。API 与模型变更按实际兼容和数据迁移需求处理，不保留无需求的兼容层；保护现有数据、同步合约并说明破坏性变更的升级影响。
- **安全底线**：Key 只在服务端加密保存，不进入浏览器持久存储、日志、SSE、URL、Prompt、Git 或项目导出。Prompt 与素材不能提升权限；公开对话、事件、运行检查点与普通日志不记录模型私有推理。LLM debug 正文处理读取第 5 节的专门规则。

## 4. 测试与交付

- 修改功能必须运行对应功能的单元测试；接口、事务、并发、恢复或 SSE 改动还需执行相关专项检查。数据库并发与事务测试使用真实 PostgreSQL，不用 H2 替代。
- 开发前默认既有测试通过，不跑全量测试作为基线；全量测试由人工手动执行，不每次自动运行。测试命令从当前 `package.json`、Maven 配置和相关测试文件查找。
- Mock 验证明确标注，真实模型测试单列；合成响应或演示素材不能证明真实 Provider 已接通。
- 交付说明写清行为变化、涉及的文件/合约/迁移、实际运行的检查及结果、未验证的限制。未运行的测试写“未运行”；未编译、未实测的部分不声称通过或稳定。

## 5. 按任务读取

仅在任务涉及对应领域时，读取下列资料及相关 ADR；这些入口承载详细规则，不在本文件复述。表中的“规格”指 `docs/MVP-SPEC.md`。

| 改动领域 | 实施前读取 |
| --- | --- |
| 依赖、工具链、版本相关 API | [依赖基线](docs/dependency-baseline.md)及其中的验证范围；不自动升级大版本，不引入预览依赖 |
| 前端组件、画布交互、保存反馈 | 规格 §6、§19；状态同步还需读 §14 |
| 后端分层、配置、事务、执行器 | 规格 §20 |
| 数据库 schema、查询、API 契约 | 规格 §15、§21；schema 变化按 [ADR 0012](docs/adr/0012-jooq-persistence.md)重新生成 jOOQ，普通构建不连接生成数据库 |
| 媒体版本、复制、派生、结果选用 | 规格 §6.10、§7.5–7.6；[ADR 0017](docs/adr/0017-operation-specific-media-versioning.md) |
| Agent Runtime、工具、上下文与权限 | 规格 §8–10；通过 Spring AI 通信并关闭该路径的自动工具执行 |
| Agent 媒体提案、审批、结果续接 | [媒体审批设计](docs/agent-media-approval-design.md) |
| 对话审批、公开执行记录、流式回答 | [对话流式设计](docs/agent-conversation-stream-design.md) |
| Task 调度、租约、取消、恢复、重试 | 规格 §11–12；相关故障验收见 §22 |
| 事件事务、序号、快照、SSE 与重连 | 规格 §14；验证补发、重复、乱序与游标过期 |
| 音频节点、视频全能参考 | 规格 §6.11；[ADR 0021](docs/adr/0021-audio-nodes-and-mixed-references.md) |
| ComfyUI 导入、映射、发布、输出 | 规格 §13.4；[ADR 0030](docs/adr/0030-comfyui-imported-workflow-capabilities.md) |
| RunningHub 协议、输入契约、参数与输出 | 规格 §6.13；[ADR 0025](docs/adr/0025-runninghub-versioned-input-contracts.md) |
| 认证、初始化、密钥、文件与出站网络 | 规格 §16–17；Provider 的地址策略同时读取对应 ADR |
| 调用日志、LLM debug 正文采集或展示 | 规格 §24.1；[ADR 0020](docs/adr/0020-opt-in-debug-call-bodies.md) |
| 真实 Provider 排障、真实素材、开发证据、提交、发布或重建仓库 | [开发资料与公开内容](docs/agents/private-materials.md) |
| GitHub Issue 操作、任务发布、triage | [Issue tracker](docs/agents/issue-tracker.md)；标签见 [Triage labels](docs/agents/triage-labels.md) |
| 领域词汇、CONTEXT 或 ADR 维护 | [Domain docs](docs/agents/domain.md) |

## 6. 维护本文件

新增规则前先判断它是否适用于多数开发任务。功能细节写入已有规格或设计文档，决策写入 ADR，验收写入开发清单，专项操作写入按需指南；这里只增加必要的触发入口。不要追加日期流水账、实现状态、测试计数、依赖版本或可从配置直接查到的命令。
