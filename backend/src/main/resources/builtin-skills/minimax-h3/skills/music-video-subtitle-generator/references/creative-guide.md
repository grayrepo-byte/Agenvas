> Modified by Agenvas: canvas-tool adaptation of MiniMax H3 creative guidance; see the bundled NOTICE and LICENSE.

## STEP 2：Creative Contract

最终 prompt 前先建立简洁创意合约：

- 音乐类型、器乐、速度感（BPM）、vocal 模式、情绪温度。
- 歌词来源（锁定歌词）。
- 目标时长与多镜头拆解结构（例如：30s 拆分为 6 个 Short Shots）。
- 参考图角色分工：人物卡、场景卡、文字包装卡。
- 运镜、景别、对焦、剪辑卡拍密度与转场衔接逻辑。
- 明确排除项（如：严禁淡入淡出、严禁油亮 AI 美颜脸、严禁单镜头硬撑导致变形）。

## STEP 3：参考图分工与场景采样

每张参考图只分配一个窄任务：

- **文字参考卡**：只控制文字包装样式、字体质感、图形设计、排版比例和动效语言。严禁出现人物或场景。
- **人物参考卡**：只控制人物形象、脸部气质、发型、服装轮廓、角色比例、姿态和整体气场。
- **场景参考卡**：只控制场景视觉风格、空间氛围、影像质感、背景层次和光影气质。

### 3.1 近景场景切换与多镜头采样规则

对于 Trap、Dark-pop、Cyber-grunge 等快节奏 MV，全片应在多个近景场景之间快速切换：

- 场景气质保持统一，但每个镜头的空间必须明显不同（局部背景、隧道、墙面、灯带、反光地面）。
- 场景切换必须由明确音乐冲击触发：bass hit、808 drop、snare、vocal 重音或文字砸屏。
- 镜头切换是 trap beat 上的“视觉采样”：硬切、跳切、扫描 glitch 硬切、闪切、动作匹配切。

## STEP 4：预设语法（以 Dark-pop / Cyber-grunge & Trap 为例）

### 视觉与剪辑语言

- 写实高时装质感、胶片杂志质感（90年代末-00年代初独立杂志/复印纸/胶片扫描/zine拼贴）。
- 粗颗粒、轻微胶片抖动、半色调网点、印刷毛边、扫描错位。
- **剪辑绝对只使用硬切（Hard Cut）**，严禁淡入淡出、溶解或柔和转场。
- 画面与文字强响应鼓点：hi-hat roll（微震/跳帧）、snare（放大/硬切/肩膀下压）、808 bass hit（低频压屏/拉伸/错位）。

### 文字包装规则

- 文字是空间中的动态图形主体（不是普通字幕条），可以处于前景、中景、背景或被人物肩膀/手部遮挡。
- 文字绝不遮挡眼睛或主要面部表情；对嘴时避免遮挡嘴部。
- 有人声时，显示文字必须逐字匹配正在表演的歌词。每个镜头只出现一个主文字事件。

## STEP 5：Prompt 结构模板

多镜头分镜脚本必须按镜头（Shot）进行模块化输出，每个 Shot 标注准确的时长与音频映射：

```text
[Global Aesthetic & Character Lock]: (全局人物与美学锚点 Prompt)

Shot 1 (0.0s - 3.5s)
Vocal Line: "..."
Typography: "..."
Visual & Action: ...
Camera & Motion: ...
Transition Out: Bass-hit 上硬切至 Shot 2 / 同向甩镜

Shot 2 (3.5s - 7.0s)
Vocal Line: "..."
Typography: "..."
Visual & Action: ...
Camera & Motion: ...
Transition Out: ...
```

## STEP 6：BGM 连续性与多镜头自然衔接系统

### 6.1 音频全局连续性

全片必须强绑定同一轨 Master Audio（完整歌曲/Beat）。在分段生成视频时，严禁使用独立、断裂的片段音轨。所有视频片段在剪辑合成阶段均对齐至 Master Audio 的对应时间戳（Timeline）。

### 6.2 多镜头自然衔接系统（Natural Stitching Protocol）

为保证 >15 秒视频拼接后的极其自然，必须在 Prompt 规划与后期合成中严格执行以下“五大衔接锁”：

**人声与口型衔接锁（Vocal & Lip Continuity）**：
分镜切替点（Cut Point）必须落在歌词的句间停顿（Pause/Breath）或强鼓点（Snare/Drop）上。严禁在人物发出某个元音或唱词中途进行硬切，除非后一个镜头是极近特写且口型完全接续。

**音乐与节奏衔接锁（Rhythm & Beat Matching）**：
剪辑点必须精准裁切在音乐的 1/4 或 1/8 拍（Beat Grid）上。根据用户提供的节拍时间轴规划动作；仅使用现有合成工具开放的速度参数，复杂变速曲线列为后期建议。

**画面美学与色彩衔接锁（Aesthetic & Color Grading）**：
跨镜头生成必须携带相同的 Aesthetic Header Prompt（相同的胶片颗粒度、色调 LUT、光影方向）。统一颗粒和调色作为创作要求；当前合成工具没有 LUT/颗粒图层时，列为后期建议。

**空间与转场衔接锁（Transition & Motion Continuity）**：
长镜头延伸：已获得精确末帧且能力支持时，使用上一镜头的末帧（Tail Frame）作为下一镜头的首帧（Head Frame）输入，Prompt 加上 continuation of action。镜头切换：采用动能延续转场（如 Shot 1 结尾向右快速 Pan，Shot 2 开头从左向右 Pan 入；或利用人物手势遮挡镜头完成 Match Cut）。

**文字动态衔接锁（Typography Motion Stitching）**：
跨镜头的文字动效，前一镜头的文字在切替瞬间跟随 Bass Hit 执行“破碎/扫出/砸屏”，下一镜头的文字在重音上“砸入/展开”，形成视觉动能的连贯传递。

## STEP 7：审查清单（Checklist）

交付前静默检查：

- [ ] 目标时长超过 15 秒时，是否已自动采用多镜头（Multi-Shot）拆解结构？
- [ ] 是否已锁定唯一的全局 Master Audio，且分镜切替点对齐到了音乐拍子上？
- [ ] 镜头切替是否避开了人声唱词中途，口型与呼吸是否自然？
- [ ] 相邻镜头之间是否有明确的转场衔接机制（首尾帧接续 / 同向运镜 / Match Cut / 鼓点硬切）？
- [ ] 剪辑风格是否严格保持“纯硬切”，绝无淡入淡出或柔和转场？
- [ ] 三张参考卡（人物/场景/文字）是否角色隔离，且未互相污染？
- [ ] 文字包装是否作为空间图层，且绝未遮挡眼睛和主要面部表情？
- [ ] 全局画质、颗粒感、色彩与光影是否保持高度一致？
