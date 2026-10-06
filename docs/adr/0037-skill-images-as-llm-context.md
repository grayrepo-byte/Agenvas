# ADR 0037：Skill 固定参考图直接进入 LLM 上下文

用户确认 Skill 参考图属于创作方法的上下文，不需要“准备项目参考”、项目素材副本或画布卡片。新运行选择固定版本后即可使用；先按需 `read_skill` 激活主说明，再通过 `read_skill_asset(skillVersionId, alias)` 读取图片。图片直接从发布归档进入 LLM 多模态消息，共用项目图片的预览数量和字节限制。选择与激活不自动附全部图片，避免把未使用附件送进上下文。

此决定覆盖 ADR 0031 实施设计的项目安装与固定图 Provider 输入语义；用户映射的项目 inputSlots 和媒体审批继续适用。发布归档不改写，旧 usage 数据保留；新运行固定图片只供 LLM 理解。工具结果及请求检查点只存安全清单/引用，实际字节在发送前、数据库事务外重新鉴权加载，已提交图片读取进入审批来源。

移除安装 API、预检 installed 字段和前端准备/等待步骤，Java、OpenAPI 和生成 TypeScript 一起发布，旧客户端须刷新。新策略 schemaVersion=5、systemPromptVersion=9、toolPolicyVersion=3；历史运行按原策略与已登记映射恢复。数据库表与历史内容保留，无新增迁移；未完成旧安装不继续复制，仅退役并清理追踪临时文件。
