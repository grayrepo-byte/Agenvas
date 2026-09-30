# 画笔标注设计验收（2026-09-30）

final result: passed

范围仅为画笔标注工具栏、直接图片编辑和保存派生节点。历史画布整体验收保存在 [旧记录](docs/evidence/design-qa-before-brush-markup.md)，其范围不由本次结果覆盖。

## Visual truth 与证据

- 原设计：`docs/evidence/brush-markup/reference-editor.png`（606 × 516），`reference-derived.png`（562 × 436）；来自用户两张附件。
- Chrome 实现初始状态：`docs/evidence/brush-markup/editor-empty-chrome.png`，586 × 515 CSS/PNG px，devicePixelRatio=1。两图均以约 161 × 286 px 的原图内容展示，未缩放对比工具栏；原设计是画布中的卡片截取，实现编辑区域居中，因此对比组件时平移对齐，20 px 的外围画布宽度差不作为组件尺寸差。
- 全图并排证据：`docs/evidence/brush-markup/editor-comparison.png`（1192 × 516）。状态为红色、画笔选中、无新增标注。
- 工具栏局部并排证据：`docs/evidence/brush-markup/toolbar-comparison.png`（689 × 94），颜色、图标、选中态和间距均可直接核对。
- 操作后：`docs/evidence/brush-markup/editor-chrome.png`（586 × 515），实际笔迹、矩形、擦除、蓝色箭头和文字。
- 窄视口：`docs/evidence/brush-markup/editor-narrow-chrome.png`（320 × 560），编辑区宽 281 px，无横向溢出；笔刷尺寸弹层与所有按钮可见。整个画布仍遵守已有最小编辑宽度约束，不代表全应用支持移动编辑。
- 保存并刷新：`docs/evidence/brush-markup/derived-chrome.png`（2560 × 1131，恢复 Chrome 默认视口），`derived-detail.png` 为包含两个标题、原图、结果与派生线的 820 × 730 原像素裁切。

## Findings

无未解决的 P0/P1/P2 标注功能问题。保留项目现有 Phosphor 线性图标。浮动工具栏、八色圆盘、粉色工具选中态、底部撤销/重做与保存均对应设计。

五项视觉表面：

- 字体与排版：项目 sans-serif，工具栏 13px、保存 14px；图标按钮有可访问名称与焦点，标题省略不挤压尺寸。
- 间距与布局：46px 工具栏、42px 颜色盘和底部控制，圆角胶囊；笔刷尺寸通过再次点击画笔/橡皮展开，默认不占用图稿之外的常驻空间。
- 颜色：深色工具栏、八种颜色、粉色选中和保存；加载/保存等待时明确禁用。
- 图片质量：使用附件中原图区域作为专门测试素材；归档 PNG 保持 161 × 286 原始像素尺寸，未标注的底部区域逐像素与原图相同。该小样本不代表大图压力测试。
- 文案：入口标记本地；编辑器提供图标工具，不含标注说明、模型、提示词或 AI 提交。标题和尺寸分开显示。

允许的产品上下文差异：编辑时使用独立深色遮罩保持操作焦点，原图标题完整放在工具栏下方，避免设计截取中标题被遮挡。来源与结果保留项目现有标题、打开原图按钮及派生线风格。具体画布位置由现有布局和缩放决定，不强行复制截图坐标。

## Comparison history

1. 首次 Chrome 检查发现尺寸滑杆常驻、背景透出原节点，造成重复图片与额外垂直间距。
2. 改为纯深色背景，滑杆通过再次点击工具展开，颜色盘向工具组对齐；重新截图、查看上述全图与局部并排图，无未解决 P0/P1/P2。
3. 补验 320px 宽度，将颜色盘的横向偏移在窄视口清除，无溢出。

## Interactions 与控制台

Chrome 实测画笔、颜色、橡皮、矩形、箭头、文字输入与回车确认、撤销/重做、笔刷大小弹层、保存、关闭、刷新后保留结果。保存后独立 PostgreSQL 显示 2 节点、1 派生线、0 Task，原节点与结果使用不同 Asset。

控制台曾出现工作区并行代码热更新导致的 Hooks 顺序错误，完整刷新后界面恢复且未再出现新的运行错误；React Flow nodeTypes/edgeTypes 对象稳定性警告仍属既有工作区问题，本次不声称全应用控制台零警告。

## Implementation checklist

- [x] 直接绘制与保存，不调用 AI。
- [x] 保存后独立节点与可删除派生线，保留来源结果。
- [x] 颜色、五种工具、笔刷大小、撤销重做。
- [x] 单元测试与 PostgreSQL 专项验证、TypeScript、相关 ESLint、生产构建。
- [x] OpenAPI、生成 TS、规格、词汇表、清单和 ADR 0018 同步。

未运行全量测试、真实 Provider 调用和 40MP 大图压力测试。未重建用户现有 8088 Compose 服务；Chrome 预览使用独立测试数据与 8081 后端/5173 前端。
