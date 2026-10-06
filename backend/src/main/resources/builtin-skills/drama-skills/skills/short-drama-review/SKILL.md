---
name: short-drama-review
description: 审查本轮绑定的原著分析、故事、剧本、设定、分镜或提示词卡，输出带精确来源与修订建议的审查卡；保留未验证项，不改写来源。
license: MIT
---

# 创作卡片审查

先读取 [卡片工作流](references/agenvas-cards.md)，保存时读取 [卡片模板](assets/card-templates.md)。冻结用户点名的卡片版本和审查范围，审查只保存报告，不代替来源创作者修订卡片。

1. 读取本轮可见的精确版本，记录实际读过的范围。依赖卡未绑定、正文只有预览、媒体无法直接观察时，写未验证并说明需要的输入，不能判定通过。
2. 原著分析走 [原著审查表](references/rubric-source-analysis.md)：索引、原文、分析与覆盖卡按 sequence/版本引用核对，列出缺章、重复、来源变化及未核对。用户给进度记录不等于分析全文已经核实。
3. 故事与剧本走 [故事剧本审查](references/rubric-story-script.md)，资产与图片提示词走 [视觉设定/提示词审查](references/rubric-assets-prompts.md)，分镜/运动走 [视觉运动审查](references/rubric-visual-motion.md)。只读适用的一类；要检查多层时逐层引用证据。
4. 每个发现记录问题 ID、来源 artifactId/versionId、具体条目/行范围、事实、后果、严重程度和可执行修改建议。结构/引用问题与语义/风格意见分开，创作者选择不因审查者偏好而成为阻断。
5. 写《创作审查》卡：已验证、需修订、未验证与结论。证据不足使用「未验证」，不能把抽样审查称为全量；已给定修改方案时说明受影响卡片，但本阶段不自动执行修改。
6. 跨文档一致性核对只针对实际读到的版本；旧报告引用旧版，来源改版后需重新审查受影响范围。报告保存成功后引用真实版本交付，媒体批准仍由系统审批决定。

## 按需方法

方法与严重程度：[审查方法](references/review-method.md)；去模板：[模板修复](references/anti-template-repair.md)；生产观察：[质量门槛](references/production-quality-gates.md)、[项目校准](references/project-calibration.md)。缺实际图片/音视频观察时，校准只限用户说明与元数据，不能评价未见媒体。
规则见 [阶段契约](references/stage-contract.md)。
