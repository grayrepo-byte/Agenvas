# 音频节点设计验收（2026-09-30，收尾于 2026-10-01）

final result: passed

范围为本轮音频节点、编辑器、音色库与 MV 草稿入口。用户要求功能/状态参考附件，但 UI 遵从系统风格。上一轮画笔验收保存在[历史记录](docs/evidence/design-qa-before-audio.md)，本结果不覆盖其范围。真实 Seed Audio / Seedance 云端效果未验证；这里的通过只指界面与本地 Mock 行为。

## Visual truth 与比较方式

三张用户附件分别为：有内容 `reference-content.png`（1104 × 609）、音色库 `reference-voices.png`（1149 × 779）、无内容 `reference-empty.png`（859 × 641）。路径均位于 `docs/evidence/audio-ui-2026-09-30/`。浏览器采用 1280 × 900 CSS 视口，截图也是 1280 × 900；实现图片与来源不拉伸，按原像素并排放在同一张比较图中。全图保留画布与面板上下文，局部比较仅平移裁切；不把画布坐标或外围留白当作组件缺陷。

| 状态 | 全图并排 | 局部并排 | 实现原图 |
| --- | --- | --- | --- |
| 有内容 | [全图](docs/evidence/audio-ui-2026-09-30/comparison-content-full.jpg) | [局部](docs/evidence/audio-ui-2026-09-30/comparison-content-focus.jpg) | [播放器](docs/evidence/audio-ui-2026-09-30/content.jpg) |
| 音色库 | [全图](docs/evidence/audio-ui-2026-09-30/comparison-voices-full.jpg) | [局部](docs/evidence/audio-ui-2026-09-30/comparison-voices-focus.jpg) | [音色库](docs/evidence/audio-ui-2026-09-30/voices.jpg) |
| 无内容 | [全图](docs/evidence/audio-ui-2026-09-30/comparison-empty-full.jpg) | [局部](docs/evidence/audio-ui-2026-09-30/comparison-empty-focus.jpg) | [上传空态](docs/evidence/audio-ui-2026-09-30/empty.jpg) |

三组全图与局部均已查看。宽全图在工具展示时缩小到 2048px，磁盘文件保持原像素；局部图直接核对文字、操作可见性与间距。浏览器为 Codex in-app browser，独立测试后端 18081 / 前端 15173 / PostgreSQL 17.11，未重建用户既有服务。

## Findings 与五项视觉表面

无未解决的 P0/P1/P2 音频界面问题。用户要求的系统风格优先于附件颜色和演示名称，允许的差异列在下方。

- 字体与排版：系统 sans-serif、已有 13/14px 操作文字和 Phosphor 图标，可访问名称与焦点样式；标题、时长与操作不互相挤压。
- 间距与布局：默认空卡片 430 × 240、内容卡片 430 × 160，保留明确的用户调整尺寸。音色库宽 640px、双列列表，按工作区顶部和锚点之间的空间限制高度；只滚动列表，保持搜索、筛选、关闭与费用提示可见。
- 颜色与状态：系统深色面板、边框和蓝色选中/运行态。空态没有播放器或 Agent 对话；内容态显示 MV、重新生成、下载、版本与 Agent 入口。等待、失败、UNKNOWN、保存冲突沿用任务/草稿反馈。
- 媒体与图像质量：波形来自实际解码音频；Mock 是 3 秒提示音，明确标注“非语音合成”。默认暂停，进度与时长随真实音频变化。音色使用名字首字的系统圆形标识，不伪造人物照片或真实试听。
- 文案与层级：空态提示声音、对白、情绪和环境音；内容态完整显示 Mock 提示。音色库列官方 speaker 名称、语言/场景、最近与收藏；试听说明创建付费任务，Mock 禁止音色试听。

## 允许的产品差异

附件粉色运行、荧光 MV 和白色选中框改用系统主题。配音员示例替换为核对过的官方 speaker ID 与名称。截图所选 Mock audio 用于本地验收，配置火山连接与能力后可选 Seed Audio 1.0。Agent 对话复用独立的既有 Agent 卡片，固定音频版本且不自动运行；助手与翻译也复用正常文字节点/任务，不用静态返回值冒充模型效果。

## Comparison history

1. 初次比较发现内容卡片过高、音色库为窄单列。初始证据为 `comparison-content-before.jpg`、`comparison-empty-before.jpg`、`comparison-voices-before.jpg`，位于同一证据目录。修正为内容态 160px 与 640px 双列库。
2. 进度控件受全局 input 最小尺寸影响，挤压 Mock 说明；重置尺寸并限制播放器内部滚动，最终内容截图完整显示说明。
3. 双列库在锚点上方空间不足时可能越过工作区；限制可用高度、固定顶部控制、只滚动列表，并重新拍摄/比较，最新图中上下控件完整可见。
4. 选择聚焦同时考虑编辑器空间；拖动隐藏工具栏/编辑器，批量拖动保存所有移动节点。选择、拖动与布局定向测试通过。

## Interactions、控制台与限制

浏览器完成创建、空节点上传、全局导入、Mock 生成、播放/暂停/进度、下载、音色搜索/筛选/收藏/选择、模板、展开、助手预览、翻译任务、MV 草稿与带音轨 Mock 视频、重新生成 v2 后选回 v1、刷新持久化及 Agent 绑定。详见[专项证据](docs/evidence/audio-nodes-2026-09-30.md)。

热更新过程中出现一次 React effect 依赖长度变化错误，完整刷新后完成最终验收且未出现新增运行错误。历史日志仍含热更新的 React Flow nodeTypes/edgeTypes 稳定性警告，不声称全应用控制台零警告。jsdom 的 pause 未实现警告不等同浏览器播放失败。

10 个前端定向文件共 186 例通过；最终音色库布局相关两文件 86 例复跑通过，类型检查、lint 与生产构建通过。12 个后端专项类共 30 例通过，含真实 PostgreSQL/解码与假 HTTP。全量测试、真实云端生成/试听、窄屏画布编辑和大音频压力测试未运行。既有画布要求桌面最小宽度，音色库窄屏 CSS 单列规则不作为移动端实测结论。
