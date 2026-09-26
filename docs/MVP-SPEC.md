# Agent Canvas — MVP 产品与工程规格

**文档版本：1.0｜目标产品版本：v0.1.0｜编写日期：2026-09-22**

> 定位：从零开发、可自托管、核心 Agent 能力完整开源的 AI 创作画布。
> 后端固定为 Spring Boot + Spring AI。本文是开发规格，不是已经实现、编译验证或压测通过的系统。
> 所有性能数字、并发数和资源规格均为本项目的初始设计目标；发布前必须按验收清单实测。

---

## 0. 阅读方式与决策状态

配套文件：`AGENTS.md` 是 AI 编码约束；`DEVELOPMENT-CHECKLIST.md` 是开发顺序与验收清单。它们不能替代本规格中的产品语义。

**已经确认的用户要求**：从零开发，不 fork 现有业务项目；开源产品；画布中嵌入可工作的 Agent；Agent 可以生成、执行并修改创作流程；后端使用 Spring AI；首版重视稳定性。

**本稿采用的产品假设**：桌面 Web 优先；自托管个人创作者优先；首版单管理员、不开公共注册；先完成三镜头短片这一条创作闭环；同一项目同一时间只允许一个活动 Agent Run。多用户协作、收费 SaaS 和任意插件运行不属于本版。

**本稿采用的技术决策**：Vite SPA 而不是 Next.js 全栈；Spring MVC 而不是全栈 WebFlux；模块化单体而不是微服务；PostgreSQL 持久化任务而不是第一天引入消息中间件；默认本地文件存储；REST + SSE；一个 Creator Agent 配置，多实例展示，受控串行执行。

**媒体接入假设**：首个真实媒体适配器采用 ComfyUI，接入两份受信任的固定工作流，分别完成生图与图生视频。它只是可替换的推理服务，不是本产品的画布、业务模型或 Agent 内核。LLM 通过 Spring AI 接入一个经过工具调用测试的模型端点。2026-09-25 确认的后续交付为界面配置的媒体能力目录、统一执行内核，以及固定代码实现的 GPT Image 2 图片与火山方舟 Seedance 视频适配器；见[基础规格](superpowers/specs/2026-09-25-media-capability-foundation-design.md)、[固定渠道规格](superpowers/specs/2026-09-25-fixed-media-provider-adapters-design.md)和[ADR 0002](adr/0002-fixed-media-adapters-before-workflow-platforms.md)。RunningHub 类动态脚本接入已撤回，不作为当前实施依据。

“从零”指自主实现产品和领域模型，不指重写 React、画布引擎、数据库或模型推理框架。

### 0.1 规格用语

- **必须**：v0.1.0 发布门禁，不满足不能宣称完成。
- **应当**：默认实现，偏离需要在 ADR 中记录理由与测试。
- **可以**：扩展点，不计入本版承诺。
- **P0**：本次发布必需；**P1**：下一阶段；**P2**：探索阶段。

### 0.2 稳定性的定义

本项目所说的稳定，不是“每次 AI 都生成满意的作品”，而是：用户内容不被静默覆盖；状态可解释；外部调用不因本地重复执行而无约束地重复提交；重启后能判断继续、核对或等待人工；失败不会使整个项目不可用。

系统稳定性与模型画质、角色一致性、供应商可用性分别验收，不混为一个“成功率”。

---

## 1. 产品定位与首版成功标准

### 1.1 一句话定位

**一个让 Agent 在可编辑画布里完成创作，并允许人随时检查、修改和接管的开源工作空间。**

用户不需要手工搭所有流程。Agent 负责理解需求、产生脚本和分镜、提出生成计划、调用受控工具。画布展示输入、产物、依赖和执行状态，而不是仅展示最终聊天文本。

### 1.2 核心交互对象

画布上必须能看到真正可操作的 **Agent 卡片**。卡片具有指令、输入引用、输出区域、状态、运行按钮、停止按钮、待审批提示和执行记录入口。

聊天侧栏是辅助操作界面，不得以“只有侧栏聊天 + 被动图片节点”替代画布中的 Agent。

### 1.3 首版黄金路径

1. 用户创建项目，选择画幅并添加 Creator Agent 卡片。
2. 上传一张产品或角色参考图，将其绑定到 Agent 输入，或直接输入创作要求。
3. 输入：“制作一个 15 秒、三个镜头的咖啡广告，现代极简风。”
4. Agent 读取受限项目上下文，创建创作说明、脚本、三个镜头及待生成图片卡片。
5. 用户看到生成计划、引用素材和预计用量，确认图片生成。
6. 图片完成后，用户选择需要保留的版本；再确认视频生成计划。
7. 视频完成后，用户按镜头顺序导出无声 MP4。
8. 用户选中第二个镜头，要求“改成夜景，其他镜头不变”。系统只创建相关内容的新版本与必要的新任务。
9. 页面刷新或服务重启后，项目、历史结果和任务状态仍可恢复。

首版不承诺任意题材一步成片，不承诺像素级角色一致，也不承诺完全确定性的生成结果。

### 1.4 验收判定

黄金路径必须分别在 Mock 模式和至少一个真实媒体 Provider 上通过。Mock 通过只能说明应用链路成立，不能作为真实模型接入或作品质量通过的证据。

---

## 2. 功能范围与明确不做的事情

| 模块 | P0 必须交付 | 明确边界 |
|---|---|---|
| 初始化与登录 | 单管理员初始化、登录、退出、修改密码 | 不开放公共注册，不做团队邀请 |
| 项目 | 创建、列表、打开、重命名、归档 | 不做跨组织权限体系 |
| 无限画布 | 平移、缩放、框选、拖拽、缩放卡片、基础对齐、适配视图 | 不做自由绘画和专业矢量编辑 |
| Agent 卡片 | 添加、配置指令、绑定输入、运行、停止、查看状态与记录 | 一种内置 Agent 配置；不并行协商 |
| 创作产物 | 文本、图片、视频、角色说明、场景说明、镜头 | 角色/场景首先是结构化说明，不是专业资产管理系统 |
| Agent 能力 | 读取受限上下文、创建/修改产物、整理画布、提出生成计划 | 不允许任意 SQL、Shell、网页控制 |
| 图片 | 上传参考图、生成图片、查看历史版本、选用版本 | 两份固定媒体工作流之一；不做画笔局部重绘 |
| 视频 | 以选定关键帧生成视频、预览、下载 | 一种真实图生视频工作流；不做口型驱动 |
| 导出 | 按镜头顺序拼接无声 MP4；导出项目 JSON 与素材清单 | 不是完整时间线剪辑器；JSON 导入不在 P0 |
| 计划与审批 | 预览影响范围、生成数量、已知/未知成本、确认/拒绝 | 默认分图片与视频两阶段审批 |
| 可靠性 | 持久化任务、幂等、状态核对、SSE 补发、版本冲突 | 不宣称跨供应商 exactly-once |
| 设置 | LLM 端点、媒体服务配置、密钥状态、连接诊断、限额 | 不做几十家模型供应商市场 |
| 开源交付 | Docker Compose、文档、测试、示例、Mock 模式、许可证清单 | 无强制官方账号、无远程许可证校验 |

P1：界面配置的固定媒体能力与 GPT Image 2/Seedance 适配器、S3/R2 存储实现、自定义 Agent 配置、自定义受审 Skill、MCP 白名单接入、简单音轨、项目导入、较完善的内容撤销。RunningHub 类工作流平台及动态脚本当前不排期。

P2：多个 Agent 并发协调、多人协作、插件市场、完整剪辑时间线、组织权限、云托管计费、移动端创作、3D、任意工作流编辑器。

**不得为了“以后可能有用”先引入微服务、Kafka/RocketMQ、Redis 集群、向量数据库或通用工作流引擎。**

---

## 3. 核心概念与必须守住的边界

| 概念 | 负责什么 | 不负责什么 |
|---|---|---|
| Project | 一次创作的权限、配置与资源边界 | 不是一条聊天会话 |
| Artifact | 文本、镜头、图片等业务产物的稳定身份 | 不是 React Flow Node |
| ArtifactVersion | 某次内容及其输入引用的不可变版本 | 不是最新内容的可变缓存 |
| Asset | 实际媒体文件、哈希、大小、存储位置 | 不是带提示词的业务对象 |
| CanvasItem | Artifact 或 AgentInstance 的空间展示 | 不存业务执行状态的唯一真相 |
| AgentProfile | 内置系统提示词、允许工具、Skill 和策略版本 | 不存某次运行进度 |
| AgentInstance | 画布里的 Agent 配置实例和输入绑定 | 不等于一个永久运行的线程 |
| AgentRun | 一次用户指令的执行生命周期 | 不等于一次 HTTP 请求 |
| ExecutionPlan | 经验证的步骤、依赖、输入与影响范围 | 不等于素材引用关系 |
| Task | 可恢复的本地或外部执行单元 | 不等于前端 loading 状态 |
| Approval | 用户对具体计划版本与额度的授权 | 不是模型自己输出的同意 |
| ProjectEvent | 已提交业务变更的增量通知 | 不是全部业务数据的唯一存储 |

### 3.1 三种图必须分开

**画布图**：卡片摆在哪里、哪些卡片被分组。

**素材关系图**：某张图片引用了哪个角色，某个视频由哪个关键帧生成。部分引用关系允许形成环，不能把全部关系都当作有向无环图。

**执行计划 DAG**：任务依赖、输入输出类型、哪些任务已就绪。此图必须无环，并经过服务端校验。

因此不能用“画布上连了一条线”直接触发付费生成，也不能仅凭 Artifact 关系恢复完整执行流程。

### 3.2 数据真相

PostgreSQL 保存业务状态与执行状态；文件存储保存媒体字节。前端缓存、内存队列、SSE 连接、Spring AI Chat Memory 都不是唯一真相。

---

## 4. 技术栈与版本策略

### 4.1 前端

| 项目 | 选型 | 使用约定 |
|---|---|---|
| 应用形态 | Vite + React SPA | 全部业务 API 直接访问 Spring Boot；不再建 Node BFF |
| 语言 | TypeScript | strict；禁用无约束 any；外部输入按 unknown 校验 |
| React | 19.x 稳定系列 | M0 锁定精确版本；不能混用预览版 |
| 构建工具 | Vite 正式版 | M0 根据依赖 engines 锁定精确版本 |
| 画布 | @xyflow/react 12.x | 仅使用开源核心；业务能力自主实现 |
| 路由 | React Router 7.x，SPA 模式 | 不引入第二套路由系统 |
| 服务端数据 | TanStack Query 5.x | 统一管理已持久化实体和请求状态 |
| 交互状态 | Zustand 5.x | 仅放选择、视口、拖拽草稿、待提交命令等 |
| UI | Tailwind CSS 4.x + shadcn/ui / Radix | 引入的组件代码纳入仓库管理，不复制 Pro 示例 |
| 表单 | React Hook Form + Zod | 前端体验校验；后端仍是最终校验方 |
| API 类型 | OpenAPI 生成 TS 类型 + fetch 封装 | 禁止前后端分别手写一套接口定义 |
| 测试 | Vitest、Testing Library、Playwright、MSW | 单元、交互、E2E、失败场景 Mock |
| 包管理 | pnpm，精确 packageManager | 只保留 pnpm-lock.yaml |
| 构建运行时 | Node.js 24 LTS | 本次官方页面列出 24.21.0；构建镜像锁定精确 tag/digest [S11] |

选择 Vite 是本项目的架构判断：P0 是登录后的高交互编辑器，不依赖 SEO、SSR 或服务端 React 功能，独立 Java 后端已承载业务与鉴权。后续营销站可以独立建设，不要求编辑器改成 Next.js。

React Flow 官方说明其库保持 MIT 开源，Pro 提供额外示例与支持；不能把核心库的 MIT 许可理解成所有 Pro 素材都可直接复制。[S09]

### 4.2 后端

| 项目 | 基线 | 使用约定 |
|---|---|---|
| Java | 21 | 使用同一 JDK 发行版的受维护补丁；编译 target=21 |
| Spring Boot | 4.0.8 | 作为本稿起始基线，不宣称是最新主线版本 [S02] |
| Spring AI | 2.0.1 | 使用 spring-ai-bom；禁止混入 1.x 的内部实现 [S01] |
| Web | Spring MVC | REST、SseEmitter；长任务交给持久化调度 |
| 安全 | Spring Security + Spring Session JDBC | 同源 Cookie Session；不把 JWT 存 localStorage |
| 数据访问 | MyBatis-Plus 3.5.17，Boot 4 starter | 不再叠加另一套 MyBatis starter [S03] |
| 数据库 | PostgreSQL 17.11 | 17 系列正式维护版本；后续补丁经 CI 升级 [S14] |
| 迁移 | Flyway | 所有表和索引通过有版本迁移创建 |
| 校验 | Jakarta Validation + 领域校验 | 结构正确不等于业务正确 |
| HTTP 客户端 | Spring RestClient；模型调用由 Spring AI 管理 | 明确连接、读取、总体超时与重试策略 |
| API 文档 | springdoc-openapi 3.0.x | Boot 4.0.x 对应分支，M0 锁定补丁 [S13] |
| JSON | 遵循 Boot 4 的 Jackson 依赖管理 | 不直接照搬 Boot 3 / Jackson 2 的包名及插件 [S22] |
| 可观测性 | Actuator + Micrometer；预留 OTel 导出 | 不要求开发环境先部署整套监控集群 |
| 测试 | JUnit、Mockito、Testcontainers、HTTP Stub | 使用真实 PostgreSQL 验证锁与 JSONB 行为 |
| 构建 | Maven Wrapper | 所有直接依赖及构建插件锁定版本 |

Spring AI 2.0.x 官方兼容范围包括 Spring Boot 4.0.x 和 4.1.x。Spring Boot 4.0.8 的 Java 要求包含 Java 21，因此本稿采用这条组合；这不替代全依赖的编译与集成测试。[S01][S02]

Spring MVC 支持异步响应和 SSE，本项目不因为需要流式进度就把数据库访问和整个应用改成响应式。[S07]

### 4.3 基础设施

默认部署只有三个服务：`web`、`server`、`postgres`。媒体服务是外接推理能力，Mock 模式不需要它。

- web：静态前端 + Nginx 反向代理。
- server：一个 Spring Boot 应用，内部包含受限的 Agent、任务和媒体处理执行器。
- postgres：业务库、会话、任务、事件。
- 文件：server 的持久卷；通过 StorageGateway 隔离；P1 实现 S3/R2。
- FFmpeg/ffprobe：固定版本，作为本地受控子进程完成探测与顺序导出，不接受用户或模型提供的任意命令。

默认不引入 Redis、不引入 MQ、不引入 Kubernetes、不需要第二个 Node 运行服务。

### 4.4 依赖冻结门禁

M0 必须产出 `dependency-baseline.md`，记录 JDK、Node、pnpm、Maven、所有直接前端依赖、Boot/AI/MyBatis/springdoc、数据库和镜像 digest。执行依赖解析、构建、启动、JSONB 读写、Tool Calling、SSE、OpenAPI 生成测试后才算冻结完成。

本文列出的精确版本是已经核对官方文档的起始候选，不是“已在本项目联调通过”的声明。其余工具的补丁号不能由 AI 编造。

---

## 5. 系统架构与部署边界

```text
浏览器：React / React Flow / Query / Zustand
                │
                │ 同源 HTTPS：REST 命令 + SSE 事件
                ▼
          Nginx：静态站点 / 反向代理
                │
                ▼
┌────────── Spring Boot 模块化单体 ──────────┐
│ Identity / Project / Canvas / Artifact    │
│ Agent Runtime（Spring AI）                │
│ Plan & Approval / Task Scheduler          │
│ Provider Gateway / Asset / Export        │
│ Event Stream / Usage / Operations         │
│                                          │
│ 同步请求：校验 → 短事务 → 返回             │
│ 持久化 Worker：认领 → 执行 → 状态提交       │
└───────┬────────────┬───────────────┬───────┘
        │            │               │
        ▼            ▼               ▼
 PostgreSQL     本地文件卷      外部推理服务
 状态/任务/事件   原文件/缩略图   LLM / ComfyUI
                     │
                     ▼
             受限 FFmpeg 子进程
```

### 5.1 模块职责

| 模块 | 主要职责 |
|---|---|
| identity | 初始化、登录、会话、资源归属校验 |
| project | 项目设置、归档、活动 Run 槽位、项目事件序号 |
| canvas | 卡片布局、Agent 输入绑定、前端快照 |
| artifact | 产物身份、不可变版本、语义引用、过期标记 |
| agent | 上下文、模型回合、工具执行、检查点、Run 状态 |
| plan | 计划校验、步骤拓扑、授权范围、审批 |
| task | 认领、租约、心跳、重试分类、核对、取消 |
| provider | 聊天和媒体适配、能力声明、请求映射 |
| asset | 上传、文件校验、归档、下载、存储抽象 |
| export | 顺序剪辑说明、FFmpeg 导出、结果入库 |
| event | 项目事件日志、SSE、补发、快照水位 |
| usage | 用量记录、预留额度、未知成本处理 |

所有模块在一个部署单元内。通过应用服务或窄接口调用，不通过内部 HTTP 互调，不按模块拆数据库。

### 5.2 三条执行路径

**普通编辑**：HTTP → 身份与版本检查 → 短事务修改内容并追加事件 → 返回 DTO。

**Agent 操作**：HTTP 创建 Run 与可运行任务 → Worker 调用 Spring AI → 持久化模型响应 → 受控工具调用 → 领域服务 → 事件。

**媒体生成**：批准具体计划 → 事务中创建任务与用量预留 → Worker 提交 Provider → 保存外部任务标识 → 定时查询 → 文件归档 → 产物新版本 → 唤醒依赖任务。

LLM 调用、文件下载、视频生成、FFmpeg 运行都不放在数据库长事务中。

---

## 6. 页面结构、画布交互与用户体验

### 6.1 页面路由

| 路由 | 内容 |
|---|---|
| `/setup` | 首次初始化；已初始化后不可再次执行 |
| `/login` | 登录 |
| `/projects` | 项目列表、创建项目、归档入口 |
| `/projects/:projectId` | 创作工作区 |
| `/settings/providers` | LLM / 媒体配置、诊断、能力与密钥状态 |
| `/settings/general` | 密码、语言、存储与系统诊断 |

### 6.2 工作区布局

顶部：项目名称、保存状态、画幅、导出按钮、设置入口。

画布创建入口：在空白处双击或点击悬浮“+”打开同一七类菜单（文字、图片、视频、角色、场景、镜头、Agent）；不保留左侧创建栏。

中央：无限画布，默认显示关系层，可切换“创作视图 / 执行状态视图”。目标工作区采用深色点状背景；缩放控件位于右下角，菜单在双击处出现并避让视口边缘，新卡片落在双击对应的画布坐标。

右侧：选中对象属性、版本选择、引用绑定和历史，按当前对象切换。

底部：单选时显示随类型切换的内容编辑区，空选时隐藏，多选时显示批量操作；另保留运行步骤、待审批、失败与待核实任务、用量记录抽屉。Agent 长期配置指令与本次运行指令分开处理。顶部“资源”按钮打开可搜索抽屉，可找回并重新放置已从画布移除的草稿和历史产物；上传从顶部“导入素材”进入，对齐在多选操作中，导出从顶部进入。

桌面最小设计宽度 1280px；较窄视口展示只读或简化提示，P0 不承诺移动端完整编辑。

### 6.3 卡片种类

Artifact 卡片：`TEXT`、`IMAGE`、`VIDEO`、`CHARACTER`、`SCENE`、`SHOT`。

工作卡片：`AGENT`；它绑定 AgentInstance，不强塞进 Artifact 类型。

状态用颜色、文字和图标共同表达，不能只靠颜色。视频默认显示海报帧，只有当前预览对象开始播放。

### 6.4 Agent 卡片详细行为

必须展示：名称、输入引用数量、指令摘要、当前状态、最后一项操作、输出区域入口、运行/停止/查看记录按钮。

输入绑定来源：手工连线、选中对象“发送给 Agent”、项目资源列表多选。绑定是明确的数据记录，不靠截图猜测。

空白处创建 Agent 时默认无输入。全项目读取必须明确开启，不能默认把所有素材和提示词发送给远端模型。

连接 Artifact → Agent 表示“可读取的输入”；Agent → 结果表示产出归属；Artifact → Artifact 是语义关系。执行依赖另由计划展示，不能混淆。

### 6.5 自动布局

Agent 调用“排列这些对象”，传对象 ID 与布局意图；服务端按确定性规则产生布局命令。模型不需要猜像素坐标。

默认新结果放在 Agent 输出区域的空位；不得自动整理整个项目或移动用户已锁定卡片。自动跟随新结果可关闭；用户手动平移后停止抢夺视口。

### 6.6 保存与编辑冲突

拖拽中仅修改本地草稿，结束后批量提交布局。文本编辑采用短延迟自动保存；离开页面时仍有未确认修改必须提示。

显示“已保存 / 保存中 / 保存失败 / 内容有冲突”。请求失败时保留本地草稿，不伪装成已保存。

P0 的快捷键撤销只覆盖本地布局与明确支持的编辑命令；跨刷新完整撤销不承诺。内容历史通过创建新版本恢复。任何撤销都不能撤销已产生的外部费用。

### 6.7 审批界面

显示：计划版本、涉及的镜头、将读取的参考素材、模型/工作流、数量、预计时长参数、已知成本或“未知”、是否覆盖当前选用版本、失败重试政策。

按钮为“确认执行此计划”“返回修改”“取消”，不使用含糊的“继续”。批准后改变模型、输入、数量或额度，原审批失效。

### 6.8 失败与待核实界面

生成明确失败：展示原因分类和重新生成入口。

提交结果未知：展示“可能已在外部开始执行；系统不会自动重复提交”，提供核对、保留等待、明确风险后新建尝试。不得把 UNKNOWN 当普通失败显示一个无提示重试按钮。

### 6.9 画布重构与直接媒体运行目标（2026-09-25 已确认，实施中）

七类创建入口沿用文字、图片、视频、角色、场景、镜头、Agent。工作区使用深色点状画布，双击空白处或悬浮“+”打开同一菜单，卡片落在交互坐标；单选时底部按类型编辑，空选隐藏，多选显示批量操作。顶部“资源”抽屉可搜索并重新放置已移除的草稿和产物。交互细节见 [ADR 0005](adr/0005-canvas-interaction-redesign.md)。

图片/视频卡片创建时即有稳定 Artifact 身份，首次生成前允许当前媒体版本为空。提示词、参数和视频输入图保存在独立媒体草稿中，短延迟自动保存；保存失败保留本地输入。图片本轮支持独立文生图，参考图编辑后续再做。视频可先保存不完整草稿，运行前须选择同项目已归档图片的精确版本和有效时长。草稿、当前选用结果和卡片展示状态分离；历史结果由用户显式选回。生成结果追加不可变版本，晚到结果不得覆盖之后的草稿或选用。移除卡片不取消已受理任务，结果可从项目资源找回。

用户在图片或视频卡片点击“运行”即直接受理媒体 Task，不创建 AgentRun 或待审批计划。按钮旁预先显示能力、预计费用或“未知”。Agent 提出的付费媒体计划仍需人工审批，普通内容编辑工具沿用原规则。直接任务固定点击时的草稿、能力和输入版本；排队期间修改草稿只影响下一次运行。同一卡片所有来源的排队、运行或 UNKNOWN 任务互斥，重复点击返回原任务；不同卡片可并行，也可与一个 AgentRun 并行。UNKNOWN 须先核对或经明确风险的新尝试流程。

默认每项目最多三个占用中的媒体任务，每种能力有管理员可配置的全局上限；超额任务持久排队，界面显示前方待处理数量、原因和排位可能变化。UNKNOWN 同时占项目和能力名额；ComfyUI 继续使用全局单槽门禁。点击“运行”时预留任务次数额度，已知价格再预留估算金额；排队取消且未对外提交时释放未消耗预留，UNKNOWN 不自动释放。实现边界见 [ADR 0006](adr/0006-direct-media-task-boundary.md)。当前代码已加入直接任务、草稿、迁移、合约和画布交互；验收状态以任务清单和测试证据为准，真实 Provider 调用尚未实测。

---

## 7. 数据模型与持久化约定

### 7.1 通用约定

主键使用 UUID，API 按字符串传输。数据库时间使用 `timestamptz`，服务端使用 Instant，API 输出 ISO 8601 UTC，界面按用户时区显示。新镜头、计划、用量和导出区间使用整数秒；仅素材探测和已冻结的 v1 历史输入保留毫秒精度。

可变记录有 `version bigint` 乐观锁；内容版本有独立 `version_no`。可查询字段使用普通列，变体内容使用有版本的 JSONB；不得把整个业务系统塞进一个 JSONB 列。

所有跨对象操作验证同项目、同资源归属。关键跨项目关联使用组合外键或等价数据库约束，不能仅依赖前端筛选。

### 7.2 表目录

以下是逻辑表，Spring Session 和 Flyway 自有表另外管理。不是要求建立对应数量的微服务。

| 表 | 必需字段与职责 |
|---|---|
| `app_user` | id、login_name、password_hash、status、created_at；P0 一个管理员 |
| `project` | id、owner_id、name、aspect_ratio、status、active_run_id、event_seq、version、timestamps |
| `artifact` | id、project_id、kind、title、current_version_id、archived_at、version |
| `artifact_version` | id、project_id、artifact_id、version_no、schema_version、content_json、input_refs_json、created_by_kind、run_id、created_at |
| `artifact_relation` | id、project_id、source_artifact_id、target_artifact_id、relation_type、created_at |
| `canvas_item` | id、project_id、subject_type、subject_id、x、y、width、height、z_index、group_id、locked、version |
| `agent_instance` | id、project_id、profile_key、profile_version、name、instruction、output_group_id、version |
| `agent_binding` | id、project_id、agent_instance_id、artifact_id、selected_version_id、binding_type |
| `agent_run` | id、project_id、agent_instance_id、user_id、status、instruction、context_snapshot_json、policy_snapshot_json、profile_version、next_step_index、version、timestamps |
| `agent_message` | id、run_id、seq、role、content_json、provider_metadata_json、created_at；保留协议所需工具关联信息 |
| `agent_step` | id、run_id、step_index、status、request_hash、response_json、model_id、token_usage_json、lease_epoch、timestamps |
| `tool_execution` | id、run_id、step_index、tool_call_id、tool_name、argument_hash、status、result_json、command_id、timestamps |
| `execution_plan` | id、project_id、run_id、revision、plan_json、plan_hash、input_snapshot_hash、status、created_at |
| `approval` | id、project_id、plan_id、plan_hash、scope_json、limits_json、decision、decided_by、expires_at、decided_at |
| `task` | id、project_id、run_id、plan_id、step_key、kind、status、input_json、input_hash、provider_id、provider_request_id、attempt_no、next_action_at、lease_owner、lease_until、lease_epoch、version、error_code、timestamps |
| `task_dependency` | task_id、depends_on_task_id、required_output_key；同项目约束 |
| `provider_attempt` | id、task_id、attempt_no、request_key、request_hash、provider_request_id、submission_status、response_summary_json、timestamps |
| `asset` | id、project_id、storage_backend、storage_key、sha256、mime_type、size_bytes、width、height、duration_ms、status、metadata_json、created_at |
| `provider_config` | id、owner_id、kind、adapter_key、current_config_version、enabled；稳定配置身份 |
| `provider_config_version` | provider_id、config_version、endpoint、credential_ciphertext、key_version、capabilities_json、created_at；不可变连接/凭证版本 |
| `usage_ledger` | id、project_id、run_id、task_id、operation_key、entry_type、quantity_json、estimated_cost、actual_cost、currency、cost_status、created_at |
| `project_event` | project_id、seq、event_id、type、schema_version、aggregate_id、aggregate_version、payload_json、occurred_at |
| `idempotency_record` | principal_id、scope、key、request_hash、state、resource_id、response_json、expires_at |

AgentProfile 与 Skill 的 P0 定义放在版本化配置文件，不额外做一套可编辑平台。

### 7.3 关键唯一约束与索引

| 对象 | 必须约束 |
|---|---|
| 内容版本 | `UNIQUE(artifact_id, version_no)`；内容版本写入后不可原地修改 |
| 工具执行 | `UNIQUE(run_id, step_index, tool_call_id)` |
| 计划 | `UNIQUE(run_id, revision)` |
| 计划任务 | `UNIQUE(plan_id, step_key, attempt_no)`，非计划任务采用独立命令键 |
| 外部请求 | `UNIQUE(provider_id, provider_request_id)` 的非空部分唯一约束，落点可在任务/映射表统一管理 |
| 事件 | `PRIMARY KEY(project_id, seq)`；event_id 唯一 |
| 幂等请求 | `UNIQUE(principal_id, scope, key)`；相同 key 不同 hash 返回冲突 |
| 调度 | `(status, next_action_at)` 部分索引；`lease_until` 恢复扫描索引 |
| 项目读取 | `artifact(project_id, archived_at)`、`canvas_item(project_id)`、`agent_run(project_id, created_at)` |
| 用量 | operation_key 唯一，禁止重复结算 |

活动 AgentRun 的互斥通过 project 行的 active_run_id 和事务锁实现；不能只在 Java synchronized 或 Redis 临时锁里判断。用户直接触发的媒体 Task 不占这个 AgentRun 槽位，另按项目、能力及目标卡片在数据库中限制并发；见 [ADR 0006](adr/0006-direct-media-task-boundary.md)。

### 7.4 Artifact 内容 Schema

TEXT：`format`、`text`。

CHARACTER：`name`、`description`、`appearance`、`referenceVersionIds`。

SCENE：`name`、`location`、`timeOfDay`、`lighting`、`style`、`referenceVersionIds`。

SHOT：`order`、`durationSeconds`（1–30 的整数）、`description`、`camera`、`action`、`characterVersionIds`、`sceneVersionId`、`selectedImageVersionId`、`selectedVideoVersionId`。旧 `durationMs` 内容版本只作为迁移前的不可变历史保留。

生成 IMAGE / VIDEO：`assetId`、`prompt`、`negativePrompt`（可选）、`providerConfigVersion`、`workflowVersion`、`parameters`、`sourceTaskId`。用户上传参考图的 IMAGE 使用互斥分支 `sourceType: UPLOAD`、`assetId`，不伪造生成 Task/Provider 字段；详见 [ADR 0001](adr/0001-upload-image-provenance.md)。

所有 Schema 有明确必填项、字段长度和枚举约束。客户端与模型都不能提供 storage_key、owner_id、审批状态等受保护字段。

### 7.5 版本与生成结果选择

新一次生成产生新的 ArtifactVersion。重新生成不能覆盖已有文件或删掉旧版本。

任务绑定明确的输入版本 ID；任务执行中用户改了参考图，旧任务仍对应旧输入。结果保存后标注“基于旧版本生成”，不能静默选为最新结果。

自动选用结果必须满足 compare-and-set 条件：当前选用版本仍等于任务创建时的预期值，且目标未归档、Run 未取消。否则只保存到历史并提示用户。

同一个 Artifact 在 P0 的多个画布视图中共享内容，不承诺每张展示卡片独立钉住不同版本；钉住的是 Agent 输入与 Task 输入快照。

### 7.6 删除语义

从画布移除只删除/归档 CanvasItem，不删除 Artifact 和 Asset。

归档 Artifact 不删除历史媒体，若有活动任务引用，先提示并取消尚未提交的相关任务。

归档项目后禁止新 Run；已经提交的外部任务仍需核对并记录晚到结果。物理清理是延迟维护任务，必须再次检查引用、活动任务与备份策略。

---

## 8. Agent Runtime：职责与执行协议

### 8.1 Spring AI 的使用边界

使用 Spring AI 的 ChatClient/ChatModel、工具定义、ToolCallback、ToolContext、结构化输出能力及可观测性。Spring AI 2.0 的 ToolCallingAdvisor 可以自动驱动工具循环，但该循环不等于本产品所需的持久化任务恢复。[S04][S05][S06]

**本版选用受控执行方式**：业务 Runtime 管理持久化回合和工具执行；相应 ChatClient 调用关闭默认自动 Tool Calling，避免业务层与 Advisor 各执行一次工具。官方支持关闭自动注册并由应用驱动执行。[S05]

不同时维护两套循环，不根据文本猜“模型可能调用了某个工具”。

### 8.2 一次 Run 的处理顺序

1. HTTP 请求校验会话、项目、Agent 实例与输入引用；原子取得项目活动 Run 槽位。
2. 保存用户指令、输入版本快照、Agent/Skill/Tool 策略版本，创建首个可执行回合任务，返回 runId。
3. Worker 认领回合，构造受限上下文，调用模型；调用不持有数据库事务。
4. 完整保存模型消息与工具调用列表，然后再执行任何会产生业务变更的工具。
5. 每次工具调用按稳定标识查账；已经完成则返回原结果，未完成才进入权限与业务校验。
6. 工具调用应用服务，短事务保存业务结果、工具账本和项目事件。
7. 将工具结果按原 tool_call_id 回填；同一模型回合多个工具调用在 P0 串行处理。
8. 还有工具调用则进入下一回合；需要批准则进入 WAITING_APPROVAL；有媒体任务则进入 WAITING_TASKS，释放线程。
9. 外部任务完成后，依赖状态更新并以持久化任务唤醒 Run；不能依靠浏览器发“继续”。
10. Run 结束或进入终态后释放项目活动槽位，保留产物与日志。

模型请求发出但响应未保存就崩溃，重新请求可能产生额外 LLM 用量；不能承诺这类外部调用零重复。业务写入和媒体生成提交仍通过稳定命令键与审批范围防止重复扩大影响。

### 8.3 Run 状态

`QUEUED → RUNNING → WAITING_APPROVAL / WAITING_TASKS → RUNNING → SUCCEEDED`

其他状态：`BLOCKED`、`CANCEL_REQUESTED`、`CANCELED`、`FAILED`。

- WAITING_APPROVAL：仅由有效的用户审批或拒绝唤醒。
- WAITING_TASKS：由已持久化的任务结果唤醒。
- BLOCKED：输入冲突、提交未知、无可用 Provider 等需要干预。
- CANCEL_REQUESTED：停止创建新步骤与新外部提交，清理未执行任务。
- CANCELED：本系统已停止后续编排；不代表外部全部取消或退款。
- FAILED：本次执行无法继续；保留已完成产物。

UI 在 WAITING_* 状态不显示虚假的“模型正在思考”。

### 8.4 检查点

必须保存完整的应用可用消息序列、工具调用标识、工具执行结果、引用版本、已批准计划、未完成任务、模型配置版本。

需要原样保存供应商协议要求继续对话的元数据，但不把这些元数据或模型私有推理作为前端执行日志展示。前端展示的是可验证的动作摘要、参数摘要和结果。

恢复前先核对账本与任务状态，再构造下一回合。不得简单地把最后一句用户消息重新执行一遍。

### 8.5 上下文组装

顺序：内置系统规则 → 项目创作说明 → 当前 Agent 指令 → 显式输入绑定 → 当前选中对象 → 相关一跳引用 → 本 Run 近期消息 → 必要的历史摘要。

读取素材是按需分页，不把整张大画布 JSON 或全部原图塞进 Prompt。模型具有视觉能力且任务需要时，才传引用图片的受控预览。

当前选择只表示操作意图，不是权限。Tool 服务端仍重新检查目标是否属于项目、是否在授权输入/影响范围内。
创建 Run 时可提交最多 20 个当前选中 CanvasItem ID；服务端核对同项目归属并将主体与当时版本引用固定到 Run 快照，幂等键相同而选择不同须冲突。选择变更不追溯改写运行中快照，也不让未绑定产物获得读取或修订权限。

运行前确认还显示模型配置来源/版本与系统提示词版本。客户端提交确认时携带预览中的版本；若创建时规则版本已变，服务端返回冲突并要求重新预览。新 Run 在策略快照中固定提示词版本；历史无版本快照不能被猜测为某一版。

### 8.6 默认限制

每个 Run 最多 12 次模型回合、40 次工具执行、8 张生成图片、6 段生成视频。默认三个镜头，最多六个镜头；超出需要用户拆分任务或明确修改受控配置。

模型请求默认总体超时 90 秒；Run 的活动规划阶段累计上限 10 分钟，等待审批与外部任务不占用执行线程。Run 总保留等待期限默认 24 小时；到期转 BLOCKED，由用户处理，不直接重复运行。

这些都是项目策略，可配置但不能由模型修改。每次调用前检查额度与取消标志，不能只在开始时检查一次。

---

## 9. Tool 合约、权限与副作用

### 9.1 工具必须薄

`Tool Adapter → Application Service → Domain / Repository`。

Controller 与 Tool 使用同一组业务命令和权限规则。Tool 不直接写 Mapper，不直接操作 React Flow，不直接执行未经审批的外部 HTTP 生成。

### 9.2 P0 工具目录

| 工具 | 输入摘要 | 副作用 |
|---|---|---|
| `read_project_summary` | 无业务身份参数 | 只读 |
| `read_artifacts` | 允许读取的 ID 列表，数量上限 | 只读 |
| `read_selection` | 服务端记录的当前选择引用 | 只读 |
| `create_text` | title、text、用途 | 创建文本产物 |
| `create_character` | 结构化角色描述与合法引用 | 创建角色说明 |
| `create_scene` | 结构化场景描述与合法引用 | 创建场景说明 |
| `create_shots` | 1–6 个镜头，带引用与排序 | 原子批量创建镜头 |
| `revise_artifact` | id、expectedVersion、允许字段 | 创建内容新版本 |
| `place_artifacts` | 1–6 个当前可见产物版本 ID、固定 `AGENT_OUTPUT` 分组 | 创建显示卡片；已有卡片复用 |
| `arrange_items` | 1–6 个输出卡片 ID、内容版本、预期布局版本；水平/垂直/网格 | 仅修改 Agent 输出分组内允许的布局，不修改内容 |
| `link_artifacts` | source Artifact ID 与预期 CAS 版本、target 内容版本 ID；角色/场景→图片引用、镜头→角色/场景四种合法关系 | 在 Run 可见范围内修订 source 的内容引用并创建不可变版本；已有相同引用不重复建版本，不触发执行 |
| `propose_generation_plan` | 目标、工作流类型、输入版本、步骤参数 | 创建待审批计划，不调用媒体 Provider |
| `read_task_status` | taskId 列表 | 只读；正常进度由调度器查询，不让 LLM 反复轮询 |
| `propose_export` | 有序镜头与当前视频的精确版本、区间、项目画幅 | 保存待人工确认的导出提案；不创建 Task、不运行 FFmpeg |

Agent 没有 `approve_plan` 工具。审批只能从经过鉴权的用户 API 发起。

### 9.3 可信执行上下文

userId、projectId、runId、工具权限集合、预算范围由服务端注入，不作为需要模型填入的工具参数。Spring AI 的 ToolContext 支持传递不发送给模型的应用上下文。[S04]

请求中的 Artifact ID 仍是不可信输入。没有把 userId 暴露给模型，并不等于引用 ID 就自动安全。

### 9.4 工具参数与返回值

所有参数有 JSON Schema、必填定义、长度/数量上限、枚举及允许字段白名单；另外执行 Jakarta Validation 和领域校验。不能假设 `@Tool` 自动完成全部业务校验。

结果统一包含：`status`、`operationId`、`createdIds/updatedIds`、`affectedVersions`、`taskIds`、`errorCode`、`userVisibleSummary`。不返回数据库实体、堆栈、密钥、可复用的签名下载 URL。

长任务工具返回普通可序列化的“已受理/待审批”结果，不返回 Future、Mono、Flux。Spring AI 文档说明这些异步/响应式类型不属于方法工具支持的参数或返回类型。[S04]

### 9.5 不可越过的检查链

身份 → 项目归属 → 工具允许列表 → 参数 Schema → 领域规则 → 对象版本 → 计划审批 → 用量额度 → 幂等记录 → 执行 → 审计。

Prompt、Advisor、模型自检都不是安全边界，最终阻断必须位于应用服务与执行器。

---

## 10. 结构化输出与计划验证

优先使用模型支持的原生结构化输出或工具参数 Schema；Spring AI 的对象映射只承担协议与结构转换，不负责证明镜头合理或用户授权充分。[S24][S23]

解析失败可进行最多两次带具体错误的修复调用，计入本 Run 回合与 Token 限额。仍然失败则结束当前步骤并展示可理解的错误，不把半截 JSON 写成业务对象。

校验层级：

1. JSON 语法和 Schema：字段、类型、长度、枚举。
2. 领域：镜头数量、总时长、存在的引用、同项目、输入媒体类型。
3. 能力：Provider 是否支持参考图、画幅、时长、分辨率。
4. 执行：DAG 无环、输出端口存在、未引用尚未计划产生的文件。
5. 安全：没有新增越权对象、外部 URL、脚本或未经允许的模型。
6. 授权与用量：与审批快照一致，额度仍充足。

规划时可以引用已有 ArtifactVersion，或引用同一计划中上游步骤的“输出槽位”。上游完成后由调度器把槽位解析为具体版本 ID，保存到下游任务输入；这个解析过程不能由 LLM 临时换成另一张图片。

---

## 11. 执行计划、审批与人工接管

### 11.1 计划内容

计划必须包含：planId、revision、runId、目标、受影响对象、步骤及 stable stepKey、类型化输入、输出槽位、依赖、Provider 配置版本、工作流版本、参数、预计用量、输入快照 hash、计划 hash。

计划批准后不可原地改写。调整产生新 revision，原批准范围不自动继承。

### 11.2 默认两阶段审批

阶段 A：脚本与镜头说明生成完成后，批准关键帧图片计划。

阶段 B：用户选定关键帧后，批准视频计划。视频任务必须固定到这些图片版本。

P0 不默认开启“无限自动重生成直到满意”。用户可以拒绝、修改说明、换参考图，然后重新批准。

本地执行也有 GPU 时间和资源成本，默认遵守相同的执行确认；不把自托管等同于无限资源。

### 11.3 批准事务

在同一短事务中核验 planHash、输入版本与额度，写入 Approval、用量预留、任务和事件。任一失败全部回滚。

服务端还要在每次真实提交前复核：授权未被撤销、任务未取消、Provider 配置版本未变化、输入未被禁止使用。不能只在点击批准时检查一次。

### 11.4 人工接管规则

P0 提供“停止当前 Run”“修改内容”“基于当前版本发起新 Run”，不承诺任意指令点即时暂停与继续。

用户编辑当前计划依赖的对象：允许编辑并产生新版本；旧计划未提交的受影响任务变为 BLOCKED，提示重新确认。已经提交的任务继续核对，但结果不得覆盖用户的新版本。

用户只改布局：不使内容生成计划失效。

用户只改第二个镜头：不能改共享场景的原版本而影响第一、第三个镜头；应创建场景的新版本并仅给第二个镜头更新引用。

---

## 12. 持久化任务与恢复机制

### 12.1 为什么首版用数据库任务表

把创建任务与业务变更放在同一数据库事务内，减少跨基础设施协调。调度使用 PostgreSQL 的行锁和 SKIP LOCKED；官方说明这适合多个消费者访问队列表的场景，但不适合作为一般查询的一致性语义。[S08]

这是本产品有限规模下的取舍，不是宣称数据库队列能代替所有消息系统。

### 12.2 任务类型与状态

类型：`AGENT_TURN`、`IMAGE_GENERATION`、`VIDEO_GENERATION`、`MEDIA_EXPORT`、`ASSET_INGEST`。

状态：`PENDING`（依赖未满足）、`READY`、`RUNNING`、`SUBMITTING`、`WAITING_PROVIDER`、`UNKNOWN`、`BLOCKED`、`SUCCEEDED`、`FAILED`、`CANCELED`。

lease 是独立的认领属性，不是把等待 Provider 的整个生命周期都锁在事务或线程里。

### 12.3 认领协议

扫描到期且可处理的任务 → 在短事务中 FOR UPDATE SKIP LOCKED → 设置 lease_owner、lease_until，并递增 lease_epoch → 提交 → 在事务外执行 → 用 taskId + lease_epoch + 预期状态条件提交结果。

所有进度写入、心跳和最终落库都带 lease_epoch。旧 Worker 失去租约后返回的结果不能覆盖新 Worker 的状态。

默认租约 30 分钟（`agenvas.task.lease-duration`），心跳间隔取租约的 1/3。心跳执行器与耗时任务执行器分开。外部等待释放执行槽位，按 next_action_at 再次认领查询。租约必须覆盖一次完整的同步 Provider 调用：GPT Image 单次生成约 30–40 秒，租约短于调用耗时会让已经生成的图片在写回时被恢复扫描判成 UNKNOWN 并丢弃；同步媒体提交因此没有心跳，靠租约长度而不是续租渡过调用期。

### 12.4 重启恢复分类

| 崩溃点 | 恢复行为 |
|---|---|
| 任务已创建，尚未认领 | 重新认领 |
| 确定尚未对外提交 | 按幂等命令恢复 |
| 对外提交前已记录 SUBMITTING，但无法证明请求未发出 | 核对；无法核对转 UNKNOWN，不能盲目提交 |
| Provider 已返回 requestId，已持久化 | 查询原 requestId，不新建生成 |
| Provider 完成，文件归档尚未完成 | 重试下载/归档，不重新生成 |
| 文件已落盘，数据库提交前崩溃 | 通过任务标识和哈希核对归档，再完成数据库事务 |
| 业务事务提交后，SSE 尚未发送 | 从 project_event 补发 |
| 取消后外部结果晚到 | 记录与归档到历史，不自动选择，不唤醒已取消下游 |

### 12.5 幂等分层

HTTP 层：同一用户、同一作用域的 Idempotency-Key + requestHash；重放返回原资源。相同 key 不同请求返回 409。

工具层：`runId + stepIndex + toolCallId`，执行前查账，执行结果与业务变更原子落库。

业务层：`planId + stepKey + attemptNo`，防止模型换 tool_call_id 再次提交同一个已批准步骤。

Provider 层：只有对方明确支持且实测验证幂等时才复用其幂等键。不能因为接口是 POST 或带自定义 requestId 就假设具备去重保证。

幂等键不是“参数哈希永远去重”：用户明确要求再次生成同样参数时，需要新尝试编号与授权；这是合法新任务。

### 12.6 重试分类

状态查询与文件下载：可做有上限指数退避，默认最多 5 次，带抖动；超过后继续调度或转 BLOCKED，取决于任务总期限。

提交前确定的瞬时错误：允许在批准范围内重试。

提交后读超时、连接中断、语义不明确的 5xx：有外部 ID 则核对；没有可靠幂等与核对手段则 UNKNOWN。

鉴权失败、参数非法、模型不支持、用户取消：不自动重试。

内容不满意：不是技术失败；创建新版本、新尝试，并重新批准用量。

### 12.7 UNKNOWN 的人工处理

保留用量预留与提交记录。不得默认退款或把实际费用写成零。

能够查询到原任务则恢复追踪。没有查询手段时，用户可明确知悉潜在重复成本后另建一次尝试；原尝试保留 UNKNOWN，不被伪装成失败。

新尝试命令必须携带原 Task 的 expectedVersion、明确的重复成本确认和 Idempotency-Key。服务端在项目与 Run 边界内复核已批准计划、固定输入版本、Provider/工作流配置及剩余额度；同一事务建立新 Task、独立用量预留、原任务到新任务的审计关联，并仅把尚未执行的下游依赖改指向新 Task。原 UNKNOWN、Provider attempt 及其用量预留均不覆盖或释放；同键重放只能返回原新任务。该流程已用 Mock 模式与 PostgreSQL 验证，真实 ComfyUI 及浏览器端到端仍待联调。

当前 ComfyUI 候选核对仅适用于目标 Task 唯一、未被替代的 UNKNOWN attempt：提交前账本明确保存候选 `prompt_id`、提交时精确 endpoint 的 SHA-256 指纹和任务固定配置版本。核对从历史登记中按原版本及指纹查找原实例，并从其 `/history/{id}` 或 `/queue` 取得同一 `prompt_id`、同一 `client_id`；任务工作流版本须属于已知固定模板族，不能用当前模型文件名变化误判原请求。只有通过这些检查才以预期 Task 版本原子恢复原任务的轮询及 attempt 状态；旧 attempt、取消或已替代任务、原地址缺失或身份不匹配、未知模板族及查询失败均不得恢复。新提交仍必须匹配当前完整工作流版本。查不到只表示“暂无证据”，UNKNOWN 与额度预留不变，不自动发起第二次 `/prompt`。查询与数据库更新分开执行，最终写入仍用版本/状态 CAS。

### 12.8 取消与超时

取消先持久化 cancel_requested；检查点与每次外部提交前都读取它。未提交任务取消，已提交任务仅在 Provider 支持且当前任务归属可验证时请求取消。

任务超过等待期限不等于外部失败，应依据是否确定提交过分别进入 FAILED 或 UNKNOWN/BLOCKED。

本系统不承诺取消能停止供应商计费。

---

## 13. 模型与媒体 Provider 适配

### 13.1 三个接口边界

`ChatGateway`：通过 Spring AI 获取模型消息、工具调用与用量。记录模型配置版本；不得把 Provider 凭证放入 Prompt。

`GenerationGateway`：验证能力、提交、查询、在可支持时取消、归一化结果。

`StorageGateway`：写入、读取、探测、删除受控文件；不包含模型业务。

接口按能力拆开，不假设所有模型都有同一套参数。图像和视频生成可以共享任务模型，但不能强行共用一个供应商 HTTP Schema。

### 13.2 能力声明

每个实际适配器声明：支持任务类型、参考图数量、支持的画幅/时长/分辨率、是否同步返回、能否查询、能否按 request key 核对、是否支持幂等、是否支持逐任务取消、是否提供成本、输出是否会过期。

没有通过契约测试的能力默认 false。仅通过 `/models` 或一次文本对话不能证明支持 Tool Calling、视觉输入或结构化输出。

### 13.3 LLM 首发基线

实现一个 Spring AI 聊天适配器，配置 endpoint、modelId、credentialRef 和能力。兼容接口必须通过“工具请求 → 服务端执行 → tool result → 下一轮模型响应”的完整测试，不依赖产品名称判断兼容。

允许服务端配置同类型多个端点，但 P0 不做运行中自动跨供应商切换。特别是已产生工具副作用的回合，不能静默换模型重新执行。

视觉输入为可选能力；没有视觉支持时，Agent 必须承认只能读取文本元数据，不伪装成看过图片。

### 13.4 ComfyUI 首发适配器

使用两份管理员安装的固定工作流模板：`image-v1` 与 `image-to-video-v1`。映射允许的输入字段，如 prompt、seed、referenceImage、width/height、duration 参数；模板本身和允许的节点类型版本化。

image-v1 必须验证参考图确实映射到图像条件输入；仅把参考图描述写进 Prompt 不算支持参考图生成。视频模板的帧数/帧率与 `durationSeconds` 映射由适配器完成，按能力取值，不由模型猜测参数单位。

普通用户与 Agent 不能上传任意可执行工作流、安装 Custom Node 或修改服务器文件路径。

官方自托管 API 提供 `/prompt` 提交、`/history/{prompt_id}` 查询、图片上传及媒体读取等路由；本适配器以提交与查询为真相，WebSocket 进度最多是优化。[S15]

保存返回的 prompt_id 后持续查询原任务。历史暂时为空不等于失败；结合队列信息与超时策略核对。

ComfyUI 新提交以数据库已提交的 `provider_attempt.request_key` 作为请求中的 `prompt_id`，并要求回执返回同一 UUID；这样响应丢失时仍保有一个可精确查询的候选 ID。该字段不是幂等键：查询不到历史/队列不能证明请求从未受理，不能据此自动重新提交。旧 attempt 没有这个保证，必须按实际提交时的协议版本处理。

共享实例的 `/interrupt` 可能停止“当前运行任务”，因此 P0 默认禁用取消外部运行中的 ComfyUI 任务；只取消本系统后续编排。不能为了一个项目取消他人的任务。[S15]

工作流输入上传文件名由服务端生成。输出文件从固定服务地址按经校验的标识获取，不接受任意本机路径。

### 13.5 首发模板交付门禁

实际发布必须随代码提供模板 JSON、模板 hash、ComfyUI 兼容版本、所需模型名称与获取说明、Custom Node 精确版本/commit、模型与节点许可证清单，以及真实任务测试记录。

本文不虚构已验证的工作流 JSON，也不宣称某块 GPU 一定能跑。开发阶段必须选择能在测试环境实际运行的模板并完成冻结。

只验证文生图而未验证图生视频时，不得在发布说明中写“视频生成已支持”。

### 13.6 Mock 模式

不需要 API Key 或 GPU，可创建项目、操纵画布、演示审批、模拟等待/失败/未知和导出。

所有 Mock 输出必须明显标注“演示素材”，不能伪装为真实模型输出。测试支持可重复的成功、失败、超时、重复返回、晚到结果和断网场景。

Mock 与 Real 使用相同的应用服务、任务状态机和事件协议，不能另写一条绕过可靠性机制的演示链路。

### 13.7 固定云渠道的实现边界

媒体配置从单一环境变量模式迁至管理员界面的连接与能力目录，图片/视频计划逐步骤固定所选能力版本，由同一任务内核调用项目维护的固定适配器。首批云渠道为 GPT Image 2 图片生成/参考图编辑和火山方舟 Seedance 首帧图生视频；当前实现只用本地假 HTTP 服务验证固定协议、PostgreSQL 任务链路与结果归档。Seedance 的临时视频地址限制在已审核的方舟 HTTPS 媒体域，拒绝重定向，过期时仅重查原任务 ID。其协议、时长与 UNKNOWN 恢复边界以[固定渠道规格](superpowers/specs/2026-09-25-fixed-media-provider-adapters-design.md)为准。两种云渠道均无真实调用证据，界面仍标记“未实测”，不能宣称已完成真实生成。普通用户与 Agent 仍无执行动态代码或任意外部 HTTP 的权限。

OpenAI 图片连接可由管理员配置自定义 HTTPS API Base URL，留空使用官方 `/v1`；地址属于连接版本，已批准任务固定历史版本。服务端拒绝私网 DNS 目标与重定向。自定义公开网关的真实生成尚未运行。

后续固定渠道增加 Google Nano Banana 2 图片生成与单张参考图编辑，使用官方 Gemini `generateContent` 和固定模型 `gemini-3.1-flash-image`；仍复用上述审批、版本、任务与 UNKNOWN 边界。Google 真实调用状态单独记录，详见 [ADR 0004](adr/0004-google-nano-banana-2-fixed-adapter.md)。

### 13.8 整数秒业务时长

新镜头内容、执行计划、Task、用量和顺序导出的用户起止点统一使用整数秒；API 字段分别使用 `durationSeconds`、`startSeconds`、`endSeconds`、`videoSeconds`，不得再接受业务小数秒。Mock 视频支持 1–30 秒，固定 ComfyUI 视频模板支持 1–5 秒整数，Seedance 当前能力支持 4–15 秒整数；不支持时提示修改镜头时长。导出总时长上限仍为 60 秒。素材探测得到的实际文件时长继续以毫秒保存，并由 FFmpeg 使用精确值校验边界；不把 5.54 秒的真实文件误记成 5 秒。历史版本、已受理任务和用量不原地改写；新内容与迁移规则见[基础规格](superpowers/specs/2026-09-25-media-capability-foundation-design.md)及[ADR 0003](adr/0003-integer-business-video-seconds.md)。

升级到 V36 时，仅当前镜头时长可整除 1000 毫秒的旧版本会追加一个 `durationSeconds` 新版本；例如旧 1250 毫秒镜头仍显示 1.25 秒，用户必须明确改成 1–30 的整数秒才能提出新计划。旧关键帧选择仍固定旧镜头版本，需要重新选择。升级时旧待审批媒体计划与导出提案标为 `STALE`，应按新单位重建；已受理的 v1 视频及导出 Task 保持原毫秒输入并仅按原请求恢复，不因升级重新提交。

---

## 14. 事件、SSE 与前端状态同步

### 14.1 事件模型

必需事件：artifact.created、artifact.version.created、artifact.current_version.changed、canvas.items.changed、agent.run.changed、plan.proposed、approval.changed、task.changed、asset.ready、usage.changed。

每个事件包含 eventId、projectId、seq、type、schemaVersion、aggregateId、aggregateVersion、occurredAt、payload。正文只包含展示必需的数据；敏感配置与完整 Prompt 不进入通用事件流。

### 14.2 事务一致性

领域变更、任务/工具账本更新和 project_event 写入必须在同一数据库事务中完成。内存 ApplicationEvent 可以作为唤醒优化，但不能成为唯一可靠通知。

project_event 在 P0 同时承担事务性待发送记录与短期事件日志，不因某个客户端收到就删除。SSE 发送失败不回滚已提交业务。

### 14.3 项目序号与提交顺序

不能直接用全局 BIGSERIAL 的生成顺序当成可靠的提交水位：并发事务可能先拿到较小 ID，却晚于较大 ID 提交。

本项目在短事务中锁定 project 的事件计数行，递增 project.event_seq 并插入对应事件，持锁到提交。所有需要产生项目事件的变更遵循同一顺序；同一项目已提交事件的 seq 因而有可安全补发的顺序。

同一项目的需要写事件的命令，先取得项目计数行锁，再按稳定顺序取得其他业务行锁；涉及多个业务行时，锁顺序固定，事务尽量短。通过并发测试验证无遗漏，而不是仅依赖设计推断。

### 14.4 快照 + 增量协议

打开项目时获取快照，其中包含画布、必要实体、活动 Run/Task、snapshotSeq。快照与水位在同一个 REPEATABLE READ 只读事务的一致性快照中取得；仅使用 READ COMMITTED 的多个查询不足以实现这一点。

随后订阅 `/api/v1/projects/{id}/events?after={snapshotSeq}`，服务端先补发较新事件，再持续发送。不能“先查水位后随意拼快照”，否则可能遗漏事件。

SSE 每条事件使用项目 seq 作为 id。自动重连使用 Last-Event-ID；手动重新建立连接使用显式 after。SSE 标准定义了事件 id 与重连行为，但历史持久化和补发由本应用实现。[S18]

### 14.5 重复、乱序与过期

客户端按 seq 去重，按 aggregateVersion 防止旧对象覆盖新对象。若检测到缺口、Schema 无法识别或水位已过保留期，重新请求快照，不在残缺状态下继续静默执行。

默认事件保留 30 天。水位过期返回明确错误并要求重新拉快照；完整业务历史仍在业务表中。

### 14.6 连接与性能

一个打开的项目使用一个 SSE 连接。服务端按项目复用事件读取，不为每个卡片创建连接，也不为每个客户端独立高频扫全表。

默认心跳 15 秒；反向代理禁用 SSE 缓冲，设置合理读取超时；客户端慢消费达到缓冲上限时断开并要求补发，不能无限积压内存。

P0 不要求逐 Token 显示。优先保证步骤事件和持久化的最终模型消息，减少断线重放、审计与流式 JSON 处理复杂度。

### 14.7 前端状态唯一性

TanStack Query 缓存保存服务器实体；Zustand 保存视口、选择、交互草稿与待提交操作。React Flow Nodes/Edges 从业务实体与布局草稿投影生成。

不能在 Query、Zustand、React Flow 三处各保存一份独立可变的完整 Artifact 数据。

---

## 15. API 合约与错误规范

### 15.1 通用规则

前缀 `/api/v1`；JSON 使用 camelCase；枚举使用稳定字符串；ID 是字符串；时间 ISO 8601；金额用十进制定点值，在 JSON 中传字符串。

成功使用正常 HTTP 状态与资源 DTO；错误使用 `application/problem+json`，附稳定 code、traceId、可选 fieldErrors 和 retryable。不采用“任何失败都 HTTP 200”的接口风格。

权限错误不得泄露其他用户项目存在与否。前端显示中文说明，程序判断只依赖稳定 code。

### 15.2 接口目录

| 方法与路径 | 语义 |
|---|---|
| GET `/auth/setup-status` | 是否需要初始化，不泄露系统配置 |
| POST `/auth/setup` | 一次性管理员初始化；受 bootstrap secret 保护 |
| POST `/auth/login` | 创建会话 |
| POST `/auth/logout` | 失效会话 |
| GET `/auth/me` | 当前用户 |
| POST `/auth/change-password` | 修改密码并使旧会话失效 |
| GET `/projects` | 游标分页项目列表 |
| POST `/projects` | 创建项目 |
| GET `/projects/{id}` | 项目概要 |
| PATCH `/projects/{id}` | 改名/设置，带 expectedVersion |
| POST `/projects/{id}/archive` | 归档项目 |
| GET `/projects/{id}/snapshot` | 一致性快照 + snapshotSeq |
| GET `/projects/{id}/events` | SSE 历史补发与增量 |
| POST `/projects/{id}/artifacts` | 手工创建合法产物 |
| POST `/projects/{id}/artifacts/{artifactId}/revisions` | 新内容版本 |
| GET `/projects/{id}/artifacts/{artifactId}/versions` | 历史版本 |
| POST `/projects/{id}/artifacts/{artifactId}/select-version` | 选择版本，带预期版本 |
| POST `/projects/{id}/canvas/commands` | 原子批量布局/展示命令，带每个对象预期版本 |
| POST `/projects/{id}/agents` | 添加内置 Agent 实例 |
| PATCH `/projects/{id}/agents/{agentId}` | 修改指令/绑定/名称 |
| POST `/projects/{id}/runs` | 创建 Run，202 + runId |
| GET `/projects/{id}/runs?agentId=...` | 按 Agent 游标分页的运行摘要；不含模型私有消息 |
| GET `/projects/{id}/runs/{runId}` | Run、计划与任务摘要 |
| POST `/projects/{id}/runs/{runId}/cancel` | 请求停止，幂等 |
| POST `/projects/{id}/plans/{planId}/approve` 或 `/reject` | 对指定生成计划 hash 批准或拒绝 |
| GET `/projects/{id}/tasks/{taskId}` | 任务状态 |
| GET `/projects/{id}/tasks/{taskId}/attempts` | 原外部提交账本的只读关联键与状态；不代表安全重试 |
| POST `/projects/{id}/tasks/{taskId}/reconcile` | 仅核对唯一且未替代的 ComfyUI UNKNOWN attempt 的原 prompt；找到后恢复原 ID 轮询，查不到保持 UNKNOWN，不重复提交 |
| POST `/projects/{id}/tasks/{taskId}/new-attempt` | 明确风险/额度后新建尝试 |
| POST `/projects/{id}/assets` | multipart 上传，进行检查与归档 |
| GET/HEAD `/projects/{id}/assets/{assetId}/content` | 受保护读取，支持 Range |
| POST `/projects/{id}/exports` | 创建导出任务，202 |
| GET `/projects/{id}/export-proposals` | 列出 Agent 保存的待确认及已决定导出提案 |
| GET `/projects/{id}/export-proposals/{proposalId}` | 读取精确提案内容与哈希 |
| POST `/projects/{id}/export-proposals/{proposalId}/approve` 或 `/reject` | 用户确认哈希后创建唯一导出任务，或拒绝且不执行 |
| GET `/projects/{id}/export-manifest` | 导出脱敏项目描述 |
| GET/POST/PATCH `/provider-configs` | 管理 Provider；列表与返回永不包含明文密钥 |
| POST `/provider-configs/{id}/test` | 明确测试范围与潜在调用成本 |

上表路径省略了共同 `/api/v1` 前缀。所有嵌套资源验证归属，不能只验证路径上的 projectId。

### 15.3 创建 Run 请求示意

```json
{
  "agentInstanceId": "95ce370f-2ac7-4a46-b449-8eac7cecd5a4",
  "instruction": "制作三个镜头的咖啡广告，先给我分镜和图片计划",
  "selectedArtifactIds": ["c426d890-ce09-43a7-9726-53dcb154c75c"],
  "expectedAgentVersion": 2,
  "requestedLimits": {"maxImages": 3, "maxVideos": 3}
}
```

客户端只能申请比系统上限更小或经用户配置允许的额度，不能通过请求提升权限。userId、projectId 授权、工具允许列表由服务端确定。

### 15.4 错误示意

```json
{
  "type": "urn:agent-canvas:problem:version-conflict",
  "title": "内容已更新",
  "status": 409,
  "code": "ARTIFACT_VERSION_CONFLICT",
  "detail": "目标镜头已被修改，请读取最新版本后重新提交。",
  "traceId": "b67c2b8df4e7472ca6ea0e83ce84f4938",
  "retryable": false
}
```

主要错误码：VALIDATION_ERROR、UNAUTHENTICATED、RESOURCE_NOT_FOUND、VERSION_CONFLICT、ACTIVE_RUN_EXISTS、APPROVAL_REQUIRED、APPROVAL_STALE、BUDGET_EXCEEDED、PROVIDER_AUTH_FAILED、PROVIDER_UNSUPPORTED_CAPABILITY、PROVIDER_SUBMISSION_UNKNOWN、ASSET_INVALID、TASK_CANCELED、EVENT_CURSOR_EXPIRED。

### 15.5 OpenAPI 的单一来源

`contracts/openapi.yaml` 是对外合约的权威文件。前端类型与请求封装从它生成；后端实现必须通过契约测试与导出差异检查。

springdoc 作为后端实现说明与比对工具，不允许它与手写合约长期各说各话。接口改动需在同一个 PR 更新合约、实现、生成代码和测试。

---

## 16. 媒体资产、上传与顺序导出

### 16.1 文件归档

默认本地存储路径由 StorageGateway 生成，包含项目分区与随机/内容标识。API 不接收任意磁盘路径。

写入流程：临时文件 → 大小/类型/媒体有效性检查 → hash → 原子移动到稳定位置 → 数据库登记 → 发布 asset.ready。故障恢复可凭稳定对象键和 hash 找到已经写入的文件。

外部 Provider 返回的临时链接必须尽快转存。Artifact 不能长期仅引用即将过期的 URL。

### 16.2 初始限制

参考图片：PNG/JPEG/WebP，单文件最大 20 MiB，最大像素数量 40 MP；P0 不接收 SVG、HTML、压缩包和任意文件。

生成视频归档最大 500 MiB，超过则显式报错并保留外部任务状态，不能重新生成。

所有媒体检查采用实际 MIME/解码结果，不只看文件后缀。探测子进程有时间、内存和输出大小限制。保留原文件与显示缩略图的区分；预览图可剥离位置等非必要元数据。

### 16.3 下载与播放

原文件默认私有。应用先鉴权再提供内容；支持 GET、HEAD、Range 与正确 Content-Type。大视频流式传输，不整段读入 JVM 内存。

P1 的 S3 实现可按授权生成短时链接，但数据库只保存对象键，不保存长期外链或签名 URL。对象存储直传必须在服务端确认实际大小与类型后才标记 READY。

### 16.4 顺序导出

输入为有序的视频版本列表及每段选用区间；最大六段、总时长上限 60 秒。P0 不提供自由时间线、复杂转场、配音、音乐或字幕编辑。

标准输出为无声 MP4，目标 720p、24fps，按项目画幅统一。默认等比例适配并补边，不自动裁掉人物；任何裁剪策略要在导出设置中明确。

先规范化各段的尺寸、帧率、像素格式和时间戳，再拼接。不能假定任意 Provider 的视频直接 concat 就一定可播放。[S20]

导出任务的输入是版本快照，导出过程中用户改镜头顺序不改变已开始的任务。新顺序需要新导出任务。

Agent 的 `propose_export` 只保存有序镜头/视频版本和区间的待确认提案；服务器固定 Asset 摘要与项目画幅，并在审批时复核项目及内容版本。提案本身不启动 FFmpeg。用户可通过独立鉴权 API 对所展示的提案哈希批准或拒绝；相同批准重放只关联原导出 Task。手工导出仍可直接由用户发起。

### 16.5 FFmpeg 安全与许可证

通过 ProcessBuilder 参数数组执行固定路径的二进制；不使用 shell 拼接；输入仅为已归档的本地路径；工作目录独立；禁止模型传自定义 filter、协议或编码命令。

进程数默认 1，线程数与超时受配置限制；取消本地任务应终止进程并清理临时文件。初始单机形态不等同于强沙箱，不能据此开放不受信任插件或公共多租户执行。

FFmpeg 的许可证取决于启用的组件；包含某些 GPL 组件会改变该 FFmpeg 构建的许可要求。发布镜像前记录实际构建参数、组件及分发义务，不能因为主项目选择 Apache-2.0 就把 FFmpeg 也标成 Apache-2.0。[S19]

---

## 17. 安全、隐私与密钥

### 17.1 鉴权与初始化

P0 只支持单管理员。首次初始化使用部署时注入的 bootstrap secret，数据库唯一约束保证只能初始化一次；不能让公网第一个访问者直接成为管理员。

使用 Spring Security 的受支持密码编码器和会话管理；密码不明文存储。Cookie 生产配置为 HttpOnly、Secure、SameSite=Lax，并配套 CSRF 防护与来源校验。登录和改密接口限流，认证失败不泄露账号状态。

Spring Session JDBC 使服务器重启不会仅因内存会话丢失而要求所有用户重新登录。数据库会话表统一纳入迁移与清理策略。

默认部署端口只绑定本机；公网部署必须先配置 HTTPS 与安全反向代理。无认证开发模式不能出现在发布默认配置中。

### 17.2 API Key

Key 从前端经 HTTPS 单次提交后，只在服务器加密保存。使用带认证加密的方案、随机 nonce 与独立 keyVersion；主加密密钥来自部署 Secret，不与数据库明文保存在同一位置。

读取配置只返回“已配置”及掩码。Key 不进入 localStorage、URL、日志、SSE、Prompt、错误返回、项目导出、示例文件或 Git。

配置变更递增版本，既有审批不得默默切换到另一个端点。Provider 配置旧版本在仍有活动/未知任务引用时必须保留，任务核对使用原端点与凭证版本；凭证被真正撤销后无法继续查询时进入明确的认证阻断，不能换新配置重新生成。密钥轮换与备份恢复必须有操作说明。

### 17.3 Prompt Injection

用户上传的文本、图片解析内容、工具结果和第三方输出都视为不可信数据。模型从素材中读到“忽略规则、删除所有文件”不能提升权限。[S16]

控制措施：输入来源标记、数据与指令分层、工具允许列表、作用域限制、服务端参数校验、付费/破坏性操作审批、最小返回数据、禁止任意工具发现与代码执行。

不能宣传通过一句系统提示词就能彻底防住注入，也不把模型审核自己当成唯一防线。

### 17.4 SSRF 与出站网络

Provider endpoint 只能由管理员配置，模型不能传入任意 endpoint。连接云接口默认只允许 HTTPS 和明确的主机/端口；重定向、DNS 解析结果和下载目标要逐次检查。[S17]

本地 ComfyUI 是明确允许的例外：管理员配置精确的服务地址/端口和受信任网段；媒体结果下载仍只能回到该固定服务的受控接口。不能因此对任何用户 URL 放开整个内网。

P0 不提供任意 URL 导入。访问云元数据地址、环回/内网绕过、DNS rebinding、跨主机重定向均属于安全测试。

### 17.5 内容与日志隐私

开始 Run 前，界面展示本轮 LLM Provider、将发送的文本/参考图范围和模型调用限额；点击运行授权这次规划调用，不等于批准后续生图/视频。默认只发送必要文本；实际发送图片需要用户显式选择该输入范围。媒体计划审批继续单独展示所用 Provider 与素材范围。默认无遥测；启用诊断导出必须明确可见。

运行记录展示动作，不展示私有推理。生产日志默认不保存完整 Prompt、图像字节和模型原始长回复；需要调试时按项目显式开启并设置短保留期。

会话内容与用户产物按项目保留；支持归档和管理员清理。对外分享链接与公开画廊不属于 P0。

### 17.6 资源授权测试

即使产品只开放一个管理员，也用两个独立用户/项目测试夹具验证不能交叉读写 Artifact、Task、Asset、Run 和 SSE。不能把“目前只有一个用户”当成跳过授权设计的理由。

---

## 18. 用量、成本与执行限额

P0 没有钱包、充值、支付回调、套餐和积分商城，但必须有用量日志、额度校验与付费风险提示。

记录：LLM 输入/输出 Token（有供应商返回时）、模型请求次数、图片数量、视频数量/秒数、导出次数、预计与实际成本、成本来源与配置版本。

成本字段必须区分 KNOWN、ESTIMATED、UNKNOWN。供应商没返回成本不能记成 0；本地推理不收 API 费也不意味着没有硬件成本。

### 18.1 预留与结算

批准计划时预留任务额度与估算预算；同一事务创建任务。任务成功后按唯一 operationKey 结算，明确失败且确认未计费时才释放相应预留；UNKNOWN 保留并要求核对。

金额采用 Decimal/BigDecimal 与 numeric，不使用 double。不同币种不直接相加；P0 统一显示配置币种，混合币种展示原值而非编造汇率。

### 18.2 硬边界与估算边界

本系统可以严格限制任务数量、并发、最大输出 Token 等本地受控参数。对于外部价格、最小计费粒度、已开始的任务和后续账单，估算预算不是供应商层面的绝对花费封顶。

因此 UI 必须区分“数量上限”和“预计预算”。实际账单高于估算要记录差异；无法判断价格时要求明确确认任务数量，不显示虚假的确定金额。

### 18.3 运行限制

每项目最多一个活动 AgentRun；全实例默认两个活动规划执行槽位。直接媒体 Task 可与 AgentRun 并行，媒体提交默认每项目最多三个占用名额，并受管理员配置的能力全局上限约束；UNKNOWN 占名额。ComfyUI 继续一个全局外部执行槽位；其他 Provider 只有完成限流测试后才提高并发。本地 FFmpeg 一个进程。

取消、审批失效、额度不足、Provider 被禁用都阻止新提交。对已经发出的请求只能依照 Provider 能力核对或取消。

---

## 19. 前端开发约定

### 19.1 目录组织

```text
frontend/src/
  app/                 # 路由、顶层 Provider、启动配置
  features/
    auth/
    projects/
    canvas/
      components/
      nodes/
      adapters/        # Domain DTO → React Flow
      hooks/
      commands/
    artifacts/
    agents/
    tasks/
    approvals/
    providers/
  shared/
    api/               # 生成类型和统一传输封装
    ui/
    lib/
    i18n/
    types/
  test/
```

按功能组织，避免把所有组件、所有 hooks 都堆进无边界的全局目录。

### 19.2 代码要求

TS strict、noUncheckedIndexedAccess；接口边界返回 unknown 后校验，或使用经过契约测试的生成 DTO。禁止随意 `as any`、`@ts-ignore` 和关闭 ESLint 来“修复”构建。

组件关注展示，业务写入集中到命令/hooks/API 层。长任务以 taskId 跟踪，不能把一次 Promise 的 pending 状态当业务状态。

生成类型目录禁止手工改。公共组件没有业务 Provider Key 和具体模型名。

异步请求处理 loading、empty、error、retry、unauthorized、conflict、canceled、unknown 等状态，不能只写成功分支。

### 19.3 画布性能

自定义 Node、Edge 和回调保持稳定引用；用选择器限制订阅范围，避免任何节点变化导致全画布重渲染。React Flow 官方性能文档也强调组件记忆化和避免不必要订阅。[S10]

只加载缩略图；大图按需预览；视频默认海报帧；尽可能关闭非必要连线动画与复杂阴影。画布缩放很小时显示简化卡片。

输入文字时快捷键不得触发删除节点。右键菜单、对话框与表单可通过键盘操作。用户手动编辑时，Agent 的视口跟随不能抢焦点。

### 19.4 样式与文案

颜色、间距、圆角、层级和状态样式通过统一 Token 管理。错误文案明确“发生了什么、已保存什么、下一步是什么”，不直接展示英文堆栈。

P0 中文优先，所有界面文案集中管理，预留英文；服务端错误码保持英文稳定标识。

---

## 20. 后端开发约定

### 20.1 代码组织

```text
backend/src/main/java/<basepackage>/
  bootstrap/
  identity/{api,application,domain,infrastructure}/
  project/{api,application,domain,infrastructure}/
  canvas/{api,application,domain,infrastructure}/
  artifact/{api,application,domain,infrastructure}/
  agent/{application,domain,infrastructure,tool}/
  plan/{api,application,domain,infrastructure}/
  task/{application,domain,infrastructure}/
  provider/{api,application,domain,infrastructure}/
  asset/{api,application,domain,infrastructure}/
  export/{api,application,domain,infrastructure}/
  event/{api,application,infrastructure}/
  usage/{application,domain,infrastructure}/
  shared/{error,security,id,time}/
```

保持一个 Maven 应用模块起步，包级边界足够。没有实际复用需求时，不强制为每个小类创建接口与工厂。

### 20.2 分层规则

API/Tool 层：认证上下文、请求转换、基础校验、调用应用服务。

Application：用例、事务边界、权限、幂等、业务对象协调、事件写入。

Domain：状态转换、授权范围、版本与计划规则；不依赖 React、Spring AI Provider DTO 或具体 HTTP 响应。

Infrastructure：MyBatis、HTTP、文件系统、Spring AI、FFmpeg 等具体实现。

不得跨模块直接注入对方 Mapper。通用 shared 不得膨胀成业务杂物间。

### 20.3 Java 规范

构造器注入；DTO 可以使用 record；实体和外部 DTO 分开。不把持久化实体直接作为 REST 或 Tool 返回值。

统一错误映射和稳定错误码。禁止 catch Exception 后返回成功或 null；禁止日志吞错；禁止用魔法数字表示状态。

时间通过可注入 Clock 获取，便于超时测试。金额使用 BigDecimal；ID 使用强约束类型或 UUID；配置使用类型化 ConfigurationProperties 并验证。

对 HTTP、文件流和子进程明确释放资源。线程/执行器有上限和关闭策略，不无限 new Thread、不无界 submit。

### 20.4 事务与锁

网络调用前结束事务。业务更新使用 expectedVersion，并核对实际更新行数。状态变化集中在状态机服务或转换函数，不能各处直接 setStatus。

悲观锁只用于短小关键区，例如任务认领、审批预留、项目 Run 槽位和事件序号。锁顺序统一，死锁可安全重试且必须有限次。

支持乐观冲突时返回当前版本提示，不能无条件“以 AI 的更新为准”。

### 20.5 Spring AI 集成

所有 ChatClient 构建与 Provider 配置集中管理；不要在 Controller 中临时 new 模型实例。每 Run 使用独立上下文，不在共享 Bean 里存放可变的 currentProjectId/currentUserId。

确保使用所选 Boot/AI 版本的公开 API。不可复制其他版本教程的内部类，再靠排除依赖掩盖冲突。

Agent Model 调用默认使用受控回合模式，禁用自动工具执行。注册工具按 Agent Profile 白名单，不将所有 Spring Bean 自动暴露给模型。

---

## 21. 数据库、迁移和查询规范

Flyway 文件只增不改；已经发布的迁移不得重写 checksum。表、索引、约束都有用途注释；测试必须覆盖空库创建与上一版升级。

关键外键、非空、唯一性在数据库落实。JSONB 保留 schemaVersion，并有内容迁移策略。禁止依赖 ORM 自动建表作为生产迁移。

查询必须有项目/用户边界；用户传入的排序字段使用白名单，条件一律参数化。列表有分页上限，默认 20、最大 100；大历史采用游标分页。

任务扫描不全表排序，使用状态与 next_action_at 索引。事件按 project_id + seq 查询，不逐事件回表扫描整个项目。

新增索引根据真实查询和 EXPLAIN 决定，不给所有字段“顺手加索引”。运行日志与原始模型回复不能无限增长；按保留策略归档或清理。

数据库不存媒体 base64。需要 hash 的 JSON 使用明确的规范化规则，不能依赖不同语言 Map 序列化顺序。

---

## 22. 测试策略与故障验收

### 22.1 测试层级

| 层级 | 必须验证 |
|---|---|
| 领域单元测试 | 状态机、计划 DAG、版本冲突、审批 hash、限额、作用域 |
| 数据库集成 | 唯一约束、SKIP LOCKED、lease_epoch、事务事件、并发审批 |
| API 契约 | 请求/响应 Schema、错误码、鉴权、幂等、分页 |
| Provider 契约 | 提交/查询/未知/取消能力、返回字段变体、文件归档 |
| 前端组件 | Agent 卡片、审批弹窗、冲突提示、任务状态、版本切换 |
| 端到端 | 黄金路径、局部重做、刷新恢复、SSE 重连 |
| 故障注入 | 提交后断网、崩溃、重复消息、磁盘满、数据库短断 |
| 安全 | 越权、CSRF、密钥泄露、恶意文件、SSRF、Prompt 注入 |
| 真实模型评估 | 工具调用正确率、遵守作用域、计划可执行性；画质单独记录 |

测试不能全部 Mock 掉数据库。并发与事务测试用 Testcontainers 中的 PostgreSQL，不用 H2 结果替代。

### 22.2 发布必须通过的故障场景

1. 同一创建 Run 请求并发重放 20 次，只产生一个 Run。
2. 同一个审批并发提交 20 次，只创建一组任务和一笔预留。
3. 在外部已接收请求但本地未保存 requestId 的时间窗杀进程：进入核对或 UNKNOWN，不自动创建第二笔生成。
4. Worker 租约过期后旧 Worker 回写：被 lease_epoch 阻止。
5. 任务完成事件重复到达：只创建一个结果版本、一次结算。
6. 快照读取与事件提交并发：前端最终无事件遗漏、无旧版本覆盖。
7. SSE 断线超过短缓冲：可补发；超过保留期则重取快照。
8. 用户修改镜头后旧任务完成：结果进入历史，不覆盖当前选中版本。
9. 用户取消后外部结果晚到：不启动下游视频/导出。
10. 外部生成完成但下载失败：只重试归档，不重新生成。
11. 运行中磁盘满：不产生 READY 的坏文件，不宣称任务成功。
12. 项目归档后回调/轮询成功：不让项目重新活跃，也不丢提交审计。
13. 越权 Artifact/Asset/Run/SSE ID：返回拒绝或不存在，无任何内容泄露。
14. 一张参考图包含恶意指令：不能批准任务、读取密钥或扩大项目范围。
15. UI 显示的生成数量与实际创建任务数量一致。

### 22.3 质量指标

关键状态机、审批、幂等模块分支覆盖率目标至少 90%；整体测试覆盖率只是辅助指标，不得用刷覆盖率替代上述场景。

维护至少 30 条固定 Agent 用例，包含正常创作、局部修改、歧义输入、非法引用、超范围要求、恶意素材与 Provider 不支持能力。记录模型标识、Prompt/Skill 版本与失败原因。

可执行计划成功率初始发布目标 90% 以上，按固定样本集统计；涉及权限、审批、重复副作用的安全验收必须全部通过。不把 30 条样本结果宣传为普遍成功率。

---

## 23. 性能、容量与可用性目标

以下目标在开发完成后验证，不是已经完成的压测结论。

| 项目 | 初始目标与测量条件 |
|---|---|
| 非媒体 API | 服务端 p95 ≤ 300 ms；同机数据库；不包含 LLM、上传下载时间 |
| 创建 Run/媒体任务受理 | p95 ≤ 500 ms；返回 ID，不等待生成完成 |
| 业务事件可见 | 事务提交到前端显示 p95 ≤ 1 秒；健康网络 |
| 画布规模 | 300 个卡片、600 条关系；仅加载缩略图，目标交互 ≥ 30 FPS |
| 刷新恢复 | 在 2 秒目标内恢复项目概要和活动任务，媒体按需加载 |
| 任务重启恢复 | 在一个租约周期 + 两个扫描周期内开始重新检查 |
| 内存稳定 | 持续创建/关闭 SSE 与预览，不出现随连接数永久增长的泄漏 |
| 初始机器 | 4 vCPU / 8 GiB 内存 / 50 GiB 持久盘，作为验证起点，不含模型推理资源 |

记录测试的浏览器、分辨率、CPU、网络、卡片类型分布和媒体大小。不要只用 300 个空白 div 证明复杂媒体画布性能。

普通浏览、编辑和历史访问不应因某个模型 Provider 不可用而全部宕机。第三方失败只影响依赖它的新任务。

---

## 24. 可观测性与运维诊断

### 24.1 日志关联

统一结构化日志字段：traceId、requestId、projectId、runId、taskId、toolExecutionId、providerAttemptId、状态变化、耗时、错误分类。

以上高基数字段用于日志与 Trace，不作为 Prometheus 指标 label。禁止记录 Authorization、Cookie、Key、签名 URL 查询参数与原始媒体内容。

### 24.2 指标

队列等待时间、READY/UNKNOWN/BLOCKED 任务数量、活动 Run、Provider 提交/查询失败率、任务完成时间分位数、工具执行错误、SSE 连接与重连、事件发送延迟、文件归档失败、磁盘使用、估算/实际用量差异。

用户可在系统诊断页看到数据库/存储/Provider 配置状态及最近错误摘要。健康检查默认不实际发起付费生成。

### 24.3 健康与就绪

liveness 不因为第三方模型短暂失败而失败；readiness 检查应用能否安全受理请求，数据库不可用时不接收写入。单个 Provider 故障反映在其能力状态，而非杀死整个应用。

Actuator 管理端点仅内部或管理员可见；生产 Swagger 和 `/v3/api-docs` 同时受保护，不能只隐藏 UI。

### 24.4 告警起点

UNKNOWN 任务新出现、数据库连接池饱和、事件明显积压、磁盘达到 80%、连续归档失败、任务租约大量过期、外部额度不足。阈值是运维策略，结合实测调整。

---

## 25. 部署、升级、备份与恢复

### 25.1 仓库与运行方式

本地开发：前端 Vite、后端 JVM、Docker PostgreSQL；默认 Mock Provider。

自托管：Docker Compose 三服务。真实 ComfyUI 与模型服务可在另一台机器，不打包大模型权重到主应用镜像。

Nginx 统一域名处理前端与 `/api`，避免生产跨域鉴权复杂度。SSE 反代禁缓冲。所有镜像锁版本与 digest，不使用 latest。

### 25.2 容器要求

非 root 用户；应用文件系统只读，数据与临时目录单独挂载；配置资源上限、健康检查与日志轮转；不挂 Docker Socket，不使用 privileged。

FFmpeg 子进程仅在指定工作目录运行。P0 的可信自托管边界必须在 README 写清，不能把它当作允许陌生人执行任意工作流的沙箱。

### 25.3 优雅停机

收到停机信号后停止接收新 Run 和认领任务；尽量完成短事务；持久化正在进行的回合/提交状态；关闭 SSE 并让客户端重连；外部任务保留原 requestId 供恢复核对。

不能在关机钩子里把所有 RUNNING 任务无条件改成 READY。尤其 SUBMITTING 状态必须保留不确定性。

### 25.4 升级

升级顺序：运行数据备份 → 暂停新任务 → 等待或安全记录正在提交的任务 → 应用迁移 → 启动新版本 → 运行 smoke test → 恢复受理。

数据库采用向前兼容的增量迁移；破坏性变更延后。回滚应用前检查旧版本是否兼容新 Schema，不能把“回滚镜像”当成必然可回滚整个系统。

### 25.5 备份范围

必须备份数据库、资产文件、工作流/Prompt/Skill 版本、部署配置和加密密钥。密钥与数据库备份分离存放，记录恢复对应关系。

默认建议每日备份；以此配置的初始目标为 RPO ≤ 24 小时、RTO ≤ 2 小时，必须完成恢复演练才能宣称达到。需要更小 RPO 时再建设连续备份。

### 25.6 恢复到旧备份的特殊风险

旧数据库快照可能不知道备份之后已经发生的外部请求。恢复后默认以“恢复模式”启动，不立即重提所有未完成生成。

先核对 Provider 记录、原请求键和资产文件；不能核对的提交转 UNKNOWN 或要求人工确认。否则一次灾备恢复可能变成大量重复计费。

### 25.7 清理

临时文件默认 24 小时清理，但排除活动任务。无引用资产默认进入至少 7 天隔离期，二次检查所有版本、任务和导出引用后才物理删除。

任务与工具幂等记录在可能被重放的生命周期内必须保留；终态审计默认至少 90 天。事件流默认 30 天。实际保留可由管理员调整，并说明删除影响。

---

## 26. 仓库结构、开发工作流与 CI

```text
agent-canvas/
  frontend/
  backend/
  contracts/
    openapi.yaml
    events/
    artifact-schemas/
  configs/
    agents/creator/
    skills/
    workflows/
  deploy/
    compose.yaml
    nginx/
    docker/
  docs/
    MVP-SPEC.md
    DEVELOPMENT-CHECKLIST.md
    dependency-baseline.md
    provider-compatibility.md
    operations/
    adr/
  AGENTS.md
  README.md
  README.en.md
  CONTRIBUTING.md
  SECURITY.md
  LICENSE
  NOTICE
  .env.example
  .github/workflows/
```

### 26.1 PR 规则

一个 PR 解决一个可验收目标。提交包含行为说明、关联任务、接口/迁移影响、测试证据、界面截图（涉及 UI 时）和安全/用量影响。

禁止未经确认的大规模重构；禁止把“顺便优化”混入可靠性关键修复。修改架构决策先更新 ADR。AI 生成的代码也需要同样的测试与审查。

不能在 PR 中写“已通过测试”却没有实际运行；执行环境缺失时记录未运行及原因。

### 26.2 CI 必需门禁

前端：冻结安装 → 类型检查 → lint → 单测 → 构建 → 核心 Playwright E2E。

后端：Maven Wrapper verify → 格式检查 → 单测 → PostgreSQL 集成测试 → 契约测试 → 迁移测试。

跨栈：OpenAPI 破坏性变更检查 → 生成代码无未提交差异 → Mock 黄金路径 → 幂等与故障测试。

安全：密钥扫描、依赖漏洞扫描、容器扫描、许可证清单/SBOM。对可达的高风险漏洞设发布阻断；误报或暂缓必须有负责人、理由和到期日期。

真实模型冒烟是显式手动或受控触发，不在来自 fork 的 PR 中注入真实密钥，也不无限消耗真实 API 费用。

### 26.3 AI 开发约定

使用配套 AGENTS.md。每次开发先读取相关规格、合约、ADR 和现有代码，然后完成一个纵向闭环；不得一开始生成几十个空 Service 假装进度。

不得擅自换技术栈、引入收费平台必需依赖、跳过审批和幂等、在前端硬编码 Key、以 Mock 输出冒充真实结果。

---

## 27. 开发阶段与依赖顺序

不按日历估时，而按能验证的交付门禁推进。

| 阶段 | 交付内容 | 退出条件 |
|---|---|---|
| M0 工程基线 | 锁版本、三服务、迁移、登录、OpenAPI、Mock Provider、CI | 新环境可启动，基础安全与契约 smoke 通过 |
| M1 画布与版本 | 项目、Artifact/Version、CanvasItem、Agent 卡片空壳、自动保存 | 手工创建/编辑/刷新可恢复，版本冲突可见 |
| M2 可靠执行骨架 | Run、Task、租约、幂等、事件、SSE 补发、取消 | 崩溃恢复和重复请求测试通过 |
| M3 Agent 文本闭环 | Spring AI 工具调用、上下文、分镜生成、审批计划 | 一句话创建三个镜头，工具副作用可审计 |
| M4 真实图片 | 固定图像工作流、上传参考、文件归档、历史版本 | 真图生成成功；未知提交、晚到结果、归档失败处理通过 |
| M5 视频与局部修改 | 图生视频、关键帧固定、第二镜头重做、顺序导出 | 真实三镜头闭环、旧版本保护、视频可播放 |
| M6 稳定化与开源发布 | 故障注入、性能、安全、备份演练、安装文档、许可证 | P0 验收全通过，发布证据可追溯 |

M2 可以与 M1 的界面工作部分并行，但 M4 的付费/耗资源调用不能先于 M2 的幂等与审批机制上线。

---

## 28. 发布 Definition of Done

产品：画布中的 Agent 真实可操作；不是只在聊天框里描述做了什么。黄金路径与局部修改均可演示，旧素材保留。

工程：可从干净环境构建；依赖锁定；空库迁移与升级通过；接口和生成类型一致；没有只有成功分支的关键操作。

可靠性：重复请求、审批竞态、提交未知、重启恢复、租约过期、SSE 补发、版本冲突、取消晚到均有自动化证据。

模型接入：至少一个真实 LLM 和一组真实图片/视频工作流通过；模板、模型要求、节点版本和已知限制明确。

安全：鉴权、CSRF、资源隔离、密钥、SSRF、恶意文件与 Prompt 注入边界测试通过；生产管理端点受限。

运维：备份恢复演练通过；旧备份恢复不会盲目重提外部任务；具备诊断和错误归类。

开源：README、许可证、NOTICE、第三方与模型许可证清单、贡献指南、安全报告入口、Mock 模式、可运行部署说明齐备。

未达到其中任一项，应发布为明确标注的实验版本，不称为稳定 MVP。

---

## 29. 开源原则与商业边界

建议主项目采用 Apache-2.0，保留标准许可证和必要 NOTICE；这是一项项目治理建议，不是对所有依赖法律兼容性的结论。[S21]

核心画布、Agent Runtime、内置工具、计划审批、任务恢复、基础 Provider 接口与部署能力完整开源。不通过闭源远程服务解锁“真正 Agent 模式”。

不强制注册作者平台，不依赖作者专用 Key，不做远程停用开源实例的许可证校验。用户可以自行提供模型端点、凭证或本地推理能力。

模型调用费用、硬件成本、第三方工作流与模型权重许可证独立于本项目源码许可。开源软件不能消除这些成本与条款。

后续商业化可围绕托管、团队运维、算力和支持，但不能在本版用大量 SaaS 计费代码阻塞核心创作功能。

---

## 30. 风险清单与处理原则

| 风险 | 后果 | 本版措施 |
|---|---|---|
| 模型误解画布对象 | 修改错误镜头 | 显式 ID/选择/绑定、影响范围预览、版本检查 |
| 模型输出非法结构 | 无法创建计划 | Schema + 领域校验、最多两次修复、无半成品写入 |
| 参考对象共享 | 改一个镜头影响其他镜头 | 不可变版本、仅更新目标引用 |
| 重复生成 | 重复成本与混乱结果 | HTTP/工具/业务幂等、审批、Provider 核对 |
| 提交状态未知 | 盲目重试重复收费 | UNKNOWN、一致审计、人工处理 |
| Worker 双执行 | 重复状态和资源写入 | 持久租约 + fencing epoch + CAS |
| SSE 丢失 | 画布状态落后 | 持久事件、快照水位、重放与重新快照 |
| 供应商不兼容 | 工具或多图调用失败 | 能力探测与契约测试，不凭“兼容”标识猜测 |
| 数据/密钥泄露 | 用户损失 | 服务端保密、日志脱敏、最小上下文、鉴权 |
| 自定义工作流任意执行 | 主机风险 | 首版固定模板、管理员安装、无任意插件 |
| 视频格式混杂 | 无法导出/播放 | 受控规范化与真实浏览器验证 |
| 范围膨胀 | 永远做不完 | 一个 Agent、一条黄金路径、P0/P1 分离 |

---

## 31. 初始配置默认值

| 配置 | 初始值 | 备注 |
|---|---|---|
| 单项目活动 Run | 1 | WAITING_* 和 BLOCKED 仍占用；用户需取消或继续处理 |
| 全实例规划执行槽位 | 2 | 等待外部任务不占线程 |
| ComfyUI 并发 | 1 | 提高前先测试资源与隔离 |
| 本地导出并发 | 1 | 受 CPU/内存限制 |
| Run 模型回合 | 12 | 修复调用也计入 |
| Run 工具执行次数 | 40 | 重放原结果不重复计业务副作用 |
| Run 图片/视频数量上限 | 8 / 6 | 用户可以收紧，模型不能提高 |
| 默认/最大镜头数量 | 3 / 6 | 超出拆分任务 |
| 任务租约/心跳 | 30分钟 / 租约的1/3 | 心跳独立执行器；租约须覆盖一次同步 Provider 调用 |
| 活跃任务扫描 | 1秒 | 数据库扫描需索引与批量上限 |
| Provider 状态查询 | 2–10秒退避 | 由调度器执行，不由 LLM 循环询问 |
| 外部任务默认等待期限 | 60分钟 | 按已验证工作流配置；到期不是自动失败/重提 |
| SSE 心跳 | 15秒 | 代理超时必须更长 |
| SSE 事件保留 | 30天 | 超期游标重新快照 |
| 审批有效期 | 24小时 | 内容/配置变更可更早失效 |
| Run 等待总期限 | 24小时 | 到期 BLOCKED，不自动重做 |
| 上传图片 | 20 MiB / 40 MP | 类型与真实解码检查 |
| 单视频归档上限 | 500 MiB | 可由管理员调整 |
| 导出 | 最多6段、60秒、720p、24fps、无声 | 固定参数白名单 |
| 数据库连接池 | 初始上限10 | 压测后再调整 |
| 生产日志保留 | 初始14天 | 不含完整敏感内容 |
| 终态执行审计 | 至少90天 | 活动/未知任务记录不得按此直接删 |

---

## 32. 关键 ADR 摘要

**ADR-001：Vite SPA + Java API。** 放弃首版 SSR，减少运行时与鉴权边界。代价是营销 SEO 另行建设。

**ADR-002：模块化单体。** 减少部署与分布式一致性复杂度。代价是未来水平拆 Worker 时需要共享存储和限流协调。

**ADR-003：ArtifactVersion 为不可变内容。** 保留可追溯与局部修改能力。代价是需要版本选择与清理策略。

**ADR-004：执行计划独立于画布/素材关系。** 保证计划可校验、可审批、可恢复。代价是多一个明确的计划模型。

**ADR-005：Spring AI 受控回合。** 使用模型/工具基础设施，但业务层管理检查点。代价是需要实现消息协议与恢复测试，不能只写一个 ChatClient.call。

**ADR-006：PostgreSQL 持久化任务与事件。** 先获得可靠闭环，不引入消息中间件。代价是必须认真实现租约、fencing、幂等、事件水位与调度容量边界。

**ADR-007：默认审批而非无限自动生成。** 确保输入和成本可控。代价是比全自动演示多一次或两次确认。

**ADR-008：ComfyUI 固定模板作为首个媒体执行端。** 开放且可替换，不把业务图绑成 ComfyUI workflow。代价是需要独立推理环境和经过验证的模板。

**ADR-009：先顺序导出，不做完整剪辑器。** 提供实际可下载结果，限制范围。代价是复杂成片编辑留到后续。

---

## 33. 外部依据与核查范围

以下链接是本稿使用的官方文档。版本核查日期为 2026-09-22；文档页面可能继续更新。架构选择、表结构、接口、阈值和验收标准是本项目的设计建议，并非这些来源对本项目的背书。

- [S01] Spring AI Getting Started：版本、BOM、Boot 兼容范围。https://docs.spring.io/spring-ai/reference/getting-started.html
- [S02] Spring Boot 4.0 System Requirements：4.0.8、Java 与构建要求。https://docs.spring.io/spring-boot/4.0/system-requirements.html
- [S03] MyBatis-Plus 安装：Boot 4 starter 与依赖使用。https://baomidou.com/getting-started/install/
- [S04] Spring AI Tool Calling：ToolContext、工具定义、方法工具限制。https://docs.spring.io/spring-ai/reference/api/tools.html
- [S05] Spring AI ChatClient：受控工具循环与关闭自动注册。https://docs.spring.io/spring-ai/reference/api/chatclient.html
- [S06] Spring AI ToolCallingAdvisor：自动循环与扩展机制。https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html
- [S07] Spring MVC Asynchronous Requests：异步响应与 SSE。https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
- [S08] PostgreSQL SELECT：行锁与 SKIP LOCKED 的适用语义。https://www.postgresql.org/docs/current/sql-select.html
- [S09] React Flow Pro：开源核心与 Pro 的区分。https://reactflow.dev/pro
- [S10] React Flow Performance：组件与订阅性能。https://reactflow.dev/learn/advanced-use/performance
- [S11] Node.js Releases：LTS 与版本状态。https://nodejs.org/en/about/previous-releases
- [S12] Vite Getting Started：工程与 Node 要求。https://vite.dev/guide/
- [S13] springdoc-openapi：与 Spring Boot 版本匹配。https://springdoc.org/
- [S14] PostgreSQL Versioning Policy：支持版本与维护策略。https://www.postgresql.org/support/versioning/
- [S15] ComfyUI 自托管服务路由：提交、查询、文件与中断。https://docs.comfy.org/development/comfyui-server/comms_routes
- [S16] OWASP LLM Prompt Injection Prevention。https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html
- [S17] OWASP SSRF Prevention。https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- [S18] MDN Using server-sent events。https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events
- [S19] FFmpeg License and Legal Considerations。https://www.ffmpeg.org/legal.html
- [S20] FFmpeg Filters：规范化与拼接能力。https://www.ffmpeg.org/ffmpeg-filters.html
- [S21] Apache License 2.0 原文。https://www.apache.org/licenses/LICENSE-2.0
- [S22] Spring Boot JSON：Jackson 依赖与支持。https://docs.spring.io/spring-boot/reference/features/json.html
- [S23] OpenAI Function Calling：结构化工具协议。https://developers.openai.com/api/docs/guides/function-calling
- [S24] Spring AI Output Converters：结构转换接口。https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html

---

## 34. 最终边界总结

这个 MVP 不是全功能 AI 视频平台，也不是通用 Agent 操作系统。

它必须把一件事做完整：**用户在画布上给 Agent 一个任务，Agent 产生可检查的计划与产物，执行被授权的生成步骤，用户能局部修改，系统在失败和重启时仍然可信。**

画布负责让工作可见；ArtifactVersion 负责让内容可追溯；ExecutionPlan 负责让执行可验证；Spring AI 负责模型与工具协议；应用 Runtime、任务状态机和审批负责把自动化限制在可恢复、可控的边界内。
