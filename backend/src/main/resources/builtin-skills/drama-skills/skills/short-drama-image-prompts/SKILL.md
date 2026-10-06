---
name: short-drama-image-prompts
description: 依据视觉设定和实际参考图卡编写图片提示词卡。用于角色板、场景板、道具板、状态变体、风格帧或局部编辑；生成另走现有媒体审批。
license: MIT
---

# 图片提示词卡片

先读取 [卡片工作流](references/agenvas-cards.md)，保存时读取 [卡片模板](assets/card-templates.md)。从视觉设定卡确定用途、主体、识别锚点、当前状态和风格要求；有实际参考图时读取绑定 IMAGE 版本后再描述观察。

1. 确定本图用途：身份板、造型/状态变体、地点板、道具板、组合板或风格比较帧。关键帧落实为图片时保留分镜的戏剧职责与终点，不重新设计动作。
2. 只读取需要的视觉事实与剧情状态，引用来源卡的实际版本。每项 IMG ID 标识提示词条目，不是图片。
3. 按主体/身份锚点、当前变体、构图/视角、光色/材质、背景边界、文字政策及禁止项写可复制正文；正文语言使用用户或简报的明确要求。
4. 每个真实参考写 REF ID、实际 IMAGE versionId、顺序、用途、允许控制与不得控制。尚未生成的图用计划条目说明，不能放入 mediaInputs；不把 UUID 或控制字段混入可复制正文。
5. 核对身份与变体、连续性锁、文字政策、视角和光线是否冲突。每图只能同时满足一组明确要求，多视图对照需写版面关系。
6. 保存《图片提示词》卡或按对象拆卡；修订保留未改对象。用户只请求写提示词时到此完成。
7. 用户同时要求生成时查询 list_media_capabilities，按真实能力与参考版本提出 propose_media_generation。工具审批后才开始 Task，卡片里的确认文字不能授予批准。

## 按需方法

最小配方：[通用配方](references/common-recipe.md)；人物：[人物与造型](references/character-and-look.md)；场景：[地点板](references/location-plate.md)；道具：[道具板](references/prop-plate.md)。
变体：[造型与状态](references/look-and-state-variant.md)；组合：[组合板](references/production-sheet-recipes.md)；风格探索：[代表帧](references/lookdev-frame.md)；局部修改：[定点修改](references/edit-and-revision.md)；核对：[审查与示例](references/review-and-fixtures.md)。规则见 [阶段契约](references/stage-contract.md)。
