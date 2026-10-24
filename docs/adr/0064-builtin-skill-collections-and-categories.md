# ADR 0064：内置 Skill 多来源目录与用途分类

接受（2026-10-24）。按用户要求导入 [MiniMax-H3/skills](https://github.com/MiniMax-AI/MiniMax-H3/tree/d21241f0a4b3acbb34c97dae47fa417b7065e438/skills)，并解决内置目录平铺难筛选的问题。目录按用途分类，来源与许可证单独展示；分类属于当前目录元数据，不修改不可变发布内容、既有绑定或 Run 快照。

## 本地集合与不可变身份

保留原 Drama Skills 的 key、正文、附件和 bundleHash。增加 `minimax-h3/agenvas-canvas-v1/<name>` 集合，固定上游 commit `d21241f0a4b3acbb34c97dae47fa417b7065e438`，覆盖上游九个 Skill：H3 提示词、极简产品广告、3D 动画、品牌宣传、音乐 MV、双人游戏开场、纸拼贴讲解、纸艺定格科普、手绘实拍融合。运行只读随应用打包的文本，不访问 GitHub、安装外部运行时或自动更新模型。

主文件重写为中文发现描述与 Agenvas 工作流；按需保留 H3 两份原格式指南、双人游戏两份模板及其他创作参考节选。Hub 工具、专属选项卡、自动重生成和不可用后期操作不作为可执行步骤。画布读取、文字 CAS、能力发现、媒体审批、UNKNOWN 与本地合成规则继续适用；五种 H3 模式不代表已配置 Provider 支持全部模式。不能以提示词自检冒充媒体实测。

## 分类与接口

用途为 `STORY`（短剧叙事）、`ADVERTISING`（品牌广告）、`ANIMATION`（动画创作）、`EXPLAINER`（知识讲解）、`MUSIC`（音乐 MV）、`PROMPT`（提示词）。个人与已退役 Skill 的分类/来源为空。分类调整只影响发现，不重建发布版本。

`GET /api/v1/skills` 增加可选 `category`，与标题/描述搜索共同在数据库中、游标分页及总数计算前应用。指定分类只返回该类当前内置 key；省略分类包含全部当前内置与个人内容。响应增加 `category`、`builtinSource`；来源含名称、仓库、固定 revision、许可证及许可链接。元数据来自 manifest，不增加数据库列或迁移；OpenAPI 与生成 TypeScript 同步。

技能库、Agent 本次选择、默认版本选择与视频提示词优化复用现有 shadcn 分类控件及分组。查询变化使用独立分页缓存，保留已打开草稿、已选 Skill 和固定版本；筛选本身不生成。回收站沿用个人内容规则。

## 第三方许可

上游 README 指向 [MiniMax H3 Community License](https://huggingface.co/MiniMaxAI/MiniMax-H3/blob/main/LICENSE)，未发现 skills 专用的宽松许可。打包完整 LICENSE、要求的 NOTICE、改写声明、上游文件摘要和许可文件 SHA-256；MiniMax 内容不重许可为 MIT。许可包含地域、商业使用与再分发条件，UI 与 README 提供明确入口。集成方法不安装 H3 模型、不授予模型使用许可，也不新增远程许可证服务依赖。分发者须按第三方许可处理此集合。
