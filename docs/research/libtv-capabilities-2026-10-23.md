# LibTV 官方能力核对

核对日期：2026-10-23。用于 Agenvas 产品差距分析，依据 LibTV 官方站及官方公开 Skill 仓库。本次仅做公开资料与代码阅读，未登录产品、调用生成接口、运行 Skill、购买会员或测试真实生成质量。

## 来源与证据口径

本次比较对象是 [LibTV（www.liblib.tv）](https://www.liblib.tv/)。[libtv-labs 官方 GitHub 组织](https://github.com/libtv-labs) 指向该域名，旗下 [libtv-skills README](https://github.com/libtv-labs/libtv-skills) 也链接同一产品。未采用 libtv.gongke.net、libtv.org、导航站、媒体转述及转载版使用指南证明产品能力。

- **官方产品表述**：官网显示入口或宣传功能，可证明产品方向及公开承诺，不能证明生成质量、稳定性及完整验收。
- **公开文档/代码可证**：Skill 仓库定义的协议与客户端行为；接口是否可用仍需真实账户实测。
- **未核实**：未取得可读的一手细节，不等同于产品缺少该功能。

## 已核实能力

| 领域 | 一手证据所能支持的结论 | 证据强度与边界 |
| --- | --- | --- |
| 画布与创作入口 | 官网同时展示 LibTV 画布、LibTV Agent BETA、项目、资产，以及图片、视频、音频、剧本生成和智能剪辑入口；有精选画布及创作者作品展示。 | **官方产品表述**。证明覆盖创作链路，未据此推定节点数据结构、撤销历史或实时协作机制。[官网](https://www.liblib.tv/) |
| 剧本与导演辅助 | 官方介绍原创剧本辅助、精细编辑、故事/剧本改编；TV Director 提供导演风格选择、分镜语言和提示词辅助。 | **官方产品表述**。可作为完善剧本至镜头组织的参考；“一万字剧本直出 80 集”等是宣传，未验收长篇一致性与实际成片质量。[官方产品介绍](https://www.liblib.tv/ai-video/rival?sourceid=040154) |
| 3D 预演 | 3D-BOX 专页明确宣传 Agent 搭景、站位调度及手绘轨迹运镜，并有新建空白 3D-BOX 入口。 | **官方产品表述**。未验证可用对象、姿态、机位、工程保存格式、输出规格和生成遵从度。[3D-BOX](https://www.liblib.tv/3d-box) |
| 视频编辑方向 | 官方展示元素替换，可沿用原视频动作和镜头替换角色、场景或其他元素；另有智能剪辑入口。 | **官方产品表述**。不能由此推定多轨时间线、精确裁剪、字幕、口型或声音混合的具体支持范围。[官方产品介绍](https://www.liblib.tv/ai-video/scene?sourceid=020821) |
| 自然语言 Agent | 官方 Skill 描述以自然语言请求生成、编辑、风格转换和复杂视频创作，由 Agent 编排流程。 | **官方文档表述**。完整短剧、MV 等属于能力声明，本次未实测。[官方 Skill](https://github.com/libtv-labs/libtv-skills/blob/c609246c1eca69f6bc129bcbb5d64c36734e4a4a/skills/libtv-skill/SKILL.md) |
| 外部 Agent 接入 | 官方提供会话创建/追加指令、参考图片/视频上传、进度查询、项目切换和结果批量下载脚本；结果关联网页项目。 | **公开文档/代码可证**。开源的是接入脚本；不能因此声称整个 LibTV 可自托管。[README](https://github.com/libtv-labs/libtv-skills/blob/c609246c1eca69f6bc129bcbb5d64c36734e4a4a/README.md) |

### 可复核的接入闭环

官方客户端通过 `POST /openapi/session` 创建会话或向已有会话追加消息，得到 `sessionId` 和 `projectUuid`；`GET /openapi/session/:sessionId` 支持以 `afterSeq` 增量查询，项目 URL 指向 `https://www.liblib.tv/canvas?projectId=...`。这形成“外部 Agent 发起 → 查询进展 → 返回项目画布”的公开协议闭环。[公共客户端代码](https://github.com/libtv-labs/libtv-skills/blob/c609246c1eca69f6bc129bcbb5d64c36734e4a4a/skills/libtv-skill/scripts/_common.py)、[会话创建脚本](https://github.com/libtv-labs/libtv-skills/blob/c609246c1eca69f6bc129bcbb5d64c36734e4a4a/skills/libtv-skill/scripts/create_session.py)、[进度脚本](https://github.com/libtv-labs/libtv-skills/blob/c609246c1eca69f6bc129bcbb5d64c36734e4a4a/skills/libtv-skill/scripts/query_session.py)

公开 Skill 依赖 `LIBTV_ACCESS_KEY` 和官方远程服务；MIT 许可覆盖该仓库，未取得平台服务端或完整编辑器的自托管证据。对 Agenvas 的可借鉴点是外部自动化入口与画布接管衔接，核心业务仍应遵循本项目自托管与权限约束。[官方 README](https://github.com/libtv-labs/libtv-skills/blob/c609246c1eca69f6bc129bcbb5d64c36734e4a4a/README.md)

## 未核实与资料冲突

1. **分镜组及结构化脚本**：二手资料有分镜表、宫格打组、批量生成与 4K 分镜图等细节，本次未取得可读的官方指南正文，不据此填写“已实现”。后续可按镜头排序、时长、画面描述、运镜、角色绑定、批量生成、局部重做逐项体验。
2. **角色/场景一致性**：部分官网快照出现角色造型室入口；仅有入口不足以证明跨镜头角色绑定、服装状态、音色复用或一致性质量。本次不作效果保证。
3. **声音、字幕、口型与剪辑**：官网音频与智能剪辑入口可证；语音克隆、自动字幕、口型同步、多轨混音、时间线持续编辑、合成导出的时长和分辨率限制均待一手文档或实测确认。
4. **复用、社区与协作**：官网展示创作者作品、精选画布和带“一键复制”名称的作品，不足以证明完整工作流的发布/导入协议、参数模板、团队实时协作、评审评论或权限粒度。
5. **官方帮助资料可达性**：官网发布的脚本指向 [飞书指南入口](https://liblibai.feishu.cn/wiki/Loxfw6XHziYRk0kKzdjcFfp9nhb) 与 [另一帮助入口](https://resonate.feishu.cn/wiki/GwRpwYzGSiESYtkLPBEcvQ4knHd)，本次浏览工具不可读，直连发生重定向循环；[官方更新页](https://www.liblib.tv/feature-updates) 只返回页头，未获取更新正文。
6. **官网表述内部不一致**：产品营销页把 3D-BOX 卡片的说明写成片名设计与文字动效，与 [3D-BOX 专页](https://www.liblib.tv/3d-box) 的空间/站位/运镜介绍不同。本记录对 3D 能力采用专页表述，未把该卡片当作具体交付证据。[营销页](https://www.liblib.tv/ai-video/rival?sourceid=040154)

## 对 Agenvas 比较的使用方式

从已核实资料看，LibTV 值得参考的是“画布组织 + 剧本/导演辅助 + Agent 编排 + 后续编辑 + 外部自动化接回项目”的整体创作路径。Agenvas 是否存在差距，应结合现有代码逐项确认，不能把已有节点生成、素材引用、Agent 审批和 API 基础重复列为缺失。

优先检查用户能否持续推进一个完整项目：结构化镜头清单与局部重做、角色/场景资产跨镜头绑定、整片预览与可恢复的编辑工程、工作流复用与操作历史。这些是比较维度和候选改进方向；其中尚未取得官方细节的项目，不应写成已验证的 LibTV 独有能力。

本记录未运行测试，未修改产品、合约或验收状态。Skill 仓库核对快照为 `c609246c1eca69f6bc129bcbb5d64c36734e4a4a`（公开提交时间 2026-03-18）；产品网页为核对日在线内容，可能随发布变化。
