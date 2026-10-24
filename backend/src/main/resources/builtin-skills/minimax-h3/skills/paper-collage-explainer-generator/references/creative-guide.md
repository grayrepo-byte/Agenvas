> Modified by Agenvas: canvas-tool adaptation of MiniMax H3 creative guidance; see the bundled NOTICE and LICENSE.

## 全局风格规则

对每张静帧和每段视频应用以下规则：

1. **统一整体风格。** 每段都应像同一套编辑感纸拼贴系列：半调剪影、大色块、暖白描边、柔和实体纸影、干净构图、触感纸材和协调的拼贴音效。
2. **控制纸张质感强度。** 纸张不能像完全扁平数字图层，也不能变得过旧、脏、皱或偏棕，除非用户明确要求。优先使用干净精致的手工纸质感：细纤维、轻微不规则撕边、浅层毛边、层叠接缝和软阴影。
3. **统一色彩基调。** 不要在未经确认时引入与静帧或整体色板冲突的牛皮纸、棕色、发黄或做旧底纸。每段从与已确认静帧主色一致的纸面开始。
4. **让运动像停格拼贴。** 使用明确纸片动作：逐片出现、滑入或弹入、轻微回弹、压平、暂停，然后锁定最终构图。避免快速旋转、过度翻飞、混乱飞散、全局淡入、平滑数字平移/缩放或泛泛漂浮。
5. **保持段落协调。** 当前一两段建立了批次风格和音效节奏后，后续片段和修订应参考该节奏与色调，让整支片子像同一系列。
6. **默认制作精致 16:9 层次场景。** 除非用户明确要求其他平台画幅，默认按 16:9 横版规划和生成。利用宽画幅建立前景/中景/背景层次、丰富环境道具、清晰主体层级和横向构图，不让画面变碎。
7. **突出纸拼贴工艺。** 每张静帧和每段视频都应让拼贴方法可见：可分离纸片组、半调照片剪影、彩色卡纸点缀、触感阴影、撕边、层叠接缝，以及弹入、滑入、轻微回弹、压平、暂停、锁定等停格动作。

## STEP 3：建立静帧规格

制作方案确认后，为每个已批准段落写紧凑的视觉规格，规格应能直接用于图像生成。

包含：

- 脚本含义或故事节点
- 视觉隐喻
- 画幅
- 背景色场
- 强调色
- 关键物件组及作用
- 构图、前景/中景/背景层次与留白
- 最终画面关系
- 风格连续性说明
- 该静帧的避免项

使用以下风格签名：

```text
flat bold color field, black-and-white halftone photographic cut-outs, selective colored cardstock accents, warm cream keylines, soft paper shadows, fine uncoated-paper grain, premium editorial paper collage, clean refined hand-torn paper edges, subtle fibrous edges, layered paper seams
```

色彩建议：

- 焦橙或红色：劳动、时间压力、紧迫感
- 芥末黄：工具、警示、累积错误
- 墨绿：认知、重置、判断、超现实平静
- 深紫：记忆、结构、神秘、梦境逻辑
- 青绿：协作、执行、系统流动
- 玫红：荒诞、仪式、戏剧张力

不要把钴蓝作为自动默认色。通过纸张质感、半调处理、描边、阴影、画幅和受控色板统一整批画面。只有当故事节点需要对比时才变化色场。

## STEP 5：规划停格组装

对每张已批准静帧，准备运动方案，并把已批准静帧视为完成最终画面。

默认运动顺序：

1. 从与已批准静帧主背景一致的干净纸色场开始，而不是不匹配的牛皮纸或棕色底。
2. 基础结构作为纸片滑入、弹入或压入。
3. 角色、卡片、物件或主要隐喻元素入场。
4. 次要物件逐个组装，带小幅回弹、压平和暂停。
5. 最终关系锁定到已批准构图。
6. 结尾短暂停留在完整静帧式画面上。

运动提示词指导：

```text
Paper-collage stop-motion assembly. Start on a clean paper field matching the approved still's background color and tone. Assemble the scene piece by piece with tactile stop-motion timing: foreground, midground, and background paper groups appear in a readable order; each paper object appears, slides or pops in, lightly bounces, presses flat, pauses, and locks into place. Preserve the halftone dots, cream keylines, subtle fibrous torn edges, layered paper seams, soft shadows, paper grain, color field, and approved aspect ratio. End by holding the completed composition matching the approved still frame. Sound design: tactile collage SFX only, synchronized to paper-piece motion: soft paper slide, pop-in, press-flat tap, light rustle, and tiny paper snap where appropriate. Default: do not add BGM, voiceover, dialogue, or subtitles unless the user explicitly requested them. No mismatched kraft-paper opening, no brown/yellowed base unless approved, no fast spinning, no chaotic object flight, no smooth digital layer movement, no scene cuts, no camera move, no zoom, no morphing, no new objects, no text, no logos, no watermark, no UI.
```

## STEP 7：质量检查

按以下标准检查静帧和视频：

- 视觉隐喻或故事节点无需屏幕文字也能理解
- 静帧默认具有漂亮的 16:9 构图、清晰色场、明确物件层级和前景/中景/背景深度
- 物件组数量可读，不是一屏碎片
- 片段从与已批准静帧或批次色板一致的色场开始
- 组装是逐片可见的，不是整体淡入
- 运动像定格拼贴：独立纸片弹入或滑入、轻微回弹、压平、暂停、锁定
- 默认保留触感拼贴音效：能听到纸片滑动、弹入、压平、摩擦等声音；除非用户明确要求，不应有 BGM、旁白口播或字幕
- 纸张质感可控：不太平、不太旧、不偏棕/牛皮纸，除非已确认
- 色调在整批中一致
- 最终帧接近已批准静帧
- 没有不需要的可读文字、假 UI、字幕、水印或 Logo

只要隐喻清楚、最终构图仍像已批准静帧，微小细节漂移可以接受。若出现严重漂移、文字、假 UI、开场颜色不匹配、未经允许的牛皮纸底、过脏过皱纸质、丢失停格组装、不需要的 BGM 或不需要的旁白，应根据问题发生阶段重生成或后期处理。
