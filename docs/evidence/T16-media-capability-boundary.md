# T16 媒体感知能力边界

当前模型请求只包含 Run 固定的文字、ArtifactVersion JSON 预览与授权工具结果，不包含图片像素、视频帧或音频样本；`ChatGateway` 的视觉能力保持未验证。初始系统消息现明确禁止把媒体元数据当作亲眼观察结果；用户要求分析视觉内容时应说明限制并要求文字描述。模型也不能把待审批媒体计划称为已生成结果，只有持久 Task 的已归档输出可作为可核实结果。

运行前确认面板同步提示此限制，使用户在启动规划前就能看到，而不只是依赖模型遵守提示。这个提示不阻止用户基于自己提供的文字描述提出创作计划；图片与视频仍各自经人工审批和持久 Task 生成。

验证：`cd backend && ./mvnw -q -Dit.test=MockStoryboardPostgresIT verify` 退出码 0，真实 PostgreSQL Mock 三镜头集成测试检查系统消息含媒体不可见与计划/结果区分；`cd frontend && corepack pnpm test -- src/features/canvas/ProjectWorkspacePage.test.tsx` 返回 0，Vitest 共 19 文件、71 项通过，包含运行前确认提示断言。前端 `typecheck`、`lint`、`build` 也均退出 0；`git diff --check` 通过。未调用真实 LLM 或媒体 Provider；系统提示不能单独保证任意模型绝不幻觉，故真实模型能力验收仍未完成。
