---
name: short-drama-storyboard
description: 把剧本和视觉设定卡转成分镜、场次调度与关键帧提示词卡。用于镜头设计、导演方案比较、轴线站位或跨镜连续性核对。
license: MIT
---

# 分镜与关键帧卡片

先读取 [卡片工作流](references/agenvas-cards.md)，保存时读取 [卡片模板](assets/card-templates.md)。绑定本集剧本、视觉设定、既有分镜和必要图片卡的精确版本，已有单集可直接开始。

1. 逐场确定观众需要看懂的行动、信息、关系变化与注意转移，写场次空间关系和进入/退出状态。导演方案未定时比较不同的注意组织，明确用户已经选择的版本。
2. 为镜头分配稳定 SHOT ID，写来源剧本场次/行范围、戏剧职责、景别、机位、轴线、人物站位、动作、对白/声音、持续时间意图和终点。
3. 将角色/道具持续状态、左右手、视线、光向和遮挡逐镜投影，状态改变必须有来源或本镜可见动作。拆镜只在戏剧信息、空间或可生成性需要时发生。
4. 关键帧提示词冻结一个可观察时刻。起始帧与结束帧分别说明身份、状态、构图与允许变化；不把跨时间动作塞进静态帧。
5. 写实际参考的 IMAGE versionId、顺序、用途及控制边界。未有图片时写关键帧计划和缺项，不能声称已有参考。图片阅读不自动生成或授权生成。
6. 保存《EP001 分镜》《EP001 关键帧提示词》卡，长集按场次范围拆卡。每个输出引用输入固定版本，分镜中的关键帧条目引用对应提示词卡版本。
7. 核对完整剧本范围、对白/声音承载、轴线、持物与边界衔接，报告未覆盖场次。局部修订仅追加受影响卡片版本；更新下游引用时核对用户要求和权限。

## 按需方法

调度：[调度手册](references/blocking-playbooks.md)、[场次视觉计划](references/scene-visual-plan.md)；镜头：[镜头手艺](references/shot-craft.md)、[生产镜头语法](references/production-shot-grammar.md)、[方案比较](references/coverage-audition.md)。
关键帧：[关键帧手艺](references/keyframe-craft.md)、[漫剧语汇](references/comic-keyframe-lexicon.md)、[光线](references/lighting-craft.md)、[剧本到关键帧示例](references/screenplay-to-keyframe-example.md)。
修订：[镜头身份](references/shot-revision-identity.md)；核对：[审查与示例](references/review-and-fixtures.md)。规则见 [阶段契约](references/stage-contract.md)。

## 完成

点名场次都有职责明确的镜头和关键帧意图，来源、连续性及未提供参考可追溯，卡片保存成功即完成。生成视频交给当前媒体提案工具，不开启外部流程。
