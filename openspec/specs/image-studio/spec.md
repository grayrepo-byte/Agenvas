# image-studio Specification

## Purpose

独立生图工作台让用户无需创建或打开画布即可提交静态图片生成，并在同一工作区查看批次状态、首张可显示的图片及后续结果。

## Requirements

### Requirement: First image readiness and reveal

工作台 MUST 区分结果已归档与图片已可显示。对于用户刚发起并仍在查看的批次，工作台 MUST 将首张可显示的结果从取景框原位揭晓一次，并在这次自动揭晓后保留该主图，直到用户主动切换。

#### Scenario: Submission reserves the frame and numbered slots

- **WHEN** 用户显式提交新的生图请求
- **THEN** 主图区立即显示本次请求的取景框，胶片按请求数量显示编号占位，不继续显示上一批的照片
- **AND** 取景框反馈已知请求与任务状态，不显示模拟进度百分比

#### Scenario: The first archived result has no loaded image yet

- **WHEN** 当前批次的首个结果已经归档，但还没有可显示的图片，且至少一张候选图仍在加载
- **THEN** 工作台立即更新已出图张数和对应胶片位置
- **AND** 主图区保留取景框、停止等待扫描并说明正在加载预览，而不是显示空图或继续显示正在生成

#### Scenario: An out-of-order result is the first displayable image

- **WHEN** 当前新批次的结果可以显示缩略图或原图，且它是首张可显示的结果，即使对应编号不是 `01`
- **THEN** 照片在同一主图区与取景框短暂交叉淡入淡出，对应胶片编号短亮一次
- **AND** 照片完整显示，不闪白、不缩放整张图
- **AND** 同一版本的原图随后载入、其他结果陆续到达或结果顺序更新时，不重播揭晓、不替换当前主图；后续结果填入各自编号

#### Scenario: First result is already available in the receipt or local image cache

- **WHEN** 当前请求的回执已经带有首个归档结果，或首张图片已在浏览器缓存中
- **THEN** 图片可显示时仍完成同一次揭晓，不要求先显示排队或生成中，也不人为延长等待

#### Scenario: The user changes their view before the first image is displayable

- **WHEN** 用户切去历史、主动选择其他变体或离开工作台，而首张揭晓尚未开始
- **THEN** 晚到的结果或图片不改变用户当下的选择
- **AND** 翻看历史、刷新或重新进入工作台不播放这次首张揭晓

#### Scenario: Reduced motion preserves the same feedback

- **WHEN** 设备开启减少动态效果，且当前批次有首张可显示的图片
- **THEN** 工作台直接切换到照片，不播放淡入淡出或编号短亮
- **AND** 批次状态、已出图张数、胶片编号和预览操作仍可用

### Requirement: Preview failure preserves generation outcomes

工作台 MUST 将浏览器预览失败与生成失败分开；预览失败不得改写服务端任务结果、减少已归档张数或自动触发再次生成。

#### Scenario: One image resource fails but the other is usable

- **WHEN** 缩略图或原图其中一个加载失败，而另一个可以显示
- **THEN** 工作台显示可用图片；如果只能显示缩略图，则提示原图不可用
- **AND** 不因同一结果切换清晰度而重复播放首张揭晓

#### Scenario: No first preview can be displayed or is still loading

- **WHEN** 当前有已归档结果，但所有候选预览均不可显示，且没有候选仍在加载
- **THEN** 主图区显示预览不可用，不恢复扫描，已出图张数及任务状态保持真实

#### Scenario: No result was archived and the task has an exceptional outcome

- **WHEN** 批次没有归档图片，任务失败、取消、待核实或归档受阻
- **THEN** 工作台显示对应的批次或任务反馈，不显示正在加载预览，也不播放成功揭晓
