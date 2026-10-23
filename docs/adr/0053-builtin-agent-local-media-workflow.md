# ADR 0053：内置 Agent 音色参考处理与本地任务续接

状态：接受；本次验收见开发清单。

用户要求必须由项目自带 Agent 完成视频提音、裁剪音色参考并生成成片，以排查 Agent 自身的问题。[ADR 0052](0052-audio-trimming-for-voice-references.md) 已有画布与 REST 裁剪，但缺少内置工具；[ADR 0049](0049-intelligent-dubbing-composition.md) 的内置合成只返回排队回执。两者不足以让同一 Run 获取中间音频版本并继续创作。本决定扩展这两项边界，不新增外部编排器或替代内置 Agent。

## 工具与权限

新增 `extract_audio` 和 `trim_audio`，与现有 Controller 复用 `VideoOperationService`、`AudioTrimService`。模型提供当前节点、精确来源版本、观察到的节点 CAS，以及音轨/格式或裁剪范围；服务端从节点推导 Artifact，核对项目所有者、Run 内容范围、类型、锁定和当前选用版本。模型不能提供用户、项目、Run、任务来源或任意文件地址。

提音固定使用本地 `LOCAL_VIDEO_AUDIO_EXTRACTOR`，不跟随可能指向云端的功能配置；裁剪固定使用 `LOCAL_AUDIO_TRIMMER`。两者都是零外部生成费用的持久 Task，不新增媒体审批。现有媒体来源、输出预算、不可变版本、派生节点、幂等与选择保护继续适用。

提取结果是普通音频产物，裁剪结果是同 Artifact 的独立派生节点和不可变音频片段。Agent 根据用户给定依据或自己实际收到的媒体样本选择区间，将归档版本通过已有 `AUDIO_REFERENCE` 送入兼容媒体能力。`read_media_sample` 仅供模型观察，没有可供 Provider 引用的产物身份；不以临时样本代替持久片段。提取与裁剪不进行说话人识别、人声分离、降噪、声纹训练或语义转录，音频理解仍受已配置模型的输入能力限制。

外部生成继续使用 `propose_media_generation` 的固定批次、用户鉴权审批和结果归档。H3 原生带声视频用 `compose_video` 合成时应明确保留 `KEEP` 原声，允许 `lines=[]`，不自动补一条独立配音。

## 持久等待与冻结结果

`extract_audio`、`trim_audio` 与新协议 `compose_video` 的工具回执记录 `taskIds` 及 `awaitingLocalTask=true`。原回执中的成功只表示受理成功。Run 进入现有 `WAITING_TASKS`，FFmpeg 继续由现有媒体 Worker 在事务外执行，不让模型反复查询、不在工具事务中等待进程。

不增加表。工具账本的 Run、步骤、`tool_call_id`、操作 ID 和 Task ID 构成持久关联；Task 通过服务端写入的 `agentLocalOrigin={runId,operationId}` 保留来源，`Task.runId` 仍为空以沿用本地任务独立执行、结算与取消语义。读取任务必须同时匹配当前 Run 的已提交工具回执与可信来源，不凭 Task ID 或 JSON 标记授予其他 Run 权限。

任务终态后从 `Task.output` 取得实际归档版本，以 CAS 向原工具账本只追加一次 `localTaskOutcome`，不覆盖原回执、参数摘要或初次完成时间。成功要求存在 `artifactId/artifactVersionId`；失败、取消、阻断与 UNKNOWN 同样结束等待并返回明确状态。模型回复投影标记排队已结束，成功时才标记媒体完成，所有终态均标记已解决。UNKNOWN 或取消后的晚到结果保留任务历史，不改写已送给模型的终态快照，不自动重新生成。

同一回合中本地任务与媒体审批共同阻挡下一模型回合，全部解决后才以原工具调用 ID 续接。项目锁、Run 版本和步骤唯一键保护重复通知；任务先于回合提交完成时，回合可直接使用已冻结结果继续。有界 `WAITING_TASKS` 扫描修复遗漏通知，也覆盖结果已固定而下一回合尚未创建的恢复窗口。

停止导演仍不等于取消已受理的本地任务。任务可以继续归档并应用原有目标选择保护，但已取消或终结的 Run 绝不恢复模型调用；用户通过任务原入口另行取消或显式创建新尝试。用户显式新尝试清除旧 Agent 本地来源，不把新任务结果送回原工具回合。

## 升级与验收

新策略使用 `schemaVersion=7 / toolPolicyVersion=6 / systemPromptVersion=14`，Java 与 OpenAPI 同步，前端类型由合约生成。旧回执没有等待标记时不改写为新流程；退役运行协议仍按当前 Runtime 的拒绝规则处理，不静默扩大旧 Run 工具权限。此次无新增 HTTP 端点、MCP 工具、数据库表、迁移或依赖。

专项需覆盖真实 PostgreSQL 的工具批次回滚、幂等、跨 Run 拒绝、真实 FFmpeg 中间版本、成功/失败/UNKNOWN 回填、重复通知、遗漏通知恢复、取消不续接及最终合成归档。合成媒体与本地处理通过不能证明真实 LLM 已自主完成全流程，真实 Provider 接收音频参考与最终成片音色质量须单独记录；本文件不宣称这些验收已经完成。
