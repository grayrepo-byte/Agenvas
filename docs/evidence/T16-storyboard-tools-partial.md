# T16 文本与分镜：阶段证据

本切片完成四类受控创作工具的业务行为，但尚未完成 T16，也不满足 M3 门禁。没有真实 LLM 调用或从用户一句指令自动规划三镜头。

`create_character`、`create_scene` 与 `create_shots` 使用 JSON Schema 声明、严格字段白名单、长度/数量/顺序限制和既有 Artifact 领域校验。角色/场景的参考图片、镜头的角色/场景版本只能来自 Run 初始显式绑定或本轮已生成版本；同项目但未绑定的历史版本也不能被 Agent 擅自引用。`create_shots` 接受 1–6 个连续排序的镜头，使用一笔数据库事务创建所有不可变 ArtifactVersion、语义引用、Agent 输出组画布卡片、项目事件和工具账本；任一镜头失败则全部回滚。`create_text` 同样会放置输出卡片。

`StoryboardToolsPostgresIT` 在真实 PostgreSQL 中验证显式绑定图片 → Agent 角色 → Agent 场景 → 三个有固定版本引用和顺序的镜头，以及画布输出组。第二个镜头引用未授权版本时，已尝试创建的第一个镜头、卡片和工具账本均回滚。`./mvnw verify` 最终通过 14 个单元测试和 13 个 PostgreSQL 集成测试；此前两次全量运行遇到 Docker/Testcontainers 启动端口与连接超时，受影响用例单独运行通过，第三次全量运行通过。Compose 重建后服务健康、Flyway V14 成功。

未完成：持久 Run Worker 自动组装上下文与工具回合、模型结构修复最多两次、真实 LLM/Mock 媒体黄金路径、其余创作工具与审批计划。因此开发清单 T16 三项仍保持未勾选。

2026-09-24 当前状态补充：持久 Run Worker 与 Mock 三镜头黄金路径已在后续切片实现。对不可用的固定模型配置/工具调用能力或历史密钥，`BlockedRunNotice` 根据已持久化的安全 Task 错误码显示明确原因；不渲染模型输入、私有回合或 Provider 原始异常。前端组件测试覆盖该诊断与媒体 UNKNOWN 的区分。结构错误至多两次修复和真实模型能力验证仍未完成，T16 继续未勾选。
