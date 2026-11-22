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

### Requirement: Restore the prompt when opening a historical image

编辑栏可编辑时，用户激活含有已归档图片的历史卡片，工作台 MUST 在切换到对应批次预览的同时，把该批次提交时的完整提示词直接填入编辑栏，不弹确认框，不改变其他草稿设置，不修改历史，不提交生成。

#### Scenario: Open a historical image and continue editing

- **WHEN** 用户在桌面历史栏或窄屏历史抽屉中点击或通过键盘激活有图片的历史卡片，包括已经默认选中的卡片
- **THEN** 工作台直接用该批次的完整提示词替换编辑栏文字，用户可以继续修改，不必等待图片或再次点击「回填参数」
- **AND** 模型、生成参数及参考图保持不变；窄屏抽屉照常关闭，不自动唤起键盘

#### Scenario: Browsing and background updates do not rewrite the editor

- **WHEN** 页面初始化、刷新、轮询、历史详情返回，或用户只在同批胶片中换图
- **THEN** 工作台不回填提示词，也不覆盖用户正在编辑的文字

#### Scenario: Selection is browse-only without an image or while the editor is locked

- **WHEN** 用户选择没有归档图片的记录，或在本次受理尚待确认、编辑栏锁定期间选择历史卡片
- **THEN** 工作台只切换预览，保留编辑栏提示词与本次冻结命令
- **AND** 等待解锁或结果到达后不补做之前的回填

#### Scenario: Restore the complete generation setup explicitly

- **WHEN** 用户另行点击现有的「回填参数」操作
- **THEN** 工作台仍按原有行为恢复该批次的提示词、模型、生成参数与固定版本参考图，但不提交生成

### Requirement: Undo the last history prompt replacement

历史回填实际替换了提示词时，工作台 MUST 提供一次「撤销回填」，只恢复最近一次替换前的文字，不切换预览或其他参数。

#### Scenario: Undo an accidental replacement

- **WHEN** 用户在历史回填替换文字后选择「撤销回填」
- **THEN** 编辑栏恢复该次回填前的文字，包括原先为空的情况；预览、模型、参数和参考图保持不变，撤销入口消失

#### Scenario: Consecutive selection does not create an undo stack

- **WHEN** 用户连续点击多条历史图片，或点击提示词与当前文字相同的历史图片
- **THEN** 只有实际发生的最近一次文字替换可撤销；无变化的点击不覆盖已有撤销记录

#### Scenario: A new editing action ends the earlier undo opportunity

- **WHEN** 用户手动修改回填后的提示词、显式回填全量参数或提交生成
- **THEN** 工作台清除旧的撤销入口，不用旧草稿覆盖新的编辑

### Requirement: Delete a generation batch from Studio history

工作台 MUST 提供按批次删除历史的操作，要求再次确认，且默认不删除图片文件。删除历史 MUST 保留编辑草稿、任务、用量和生成命令的幂等身份。

#### Scenario: Delete history without removing image files

- **WHEN** 用户保留默认选项并确认
- **THEN** 批次不再出现在历史和批次详情中，已生成的原图和缩略图继续保留
- **AND** 不取消已受理任务，不自动回填其他批次的提示词，也不提交生成

#### Scenario: Also permanently remove generated files

- **WHEN** 用户勾选永久删除文件并确认，且批次已确定结束、生成文件未被其他批次用作参考
- **THEN** 工作台移除历史记录，服务端撤销原图和缩略图的读取并后台永久清理文件，不放入回收站
- **AND** 只清理该批次的生成结果，不删除它使用的参考文件

#### Scenario: Protect active and referenced results

- **WHEN** 批次仍有未确定结束的任务，或者其结果文件已被其他批次用作参考，且用户选择同时删除文件
- **THEN** 服务端拒绝请求，保留记录和文件，工作台显示失败原因
