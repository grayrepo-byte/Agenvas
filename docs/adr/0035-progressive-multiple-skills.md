# ADR 0035：多 Skill 候选与渐进式加载

状态：接受（2026-10-06）。用户确认将创作 Skill 改为「名称和功能描述 → 模型按需加载 SKILL.md → 按需读取附件」，并支持同时选择多个 Skill。此决定覆盖 ADR 0031 实施设计中的单 Skill 上限与首轮正文注入；固定发布版本、Run 快照、鉴权和媒体审批边界继续适用。

遵循 [Agent Skills 的渐进式加载规范](https://agentskills.io/specification#progressive-disclosure)，用户为每个 Run 明确选择最多八个不同 Skill 的固定版本，默认配置也可保存有序的多个版本。未选择时仍显式使用 NONE；账号目录不会自动成为模型可用目录。同一 Skill 同时只能选一个版本，每个版本的输入、附件路径与参考别名独立。

创建 Run 时冻结全部候选正文、附件与素材映射，以保证后续读取和恢复不受编辑、发布或解绑影响；持久快照不等于模型上下文。首轮只提供名称、功能描述和读取所需的版本 ID。模型通过 read_skill 读取完整主文件及资料/素材清单，再通过带 skillVersionId 的 read_skill_resource 分页读取附件。只有已提交的主文件读取账本记录才构成激活；失败或回滚的工具批次不激活 Skill，也不允许读取其附件。

媒体提案的来源只记录当前 Run 已激活的 Skill，以及各自实际读取的附件与范围。未激活 Skill 的固定素材不能直接用于媒体提案；GUIDE 素材始终不能发给 Provider。输出种类须属于已激活 Skill 声明的种类并集；其中适用当前种类的 Skill 共同约束必需输入和参考，不适用的 Skill 不阻断其他创作方法。单 Skill 的种类约束与历史行为一致。多个方法互相冲突时由模型调整方案，后端仍验证结构化约束，不赋予模型批准或越权能力。

新 Run 使用策略 schemaVersion=4、systemPromptVersion=8、toolPolicyVersion=2。历史提示词 1–7、工具策略 1、单 Skill 快照及生成来源继续按原协议恢复和展示，不把旧 Run 重新解释为尚未激活。新来源使用 creativeSkill.schemaVersion=2 的 skills 数组；Task、结果和审批 hash 保留该固定结构。

V13 将默认绑定主键改为 (agent_id, skill_id)，增加有序 position 和八项上限；旧绑定保留精确版本并置于位置 0。绑定接口改用 skills 数组，运行选择改用 VERSIONS/skills，预检返回 creativeSkills 数组，前后端和生成类型必须一起升级。旧客户端需刷新并重新发送选择；旧幂等命令不转换为新选择意图。项目导出 schemaVersion=7 记录多个默认版本及 position，读取方须接受新版本。迁移只新增，不改已提交迁移，不重置现有数据。
