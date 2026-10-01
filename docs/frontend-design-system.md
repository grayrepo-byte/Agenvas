# 前端公共组件与样式

2026-09-30 按用户确认统一，视觉参考 [Beautiful UI](https://www.beautifului.dev/) 的深灰表面、细边框、紧凑选项、柔和悬停态与层次清晰的字体。

## 唯一入口

| 内容 | 维护位置 |
| --- | --- |
| 配色、间距、字号、圆角、控件高度、阴影与动效时长 | `frontend/src/shared/ui/design-tokens.css` |
| shadcn 基础组件 | `frontend/components.json`、`frontend/src/shared/ui/primitives/` |
| 单选表单兼容层 | `frontend/src/shared/ui/Select.tsx`、`Dropdown.css` |
| 动作、模型、版本、输入模式与引用菜单 | `frontend/src/shared/ui/primitives/dropdown-menu.tsx`、`command.tsx` |
| 页面表单、面板、提示、状态、摘要与图标容器 | `PagePrimitives.tsx`、`PageTheme.css` |
| 导航与页面框架 | `PageShell.tsx`、`PageShell.css` |
| 语言入口与四语言选择窗口 | `shared/i18n/LanguageSelect.tsx`，复用 `Dialog.tsx` 与 `PageTheme.css` |

公共视觉只在以上入口修改。业务 CSS 保留锚点、宽度、排列、缩略图尺寸等布局规则，消费 `--ui-*` 变量；禁止在页面重新定义下拉框颜色、边框、字号、内边距、悬停和选中样式，禁止复制一份公共组件再修改。画布图片、画笔及引用色等业务内容颜色不属于控件主题。

## 系统主色（2026-09-30）

按用户提供的按钮参考图取样，主色为 `#3D9AFF`（RGB 61、154、255），替代原粉色。唯一配置是 `design-tokens.css` 的 `--ui-accent`；悬停、强调文字、浅背景、透明外圈、光晕与焦点变量均由它派生，不在业务 CSS 写独立色值。按钮前景使用 `--ui-accent-foreground`。

登录、项目、设置与画布共同使用主色，包括直接生成、文字保存、Agent 发送/运行、裁剪确认、智能编辑、打光、标注工具、开启的开关、卡片选中和合法连接反馈。关系线颜色也集中配置：输入使用主蓝色，派生使用浅蓝色，引用灰色，Agent 输出绿色。失败/警告/成功提示、实际画笔颜料、遮罩与图片引用身份色继续表达各自语义。

`pnpm lint` 先运行 `scripts/check-theme-colors.mjs`，拒绝公共配置之外业务 CSS 的蓝/紫/洋红品牌色字面值（hex、rgb/rgba、hsl/hsla）以及主色/焦点变量的独立字面值定义，再执行 ESLint。灰度、红色错误与绿/橙状态色不属于该检查的品牌范围；它不扫描 TypeScript 中的画笔/遮罩/引用数据。后续品牌调整只改公共变量。实际检查与浏览器证据见 [蓝色主题验证](evidence/blue-theme.md)。

## 单选控件

使用基于 shadcn/Radix 的 `Select` 兼容层，继续传入标准 `option`、`optgroup`、`value/defaultValue`、`onChange`、`name`、`required` 和 `disabled`。常规控件使用 shadcn 默认尺寸；画布使用 `density="compact"`。视觉、交互和选项结构由 `primitives/select.tsx` 维护，语义颜色映射到现有 `--ui-*` 主题。

隐藏原生 select 只作为表单提交、必填校验和 change 事件桥接；label 指向可见的 shadcn 触发器。选项由 Radix Portal 与定位机制管理，避开画布缩放和祖先容器裁切；靠近视口底部向上展开，宽度与高度限制在视口内。提供方向键、Home/End、Enter/Space、前缀键入、Escape、Tab 和外部点击；禁用选项与禁用 optgroup 不可选择，禁用 fieldset 不可打开。选项更新会同步到已打开的面板，保存与业务版本检查仍由原应用服务处理。

## 动作菜单

使用 `primitives/dropdown-menu.tsx` 的 Root、Trigger、Content、Group、Item 和 Sub 组合。模型、版本和工具菜单由 Radix 管理键盘、禁用、Portal、外部关闭及焦点恢复；异步保存继续由调用方控制关闭时机。引用补全和画布添加卡片使用 `Command`、`CommandList`、`CommandGroup`、`CommandItem`，由编辑器保留文字光标与活动引用。

引用来源菜单保留悬停入口，指针进入 Portal 后取消延迟关闭。参数/资源选择器包含专用编辑逻辑，仍使用 `ui-popover-surface`；画布通过 `isolation` 将 React Flow 工具栏限制在自身层级中，使 body Portal 的组件可正常显示。

ESLint 禁止公共 Select 之外的原生 `<select>`，禁止以普通 `<div role="menu/listbox">` 新建菜单；必须复用上述组件。检查命令：在 `frontend` 执行 `./node_modules/.bin/eslint . --max-warnings=0`。

## 设置页

Provider 和媒体配置顶部使用 `SummaryStrip` 展示真实已读取的配置摘要；未保存输入不改变摘要。系统诊断、调用日志和设置表单复用同一主题。小屏导航自动容纳五个入口，摘要和表单收为单列。配置版本、后台刷新保留草稿、冲突处理、密钥清空及计费诊断确认继续遵循原行为。

验证记录见 [统一下拉框与设置页](evidence/unified-dropdowns.md)。

### 后台全宽布局

后台页面使用侧栏之外的全部宽度，不设置主内容最大宽度或居中留白。桌面采用 20–40px 自适应边距；导航收起后主内容随之扩展，短窗口仅导航列表滚动，手机改为顶部导航。宽度与边距在公共设计变量中维护，减少动态效果遵循系统偏好。

Provider 的配置编辑与工具诊断并排显示，辅助栏最多 400px；窄屏恢复单列。配置表单显示未保存修改，可显式撤销并清空临时密钥和费用确认，不发起保存或诊断。系统诊断宽屏横排四张状态卡，异常记录与改密并排；诊断数据到达不重建密码表单。调用日志宽屏横排六个筛选字段，详情四列，显示已应用筛选数量。项目卡片按可用空间自动增列。媒体编辑窗口继续使用原有短模态框，表格在小屏局部滚动。

定向测试及桌面、手机 Mock 浏览器证据见 [后台全宽布局](evidence/admin-full-width.md)。


## 媒体设置表格与模态框

媒体连接和能力以两张表格展示；名称、模型、输入范围、估算价格、状态和行操作可直接比较。列表不包含常驻编辑表单。添加/编辑连接与发布/编辑能力复用 `shared/ui/Dialog.tsx` 和 `Dialog.css`，颜色、控件与文字继续消费公共主题和设计变量，页签视觉也只定义在公共样式中。

能力编辑分为模型配置、默认参数、输入限制、估算价格四个页签；切换分区保留所有字段。窗口最大高度为 680px，且受动态视口高度约束；标题与保存栏固定，仅表单内容滚动。小屏表格只在各自区域内横向滚动。关闭窗口保留页面内草稿，成功保存后关闭；未保存内容不会写入浏览器持久存储。

shadcn/Radix Dialog 隔离背景，打开时锁定背景滚动，Tab/Shift+Tab 在窗口控件间循环，关闭后恢复触发按钮焦点。Escape 先关闭展开的下拉菜单，再关闭窗口；请求执行中禁止关闭。Select 使用 Radix Portal 与嵌套焦点机制；Tab 在其关闭焦点回调中转移到下一个表单字段。隐藏页签中的必填项校验失败时自动显示对应页签并聚焦字段。配置版本变化、CAS、错误保留和显式载入规则延续原有行为。

语言选择复用 `Dialog` 的紧凑尺寸，不显示无操作的页脚；四个原名按钮直接选择，当前语言以选中边框与勾号标识。入口在桌面折叠导航中只显示地球图标，在展开导航和手机顶部显示当前语言名。语言控件的外观集中在 `PageTheme.css`，侧栏只管理间距；验证见 [语言交互调整](evidence/language-picker-2026-10-01.md)。

验证及 Mock 截图见 [媒体设置表格与模态框](evidence/media-settings-tables.md)。

## shadcn 公共组件迁移（2026-10-02）

公共按钮、输入、文本域、复选框、开关、提示、空态、状态标记、表格、页签、表单字段和选项组采用仓库中的 shadcn 源码。媒体设置、系统日志与调用日志表格使用 `Table` 组合；媒体和系统设置使用 `Tabs`；图片/视频参数使用 `ToggleGroup`；媒体表单使用 `FieldSet`、`FieldGroup`、`Field`、`FieldLabel`。共享 `Dialog` 只承担业务表单组合，旧的手写 DropdownMenu 已删除。

配置为 Radix、Vite、Tailwind 4、Phosphor 图标，`@/` 同时由 TypeScript 和 Vite 解析。新增组件前在 `frontend` 执行 `pnpm dlx shadcn@latest info` 和 `docs <component>`，沿用当前组件目录与公共语义变量。新增第三方依赖须同步依赖基线。专用媒体播放器、画笔、卡片手势和资源编辑器继续由领域组件维护。

实际命令、回归覆盖与 Mock 截图见 [shadcn 迁移验证](evidence/shadcn-migration.md)。
