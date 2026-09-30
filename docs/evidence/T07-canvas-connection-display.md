# T07 画布连线流光与显示设置（2026-10-01）

## 已实现行为

- 画布右上工具栏新增齿轮“画布设置”，两个独立开关默认开启。
- “始终显示连线”关闭时，未选择节点则隐藏关系线；选择节点只显示其直接进入/离开的关系，多选取并集。点击其中一条可删除线后，线保持可见以便 Delete/Backspace；取消选择后隐藏。
- 流光复用现有来源到目标的 Bezier 路径，包括目标在来源左侧的线。静态底线保留各关系颜色和历史版本虚线，流光不接收指针，也不改变输出组线的不可删除语义。
- “连线流光效果”关闭时仅显示静态线；系统 `prefers-reduced-motion: reduce` 同样停止动画。动画由 SVG/CSS 播放，不逐帧更新 React，React Flow 不渲染隐藏线。
- 两个布尔值与存储格式版本保存在浏览器 localStorage，键按认证账户和项目隔离；刷新、重新进入项目恢复，同源标签页通过 storage 事件同步。选择、草稿、关系与服务端业务实体不进入此存储。
- 读取异常使用默认值并显示错误，非法字段/不支持的格式版本安全回退；写入失败保留本页选择、显示失败与显式重试，不声称已持久化。

## 文件与边界

`CanvasRelationEdge.tsx`、`canvasEdgeDisplay.ts` 管连线呈现；`CanvasSettingsMenu.tsx` 与 `useCanvasDisplayPreferences.ts` 管开关和本地持久化；`ProjectWorkspacePage.tsx`、`CanvasToolMenu.tsx` 接入。`styles.css` 保留关系配色并增加流光/设置布局，公共 `Dropdown.css` 补充 checkbox 菜单项的悬停样式。规格 6.2 / 19.3、ADR 0005 与 T07 同步。

无后端、OpenAPI、生成 API 类型或 Flyway 变更。设置不跨浏览器同步，也不包含在项目导出中。

## 实际验证

- `pnpm install --frozen-lockfile`：成功；依赖和锁文件未修改。
- 定向 Vitest：9 文件、83 项通过，覆盖新增显示/持久化/曲线路径/页面接入，以及既有关系投影、连线删除、卡片删除、选择清空与工作区交互。
- 测试类型/格式修正后复跑新增 4 文件、15 项：通过。
- `pnpm run typecheck`、`pnpm run lint`（含公共主题色检查）、`pnpm run build`：通过。生产构建仍提示画布 chunk 超过 500 kB，不影响构建成功。
- 真实 Chromium + 实际 React Flow，Mock API fixture、隔离浏览器会话：验证三条有向关系线、向左目标曲线路径、流光 dash offset 随时间向负方向推进、两个开关、点击节点只显示直接关系、点击线保留选中操作、点击空白隐藏、刷新后恢复关闭状态、独立重开流光、系统减少动态效果下保留静态线。未捕获页面运行错误，未向服务端发出业务写请求；已查看设置菜单与流光截图。
- `git diff --check`：通过。

## 未验证范围

未运行全量前端测试、后端测试、真实 Provider 调用、大画布性能或其他浏览器验收。浏览器验证使用明确的 Mock API，未修改或验收现有真实项目数据；本次实现只影响客户端呈现。
