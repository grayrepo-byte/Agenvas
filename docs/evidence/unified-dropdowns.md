# 下拉框与设置页统一验证

日期：2026-09-30。

## 实际变更

- 16 处原生单选入口改用公共 `Select`：项目画幅、设置中的平台/适配器/质量、调用日志筛选、文字格式、裁剪比例、打光/智能编辑能力和图片操作参数。
- 10 处菜单表面改用 `DropdownMenu`：创建、画布工具、文字/媒体版本、图片引用补全、图片来源、视频模式、生成模型、图片扩展及三视图子菜单。版本菜单共用焦点、外部关闭与 Escape 处理；有业务光标或悬停规则的菜单保留原编排。
- `design-tokens.css` 集中配色、间距、字号、圆角、控件高度、阴影与动效变量。下拉视觉仅在 `Dropdown.css` 维护；清除对应的业务文件重复样式。设置、导航和公共表单主题消费相同变量；Provider/媒体页增加配置摘要，诊断/媒体页复用图标容器。
- ESLint 限制新的原生 select 和 div menu/listbox，要求复用公共组件。新增共享组件交互测试；图片扩展菜单测试改用 menuitem 语义，旧原图链接断言同步为现有图片放大入口，原图加载仍验证归档 Asset URL。
- 同步 MVP 规格、ADR 0005、开发清单与 `docs/frontend-design-system.md`。本次没有新增依赖、API 字段、后端行为或数据库迁移；工作区先前未提交的业务改动保留。

## 实际执行

在 `frontend` 使用现有 `node_modules/.bin` 工具：

```sh
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/eslint . --max-warnings=0
./node_modules/.bin/vite build
./node_modules/.bin/vitest run \
  src/shared/ui/Select.test.tsx \
  src/shared/ui/DropdownMenu.test.tsx \
  src/features/settings/LlmSettingsPage.test.tsx \
  src/features/settings/MediaSettingsPage.test.tsx \
  src/features/settings/CallLogsPage.test.tsx \
  src/features/settings/SystemDiagnosticsPage.test.tsx \
  src/features/settings/PasswordChangeSection.test.tsx \
  src/shared/ui/PageShell.test.tsx \
  src/features/projects/ProjectsPage.test.tsx \
  src/features/canvas/MediaCanvasCard.test.tsx \
  src/features/canvas/MediaDraftEditor.test.tsx \
  src/features/canvas/MediaVersionPicker.test.tsx \
  src/features/canvas/CropPanel.test.tsx \
  src/features/canvas/ArtifactVersionEditing.test.tsx \
  src/features/canvas/ProjectWorkspacePage.test.tsx \
  src/features/canvas/CanvasKeyboardDeletion.test.tsx \
  src/features/auth/LoginPage.test.tsx \
  src/features/auth/SetupPage.test.tsx \
  src/features/canvas/ContentCanvasCard.test.tsx
```

结果：类型检查、ESLint（零警告）与 Vite 生产构建通过；19 个定向测试文件、191 项测试通过。检查涵盖选项选择与表单值、方向键/跳过禁用项/Home/End/前缀键入、Escape/外部点击/Tab、禁用 fieldset/optgroup、向上展开/视口约束/resize关闭、背景更新移除选项、菜单遍历与恢复焦点，以及原页面的草稿、冲突、密钥清空、诊断费用确认、版本选用和任务行为。`git diff --check` 通过。静态搜索确认业务文件无原生 select，设置页和公共页面主题无独立颜色字面值。

## 浏览器证据（Mock）

Codex in-app Browser 打开 Beautiful UI 的 Select type 菜单，对照实际表面、边框、间距与选项呈现。随后使用临时 UI 预览，挂载项目真实的设置组件和 Query/Router，读取明确标记的 Mock 配置；预览拦截所有 API 请求并拒绝写入，不连接 Provider。临时预览文件与服务在验证后清理。

- 1440×1000 桌面：媒体与 Provider 配置显示摘要、面板、表单及侧栏；平台弹层成功打开，选择 OpenAI 后表单切换至 API Base URL/API Key，弹层关闭。
- 390×844 窄屏：Provider 配置和媒体平台选择已查看。页面 `scrollWidth=375`，小于 `innerWidth=390`；平台弹层横向范围为 34–341px、底部约607px，位于视口内。弹层使用向上展开，选中态与触发器显示一致。
- 调用日志筛选与空态已在浏览器 DOM 中读取；系统诊断与全部画布菜单主要由定向组件测试覆盖，未完成逐一浏览器视觉验收。

截图保存的是 Mock 页面，不是实际用户配置或真实服务调用结果：

- [媒体设置桌面](unified-dropdowns/media-desktop-mock.jpg)
- [Provider 设置桌面](unified-dropdowns/provider-desktop-mock.jpg)
- [Provider 设置窄屏](unified-dropdowns/provider-mobile-mock.jpg)
- [统一平台下拉框窄屏](unified-dropdowns/dropdown-mobile-mock.jpg)

## 未验证范围

未运行前后端全量测试、后端测试、锁文件重装或部署；没有真实 Provider 调用。未对所有平台的原生校验提示、触屏和屏幕阅读器逐一实测；不能把本次 Mock UI 与组件测试当作真实生成验收。保留现有业务失败、冲突、取消、UNKNOWN 与未授权处理，没有重写任务执行或服务端安全边界。
