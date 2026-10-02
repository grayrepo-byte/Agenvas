# ADR 0029：Agent 对话中的公开进度、流式回答与媒体审批

状态：接受（2026-10-02）。用户在后端媒体审批完成后确认接入前端，要求 Agent 卡片显示思考过程、步骤、工具调用和流式回答，并采用 [BeautifulUI](https://www.beautifului.dev/) 的交互样式。这里的过程显示限定为公开执行状态和可核实动作，模型私有推理不进入界面、事件或响应检查点。

## 界面与授权

沿用画布中的 Agent 卡片与持久会话。执行详情使用可展开步骤、细竖线、状态图标和紧凑工具标签；公开回答独立显示并随真实文本增量增长。历史步骤默认收起，当前工作默认展开。工具摘要只取已经提交的服务端业务账本，不拆开原子工具事务以模拟中间状态。媒体长期执行显示实际 Task 状态，执行行只显示可读任务名称，不展示内部 stepKey。

视觉语言从 BeautifulUI 的现有开源组件适配，继续使用项目公共组件、主题 token 和 Phosphor 图标。输入框位于滚动正文之外。一个 ResizeObserver 只观察正文与 transcript 两个元素：用户原本在底部时，流式文本、步骤和状态引起的尺寸变化继续跟随；向上阅读历史时保持位置，回到底部后恢复跟随。切换会话保留一次定位，不为每个 token 更新整张卡片的 React 状态，也不模拟打字。

媒体提案在对话内显示冻结批次：提示词、能力、参数、精确素材版本、视频输入模式和价格或费用未知。用户显式批准或拒绝整批，使用现有 CSRF、Idempotency-Key 与 expectedVersion。提交中禁止重复点击；冲突刷新审批但保留错误，已批准或终态批次不可再次批准。结果未知保留风险提示，不为 Agent 已批准任务提供不受支持的直连重试入口。生成成功、失败、拒绝、过期或取消后的结果继续沿后端原有续接路径通知 Agent；前端不发起结果查询循环。

## 流的持久化边界

Spring AI 的流式 ChatResponse 先过滤私有思维内容及相应元数据，仅将第一候选的公开回答文本交给业务流。保留完整工具调用身份、参数、必要协议元数据和供应商实际报告的用量；缺失或全部为零的用量保持未知，不推算实际收费。关闭自动工具执行，完整聚合响应持久化后才进入现有工具 Runtime。

公开文本和累计协议数据各有 1 MiB 上限。存储模型配置创建的 SDK client 对原始 SSE 字节另设 4 MiB 上限，在 SDK 解析前拒绝超限内容；此解析前保护不覆盖旧入口或自定义上游，不能据此声称所有模型接入都具有同样的原始响应边界。正常公开增量最多按 150 ms 合批；中断时最后尚未落库、不超过 150 ms 的尾部可能丢弃，已持久化前缀保留并标为 INTERRUPTED。

本次不增加独立表或数据库迁移。AGENT_TURN Task 的 `output.assistantStream` 保存 `{streamEpoch,chunkIndex,text,status}`，状态为 STREAMING、COMPLETED 或 INTERRUPTED。增量有界合批，每次短事务验证任务项目、Run、当前步骤、活动状态、有效租约、worker 和 fencing epoch，再 CAS 更新累计文本并提交项目事件。完整响应检查点同样验证租约，旧模型线程不能晚到保存响应或触发工具。

项目唯一 SSE 连接发送 `agent.turn.stream.started / delta / completed / interrupted`。payload 包含 `runId / taskId / stepIndex / streamEpoch / chunkIndex`，delta 另含 `textDelta`。started 序号为 0，delta 严格递增，completed/interrupted 复用最后序号。流事件按项目 seq 和本流 epoch/cursor 排序，不使用不同回合的 Run 版本抑制增量。正常增量只更新 Query 缓存，不逐分片发 HTTP 查询。

项目快照的 activeTasks 与 snapshotSeq 已使用同一 PostgreSQL 一致性快照，累计文本因此可恢复。分片或项目水位缺口、游标过期触发已有快照恢复；历史会话从持久 Task 重读。更高 epoch 重置旧尝试，不混合两次回答；已提交 Task 的 `assistantText` 替换临时文本。失败和取消保存 INTERRUPTED 前缀，后续增量被拒绝。SSE 断开只影响观察，不取消后台任务。

## 接口与升级

审批 API 沿用 ADR 0028。OpenAPI 新增公开流投影与事件 payload 类型说明，生成 TypeScript；Task output 和 ProjectEvent 的扩展字段维持现有 JSON 合约。没有新增前端服务端、依赖、公开 Webhook、轮询 Agent 或私有推理接口。升级后旧客户端仍可重新加载快照，但应同时部署支持新事件类型的前端，避免未知项目事件引发重复恢复。

## 验收边界

本轮已完成前后端定向验证。后端 14 个测试类共 67 项去重测试通过，其中包含公开流的 3 项真实 PostgreSQL 集成测试和已有 Worker 的 2 项 PostgreSQL 回归。合成 HTTP 验证响应结束前公开文本已到达、自动工具执行关闭、工具身份与参数碎片聚合、实际用量与未知用量处理，以及公开文本、协议和原始 SSE 超限拒绝。数据库测试验证累计前缀与项目事件同事务、快照恢复、旧 epoch/租约及取消拒绝、失败保留中断前缀。

前端 7 个文件共 110 项定向 Vitest 测试、TypeScript 类型检查、完整 lint 和生产构建通过，覆盖单项目 SSE 缓存的重复、缺口和 epoch 恢复、终稿唯一、审批 CSRF/CAS/幂等、UNKNOWN 无直连重试及底部跟随。浏览器使用隔离且明确标注的 Mock 夹具，在 484 × 1211 视口检查 436 px 和 360 px 卡片：无水平溢出，公开步骤、工具标签与审批卡匹配参考的视觉语言。发现的内部 stepKey 展示和流式内容未跟随两个 P2 已修复并复查；最终回复只显示一份，用户上滚时新增内容不强制跳到底部。

OpenAPI 与生成 TypeScript 已同步。上述合成 HTTP、Mock 界面和真实 PostgreSQL 不代表真实供应商或真实模型验收；后续本地部署的构建、健康及静态产物一致性检查已完成，详见开发清单；真实 Provider 浏览器调用和全量测试仍未运行。浏览器记录与视觉比较见根目录 design-qa.md；开发清单保留此前仅后端阶段的历史，后续前端验收单独追加。
