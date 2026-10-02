# T07 视频节点比例修正

日期：2026-10-02

## 原因与行为

比例查询只纳入 IMAGE，VIDEO 使用既有布局矩形，完整预览的 `object-fit: contain` 因比例不符出现周围留白。先新增实际工作区投影测试，横屏、竖屏和方形视频均失败：节点仍为 225×300，没有采用媒体元数据。

将 `useImageNodeRatios.ts` 改名为 `useMediaNodeRatios.ts`，纳入视频当前展示素材的元数据查询。视频结果按实际像素宽高适配、保持长边，不使用下一次生成草稿的比例。工作区展示、React Flow 边界、缩放比例、拖动保存及对齐复用同一尺寸投影。封面和播放器继续完整展示，默认不自动播放。图片草稿的既有比例优先级保留。

草稿占位、空节点或元数据不可用时保持原布局；视频尺寸读取失败由 MediaCanvasCard 显示错误与重试，补齐四语言文案。切换视频结果重新适配，读取尺寸本身不写布局。没有后端、OpenAPI 或数据库迁移。

规格、ADR 0005 与开发清单已同步。

## 实际检查

在 frontend 目录执行：

```text
pnpm install --frozen-lockfile
./node_modules/.bin/vitest run src/features/canvas/ProjectWorkspaceImageLayout.test.tsx -t 'fits a video result'
./node_modules/.bin/vitest run src/features/canvas/ProjectWorkspaceImageLayout.test.tsx src/features/canvas/imageNodeLayout.test.ts src/features/canvas/MediaCanvasCard.test.tsx src/features/canvas/ProjectWorkspacePage.test.tsx src/features/canvas/CanvasSelectionClearing.test.tsx
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/eslint src/features/canvas/useMediaNodeRatios.ts src/features/canvas/ProjectWorkspacePage.tsx src/features/canvas/MediaCanvasCard.tsx src/features/canvas/ProjectWorkspaceImageLayout.test.tsx src/features/canvas/MediaCanvasCard.test.tsx --max-warnings=0
node scripts/check-i18n.mjs
```

锁文件安装成功，未修改依赖。视频专项用例先 3 项失败，修复后 3 项通过；最终 5 个文件共 144 项通过。覆盖横屏/竖屏/方形视频、不同草稿比例、拖动保存和重新挂载、极窄视频缩放保存、CAS 失败保留草稿、切换结果与草稿展示、元数据失败回退及显式重试。TypeScript、定向 ESLint 和 1779 条四语言文案检查通过。测试输出有 jsdom 尚未实现媒体 pause 的提示，测试退出码为 0。

测试通过 MSW 模拟 API；布局测试模拟 React Flow 手势，断言真实工作区生成的节点尺寸与保存请求，不代表真实浏览器像素或指针验收。

## 未验证范围

未运行全量测试、后端测试、真实浏览器视觉验收、真实 Provider 调用或部署。此变更只处理节点边界与媒体比例；视频文件本身编码进去的黑边不会被裁切。
