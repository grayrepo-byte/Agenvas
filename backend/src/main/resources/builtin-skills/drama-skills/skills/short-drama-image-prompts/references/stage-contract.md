# 图片提示词阶段契约

本阶段处理本轮绑定的输入卡片，产出或修订对应创作文字卡。读取、保存、版本引用、权限与跨轮续接以 [Agenvas 卡片工作流](agenvas-cards.md) 为准；卡片模板见 [本阶段模板](../assets/card-templates.md)。创作者明确决定优先，模型建议不能写成已接受事实。

结构完整性与内容质量由 Agent 对实际读到的版本逐项核对，报告检查范围和未验证项。规则编号用于解释问题，不能宣称本系统执行了未提供的自动检查。

## 本阶段规则

### `IMG`

| ID | Class | Knowledge |
|---|---|---|
| IMG-01 | structural_invariant | Each `IMG-...` item names the exact visual-setting item and current variant it depicts. |
| IMG-02 | craft_default | Put distinguishing identity, geometry, scale, or state before generic quality language. |
| IMG-03 | reviewed_invariant | Character sheets preserve identity while depicting one coherent Look. |
| IMG-04 | reviewed_invariant | Location plates preserve geography, orientation, anchors, material, and light, normally without cast. |
| IMG-05 | reviewed_invariant | Prop plates preserve scale, shape, material, wear, function, and text policy. |
| IMG-06 | structural_invariant | Edit prompts declare exact target, changes, preserve set, and expected continuity impact. |
| IMG-07 | structural_invariant | Readable text cannot coexist with a global no-text constraint. |
| IMG-08 | reviewed_invariant | A claim about reference pixels requires a creator/reference-owner description or authorized input-reference observation bound to the inspected bytes; otherwise admission stays unresolved, and a negative prompt cannot stand in for evidence. |
| IMG-09 | reviewed_invariant | Each reference states its purpose, what may be copied, and what must not be copied; a composition-, scale-, or effect-only reference cannot redefine identity, content, text, or story state. |
| IMG-10 | reviewed_invariant | Views of one Location in the same time/weather state share key-light source, colour-temperature relation, and contrast direction; any difference cites a recorded cause and its delta. |
| IMG-11 | reviewed_invariant | A lookdev frame binds accepted visual direction and production profile across a declared character-expression, core-location, or high-pressure test axis; a high-pressure frame also binds exact screenplay blocks for story state and information permission, while style references may control only declared surface treatment and never identity, fixed geography, story state, cast count, or prop text. |
| IMG-12 | reviewed_invariant | Each real input reference has a stable `REF-...` slot binding explicit order, a visible project-relative path or other unambiguous artifact locator, a Chinese label, and may-control/must-not-control scope. Reordering preserves slot identity; replacing media explicitly revises that slot's locator. `IMG-...` remains reserved for image-prompt headings. |
| IMG-13 | structural_invariant | An `IMG-...` item named by a continuity lock in `视觉设定卡` carries that lock's surface verbatim in its copyable prompt. |
| IMG-14 | structural_invariant | Every `REF-...` slot an image-prompt item declares carries one 用途 from the closed set 身份/造型状态/地理/构图/尺度/效果/起始帧/结束帧/风格, the same vocabulary the storyboard uses, so one picture answers one question. An asset board normally uses the first six; 起始帧 and 结束帧 belong to a shot. |

规则分级由高到低：`structural_invariant`（结构缺陷，阻断）、
`reviewed_invariant`（需证据判断）、`craft_default`（常用做法，可覆盖）、
`taste_option`（创作者选择，不作缺陷）。创作者已接受的事实优先于本表。
