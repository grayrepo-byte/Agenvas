---
name: short-drama-video-prompts
description: 依据分镜、关键帧和实际媒体卡写视频动作、表演、运镜及配乐意图卡。用于把镜头转为可生成提示词或修订运动边界，生成通过现有能力提案。
license: MIT
---

# 视频提示词与配乐意图卡片

先读取 [卡片工作流](references/agenvas-cards.md)，保存时读取 [卡片模板](assets/card-templates.md)。读取分镜、关键帧提示词、实际图片/音频参考和用户的目标能力要求。只有当前 list_media_capabilities 返回的能力决定可提交的模式、参数和时长，资料中的模型示例不证明该能力在项目中已接通。

1. 按 SHOT ID 核对来源、起止状态、必须发生的动作与反应。剧本对白逐字引用，声音、可读文字与终点都必须在镜头范围内找到承载。
2. 写动作触发、行动者、方向/接触、阶段顺序、结果与表演；运镜写明确动机、触发、路径及终点景别。不要用「自然、电影感」替代因果。
3. 在《视频提示词》卡中为每镜写可复制正文、时长意图、输入模式、真实媒体 versionId、用途和保持/禁止项。未有参考时分别交付文生候选或待补参考计划，不冒充图生输入已就绪。
4. 台词、动作、停顿与反应的估算放在本镜核对栏；所有串行区间不超过镜头时长，同步动作写明重叠。速率未知标记估算，不能宣称成片已验证。需要拆镜先提出影响，不能静默改分镜义务。
5. 配乐只在用户要求时写《配乐意图》卡：情绪功能、起止位置、节奏、配器、声音冲突和参考边界；不把歌词创作或音乐文件冒充已经完成。
6. 核对来源版本、起止状态、参考用途、时长与模型能力；保存或修订提示词卡后交付。生成需明确请求，并通过实际能力及 propose_media_generation 的用户审批执行。

## 按需方法

动作/时序：[运动配方](references/motion-recipe.md)、[表演动作时间](references/performance-action-timing.md)；镜头/声音：[连续性](references/camera-audio-continuity.md)；可生成性：[生成负担](references/generability.md)、[提示词语法](references/production-prompt-grammar.md)。
目标适配：[能力档案](references/target-model-profile.md)、[交付轮廓](references/delivery-profile.md)。只有用户点名或实际能力需要时读取具体模型写法：Minimax H3、Seedance 2.0/2.5、Wan 3.0 对应清单附件，不能遍历全部档案。
核对：[审查与示例](references/review-and-fixtures.md)。规则见 [阶段契约](references/stage-contract.md)。
