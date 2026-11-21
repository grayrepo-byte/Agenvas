# Spec Delta

## ADDED Requirements

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
