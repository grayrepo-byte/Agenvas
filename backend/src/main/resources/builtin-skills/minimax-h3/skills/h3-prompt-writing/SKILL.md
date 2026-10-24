---
name: h3-prompt-writing
description: 编写和改写 MiniMax H3 的文生视频、关键帧与全能参考提示词，组织视听时间轴和精确参考标签；适用于提示词准备，不代表对应模型或输入模式已接通。
---

> Modified by Agenvas: canvas-tool adaptation of MiniMax H3 creative guidance; see the bundled NOTICE and LICENSE.

# H3 提示词编写

将用户的视频创意和明确的素材关系整理为 H3 格式。先读[画布执行约定](references/agenvas-cards.md)。

1. 确认目标模式：T2VA、I2VA、FL2VA、L2VA 或 Ref2VA。模式名称描述创作目标，实际生成仍以已发布能力契约为准。
2. 文本或首尾关键帧模式按需读[基础模式指南](references/base-en.txt)，按原字段顺序组织 `integrated_multimodal_description`、`overall_soundscape`、`non_diegetic_music`。I2VA 从首帧向前发展，FL2VA 明确两帧间的可达路径，L2VA 反推开场并在末尾准确落到目标帧。
3. 全能参考模式读[参考模式指南](references/ref-en.txt)，依次输出 `subject_definitions`、`summary`、`retention_analysis`、`detailed_description`、`overall_soundscape`、`non_diegetic_music`。标签在全文中保持同一含义。
4. 参考标签只分配给实际提供的素材；区分主体身份、风格、动作、画面结构和声音用途。看不到或听不到的内容依据用户说明并标记待确认。
5. 按镜头时间线同时写动作、机位、空间、光线、声音和状态交接。对白/歌词在指南规定的 d 标签中保留原语言，可见文字逐字列出；指南中的英文结构遵循目标模式要求。
6. 输出可直接复用的完整提示词，按用户需要保存为文字卡；素材或时间范围不足时说明缺口。只写提示词不发起生成。
