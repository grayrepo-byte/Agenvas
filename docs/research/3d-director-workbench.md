# 开源画布 3D 导演台调研

核对日期：2026-10-22。依据项目官方仓库、源码、许可证和第一方产品文档；未运行这些第三方应用，也未验证其模型供应商。下列“建议”是 Agenvas 的实现建议，具体行为与验收以本项目规格、ADR、代码和本次测试为准。

## 结论

市面上已经存在真正的浏览器 3D 导演台：用户在三维空间放置人物、道具和摄影机，再把摄影机拍到的画面作为构图参考送回画布。最适合 Agenvas 的首个纵向切片是“场景摆位 → 调整机位与画幅 → 保存场景 → 输出干净 PNG → 普通图片节点继续参与生成”。镜头路径、人物动作和参考视频可在这个持久化模型上逐步增加。此流程可从 [Open Canvas 的 Director 节点](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/README.md#3d-director)与 [infinite-canvas 的导演台宿主代码](https://github.com/tigerowo/infinite-canvas/blob/ccda172efba25cbee990a785bba715b90cc42b95/web/src/app/(user)/canvas/components/canvas-director.tsx)直接核实。

建议沿用当前 React/Vite 应用，按需加载 Three.js，在工作台内编辑可序列化场景。基础人偶和道具使用程序化几何体，核心能力无需作者专属账户、远程导演台或模型 API。Three.js 使用 MIT 许可证；官方 [OrbitControls](https://threejs.org/docs/pages/OrbitControls.html) 提供环绕、平移和缩放，[TransformControls](https://threejs.org/docs/pages/TransformControls.html) 提供对象移动、旋转和缩放。依赖版本需按本项目 React 19 基线重新核对，不能照搬案例的 React 18 / R3F 8 组合。[Three.js 许可证](https://github.com/mrdoob/three.js/blob/dev/LICENSE)、[Open Canvas 依赖清单](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/package.json)

## 能力边界

| 类型 | 用户实际操纵什么 | 输出与可靠性边界 |
| --- | --- | --- |
| 3D 场景导演台 | 多个对象的三维位置、朝向、尺寸，以及独立相机、画幅和光照 | 输出是真实三维投影的构图、姿态、遮挡或运镜参考；下游生成模型是否遵从参考仍取决于模型能力。 |
| 单图片视角/运镜提示词 | 一张图片、预设相机语言或提示词参数 | 没有可编辑的完整三维场景；不能据此保证背面几何、人物走位或真实空间遮挡。 |
| 全景图查看器 | 环境图中的观察方向 | 可作为背景与方向参考；全景贴图本身不等于有体积的可交互场景。 |
| 视频时间线/文本分镜编辑器 | 镜头顺序、时长、提示词和已有素材 | 可以组织故事，但仅有时间线不证明已实现 3D 布景。 |

以上分类是调研采用的判断口径。案例中的场景对象与相机结构可由 [storyai 工程 schema](https://github.com/jiguang132/storyai-3d-director-desk/blob/8c8bd361790be4d37158a7430365e65546e358fe/src/editor/schema/directorProject.ts)和 [影策 Previs 类型](https://github.com/ddcat-ai/open-ai-canvas/blob/a934a655f175b1bc41af8d92fa8c26493699ef70/web/src/types/previs.ts)复核。不要把“摄影机参数写入提示词”直接标成 3D 导演台。

## 已确认的开源项目

| 项目 | 已确认能力 | 自托管与集成 | 许可与采用判断 |
| --- | --- | --- | --- |
| [sjolt-ai/open-canvas-tv](https://github.com/sjolt-ai/open-canvas-tv) | 画布内 Director；程序化人偶、姿势/关节、基础几何体、对象变换、多画幅、截图回填 | Next.js/React，支持本地启动；Director 是应用内 React/R3F 模块 | MIT。适合参考最小的构图闭环；其生成与上传依赖 SJolt API，不能把整套产品当作独立本地生成方案。 |
| [tigerowo/infinite-canvas](https://github.com/tigerowo/infinite-canvas) | 独立导演台节点，角色/模型/全景/机位；宿主接收场景变更、截图和 MP4 | Go + Next.js，可 Docker 部署；同源 `/director/index.html` iframe 与消息桥 | 主仓库 AGPL-3.0。可参考交互与消息边界；复制实现前须核对本项目许可及对应文件来源。 |
| [ddcat-ai/open-ai-canvas（影策）](https://github.com/ddcat-ai/open-ai-canvas) | 当前称 Previs；对象、摄影机、关键帧、角色骨骼与动作；构图/白膜/深度输出接入画布 | Go + React；工作台直接集成 R3F/Drei/Three.js，场景独立于输出媒体 | MIT；人偶另有 CC0 来源声明。适合参考场景模型与异步输出保存边界。 |
| [jiguang132/storyai-3d-director-desk](https://github.com/jiguang132/storyai-3d-director-desk) | 独立浏览器导演台；人物姿势、模型导入、机位、画幅、截图和工程 JSON | React/Vite/R3F；提供宿主消息桥、本地实例隔离与浏览器持久化 | 代码 MIT；内置 UE 人偶附单独 Sketchfab Standard 声明。适合了解编辑器模块划分，不能默认将资产随 MIT 代码一起分发。 |
| [xiaozangao/3d-director-desk](https://github.com/xiaozangao/3d-director-desk) | 上述 storyai 的衍生版；摄影机/人物轨迹、跟踪目标、动作时间线、参考 MP4 | 静态浏览器应用；有版本化 iframe 接口、帧/视频导出与工程读取 | MIT 并保留上游来源；与 storyai 同源，不能当作第二种独立架构验证。资产许可仍需逐项核对。 |

### Open Canvas：最小可用的画布闭环

其 [README](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/README.md) 明确将 Director 输出定义为图片，后续可以连接图片或视频生成。源码的 [DirectorStage](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/src/components/director-stage/DirectorStage.tsx) 接收 `onSendToCanvas` 回调；[captureScreenshot](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/src/components/director-stage/actions/captureScreenshot.ts) 把渲染 Canvas 导出为 PNG Blob。[人偶实现](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/src/components/director-stage/characters/MannequinModel.tsx) 由球、胶囊等几何体构成，适合避免预置二进制角色资产的来源问题。

可借鉴场景编辑与产物回填分离。其 README 同时披露 API Key 存于浏览器、媒体交付依赖 SJolt CDN；Agenvas 应保持现有服务端密钥与素材存储边界，不采用这部分设计。[项目许可](https://github.com/sjolt-ai/open-canvas-tv/blob/a4d7e772546db2dee6346297047b098ba6e45b94/LICENSE)

### infinite-canvas：独立导演台与宿主画布

[README](https://github.com/tigerowo/infinite-canvas/blob/ccda172efba25cbee990a785bba715b90cc42b95/README.md#核心功能) 区分“独立 3D 场景导演台”和“给生成提示词添加相机/镜头参数”，说明两种能力不是同一件事。宿主 [CanvasDirector](https://github.com/tigerowo/infinite-canvas/blob/ccda172efba25cbee990a785bba715b90cc42b95/web/src/app/(user)/canvas/components/canvas-director.tsx) 校验消息 origin 与 source，初始化时发送节点 ID、主题及工程，监听工程变更，并接收截图 data URL 或视频 Blob。

这种 iframe 接入可隔离较重的 3D 依赖，但需要宿主承担场景保存、素材上传、节点创建、协议校验和生命周期。仅把外部页面嵌进画布，仍不能满足 Agenvas 的持久化和自托管要求。其主仓库 [LICENSE](https://github.com/tigerowo/infinite-canvas/blob/ccda172efba25cbee990a785bba715b90cc42b95/LICENSE) 为 AGPL-3.0；本次不建议从该项目直接搬代码。

### 影策：场景模型与异步导出

[Previs schema](https://github.com/ddcat-ai/open-ai-canvas/blob/a934a655f175b1bc41af8d92fa8c26493699ef70/web/src/types/previs.ts) 分别定义对象 transform、摄影机 target/FOV/焦距、关键帧、骨骼轨道与动作；[视口](https://github.com/ddcat-ai/open-ai-canvas/blob/a934a655f175b1bc41af8d92fa8c26493699ef70/web/src/components/canvas/previs/previs-viewport.tsx) 实际使用 R3F、Drei 与 Three.js，并非只拼接提示词。

[use-canvas-previs](https://github.com/ddcat-ai/open-ai-canvas/blob/a934a655f175b1bc41af8d92fa8c26493699ef70/web/src/pages/canvas/use-canvas-previs.ts) 将构图和参考视频上传后再接入节点，并在上传完成后重新检查项目、源节点、场景和镜头是否仍匹配，避免异步输出写进已经切换的项目。它还区分深度产物和普通参考，不自动把深度输出接到不支持控制能力的模型。

代码 [MIT](https://github.com/ddcat-ai/open-ai-canvas/blob/a934a655f175b1bc41af8d92fa8c26493699ef70/LICENSE)。其 [人偶许可说明](https://github.com/ddcat-ai/open-ai-canvas/blob/a934a655f175b1bc41af8d92fa8c26493699ef70/web/public/previs/LICENSE-mannequin.txt) 指向 Quaternius Universal Animation Library；[资产作者页面](https://quaternius.itch.io/universal-animation-library) 明确列出 CC0。若后续引入模型，应重新固定实际下载文件、版本、哈希与来源，不只抄一份第三方声明。

### storyai 与衍生版：成熟编辑能力和可嵌入协议

原版 [README](https://github.com/jiguang132/storyai-3d-director-desk/blob/8c8bd361790be4d37158a7430365e65546e358fe/README.md) 记录模型/全景导入、导演/机位视角、工程 JSON 和本地持久化。[hostBridge](https://github.com/jiguang132/storyai-3d-director-desk/blob/8c8bd361790be4d37158a7430365e65546e358fe/src/editor/io/hostBridge.ts) 的消息类型为 `storyai:director-desk-*`，按同源校验，场景按 instanceId 隔离。它与 infinite-canvas 宿主的 `storyai:director-*` 不是同一份协议，接入时不能仅凭名称相似混用。

xiaozangao 衍生版的 [嵌入合约](https://github.com/xiaozangao/3d-director-desk/blob/a6c931cd36d8263d986706f74ab4efe9d5151959/docs/embed-contract.md) 补充 `hostOrigin`、能力查询、`requestId` 配对、工程读取、首/尾/当前帧和参考视频导出。工程 JSON 只包含本地素材引用；二进制素材在浏览器 IndexedDB，文档明确指出跨浏览器/域名不可直接恢复。Agenvas 需要服务端素材 ID 与可迁移引用，不能将 `blob:` URL 当持久资产。

其 [README](https://github.com/xiaozangao/3d-director-desk/blob/a6c931cd36d8263d986706f74ab4efe9d5151959/README.md) 记载通过摄影机轨迹和共同时间线生成干净参考 MP4。这是后续运镜阶段的参考，不能据此声称 Agenvas 已具备视频导出。代码采用 MIT，[UE 人偶声明](https://github.com/xiaozangao/3d-director-desk/blob/a6c931cd36d8263d986706f74ab4efe9d5151959/public/models/ue-mannequin-retopology.license.txt) 则写明 Sketchfab Standard，必须分别处理软件与素材许可。

## 商业产品参考与筛选限制

[TapNow 官方文档](https://docs.tapnow.media/en/docs/canvas/create-text-and-3d) 展示独立 3D 工作室、场景/角色/摄影机、运镜时间线、PNG/视频回画布与 GLB 导入导出。它特别区分自由观察视角与实际拍摄镜头，并要求保存失败后先重试保存再导出。可参考该闭环；本次未核实 TapNow 可用于再分发的开源实现，不把它列入开源代码候选。

[OpenCut 官方 README](https://github.com/OpenCut-app/OpenCut/blob/main/README.md) 描述开源视频编辑器及正在重写的编辑器/插件架构；本次没有从它核实出可采用的 3D 场景导演台，因此不将“视频编辑器”当作这项能力的证据。其余候选未纳入本次源码验证，这不等同于断言它们没有相关功能。

## Agenvas 建议的实现切片

1. **可编辑场景**：新增导演台入口与独立工作区，显示地面、程序化人偶/几何道具、对象列表和变换属性；以稳定对象 ID 保存位置、旋转、尺寸与颜色。
2. **实际拍摄机位**：区分导演观察视角和拍摄相机；保存相机位置、目标、垂直 FOV、画幅。输出使用拍摄相机，画面不包含选中框、坐标轴或网格。
3. **持久化闭环**：场景使用有版本的 JSON，通过现有服务端内容版本/CAS 机制保存；Zustand 仅持有草稿。保存失败保留场景与修改，明确显示失败/冲突和重试入口；重开节点恢复已保存场景。
4. **复用媒体链路**：截图形成 PNG File/Blob，经现有素材上传与图片节点链路入库，创建派生关系或连线；用户可把图片作为既有图片/视频生成参考，不因截图自动发起模型调用。
5. **独立运行**：导演台操作与截图不依赖外部模型、第三方账号或远程托管页面；WebGL 不可用、上下文丢失或截图失败时给出可恢复反馈。
6. **后续扩展**：在稳定场景保存之上增加多机位、姿态/骨骼、GLB 导入、镜头关键帧、白膜/深度帧与参考视频。每一项分别验证素材许可、数据迁移、浏览器支持及下游模型是否真正支持该参考能力。

此切片的验收应覆盖场景保存/重开、多导演台隔离、对象与相机修改、画幅准确的干净 PNG、上传失败保留草稿、内容版本冲突、取消/关闭、只读/未授权和 Mock 下独立运行。外部模型遵从构图和真实 Provider 兼容性另列验证，不能用本地截图成功替代。

## 核对快照与未验证范围

已读取源码的公开快照：Open Canvas `a4d7e772546db2dee6346297047b098ba6e45b94`；infinite-canvas `ccda172efba25cbee990a785bba715b90cc42b95`；影策 `a934a655f175b1bc41af8d92fa8c26493699ef70`；storyai `8c8bd361790be4d37158a7430365e65546e358fe`；xiaozangao 衍生版 `a6c931cd36d8263d986706f74ab4efe9d5151959`。

第三方项目构建、浏览器性能、实际 MP4 编码、资产再分发法律结论与真实生成效果均未运行或验证。许可证判断仅记录仓库与作者公开声明；采用第三方实现时仍须保留适用版权与许可文本，并核对所复制文件及资产的具体来源。
