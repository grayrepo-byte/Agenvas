---
name: short-drama-assets
description: 从剧本卡拆出角色、造型、场景、道具和连续性状态，产出视觉设定卡。用于资产拆解、复用与变体判断、时代锚点或跨集状态整理。
license: MIT
---

# 视觉资产设定卡片

先读取 [卡片工作流](references/agenvas-cards.md)，保存时读取 [卡片模板](assets/card-templates.md)。读取本轮剧本卡、已有视觉设定卡和明确的视觉方向；现成剧本可直接拆解。

1. 逐场找出实体、可见描述、状态变化、时代锚点和含混指代，记录剧本卡版本及场次/行范围。
2. 区分身份、变体与镜头瞬态：换掉就不是同一人/地/物的是身份，衣服、伤势、时段、天气、开合、持有变化是变体；姿势、视线、左右手和机位是分镜状态。故事知识与关系只引用可见后果。
3. 每项明确复用、增加变体、新身份或未决。资产条目 ID 是文字标记，不创建或冒充系统媒体资产。写入《视觉设定》卡，规模较大时按人物/场景/道具拆卡，以总览卡引用实际版本。
4. 写可见的识别锚点、本集状态、变化原因及生效范围。持续限制动作的身体—物件—空间关系写进状态，可独立识别的物件仍有自己的条目。
5. 核对服装/伤势、持物/所有权、道具开合、地点光态、跨集进入和退出状态。连续性锁只锁不随剧情改变的可见事实，变化部分留给状态。
6. 提示词语言与条目名称不同的时候，记录一致的画面代称；同名普通词会造成歧义时写明不按名称自动识别。
7. 声音方向写音色、语速、口音与表演要求。只有实际绑定的 AUDIO 版本才是声音参考；用户提供的音色 ID 作为候选配置，须经当前媒体能力核实后才能用于提案。文字声音设定不冒充音频。
8. 保存成功后给出关键身份决定与未决项；图片提示词和分镜读取视觉设定的精确版本，不自动启动媒体。

## 按需方法

时代：[时代锚点](references/era-anchors.md)；出场证据：[出现提取](references/occurrence-extraction.md)；身份：[身份与变体](references/identity-vs-variant.md)。
人物：[人物与造型](references/character-and-look.md)；场景：[地点与视图](references/location-and-view.md)；物件：[道具与状态](references/prop-and-state.md)；声音：[声音方向](references/voice-direction.md)。
连续性：[状态变化](references/continuity-delta.md)、[跨镜一致性](references/continuity-lock.md)；完成核对：[资产审查](references/asset-review-checklist.md)。规则见 [阶段契约](references/stage-contract.md)。
