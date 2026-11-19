# 30项后端架构迭代：最终验证

实际完成30项独立问题：首批5项，后续25项逐项实施、定向验证并独立审查。原首批30标签（代码、审查、测试、文档混排）已纠正；初始7提交跨度27分01秒不代表30次迭代。测试、审查、修复和本文均不增加轮次。完整目标、实施提交、验证和风险见[逐项记录](backend-extension-iterations.md)。

本地全量验证针对行为提交`1d6bd5f6`及仅一处Java折行的`4000b1ac`：单测在前者执行，四组集成在后者执行，格式提交逐字核对没有其他变化。本地完成时间：2026-11-18T20:02:06Z。后续提交只增加验证文档，公开契约、Flyway、生成代码、依赖、前端及部署文件与基线`104483fd`无差异。

## 完整检查

|检查|实际结果|范围|
|---|---|---|
|后端单元|2479项：2477通过、2配置跳过、0失败/错误|293个JUnit类|
|后端集成|724项：716通过、8配置跳过、0失败/错误|真实PostgreSQL17.11，185个Failsafe类|
|集成分片覆盖|四组47/46/46/46类，合集恰好185且无重复/遗漏|按当前CI脚本发现全部Failsafe命名模式|
|Java格式|1108文件完整检查通过，无路径过滤；修正后的冷缓存检查0跳过|源代码与测试|
|jOOQ漂移|通过，schema/生成源无差异|隔离一次性PostgreSQL，真实generate-sources|
|CI规划/分片/门禁Python|37项通过|当前CI脚本测试|
|Compose兼容/配置|3项及配置脚本通过|镜像、源码、Mock环境|
|安装凭据|8项通过|真实隔离卷/数据库，首次启动、重启、冲突与只读/非root|
|差异/基线|差异检查通过，6分支对main/develop的12次模拟合并均无冲突|未实际合并或更改基线|

各定向用例与全量存在重叠，不把它们相加。全量XML逐类汇总并核对分片合集，失败为0；跳过不计通过。

单测跳过本地未配置的DepthAnything实际权重，以及未提供的浏览器Director WebM夹具。集成跳过范围：RunningHub真实归档/提交/费用3项、真实MCP图像1项、AutoDL1项、既有私有媒体合成1项、外部参考站点/GitHub2项。真实付费供应商、真实云存储/NAS、浏览器录制样本、全部编码组合、极大目录内存/吞吐、原生推理并发关闭压力均未在本地全量中验证。配置权重的真实DepthAnything图片推理由最终服务镜像CI单独执行，不以普通Mock/合成HTTP替代真实供应商兼容证明。

在backend目录使用当前JDK21和仓库wrapper，等价公开命令如下；依赖镜像、本机路径及私有原始日志不入Git：

```sh
./mvnw -B --no-transfer-progress test
integration_classes=$(python3 ../.github/scripts/ci.py shard 0 4)
./mvnw -B --no-transfer-progress -Pci-integration "-Dit.test=$integration_classes" verify
./mvnw -B --no-transfer-progress spotless:check
GITHUB_RUN_ID=local-final GITHUB_RUN_ATTEMPT=1 bash ../.github/scripts/check-jooq.sh
```

四个集成分片分别使用0至3和独立工作树；没有用H2或skipTests替代。项目检查运行当前`.github/scripts`单测、`deploy/test-compose-check.py`、`deploy/check-compose.sh`、`deploy/test-installation-secrets.py`。同时最多两个Maven进程，避免混写target与过度争用。

最终托管检查以[草稿PR#59当前检查](https://github.com/grayrepo-byte/Agenvas/pull/59/checks)及其说明的确切head/run为准，包含前端格式、类型生成漂移、类型检查、lint、测试/构建、全后端四分片/单测、源秘密扫描、jOOQ、Compose/安装、amd64镜像/真实权重和门禁。阶段性CI163只覆盖至第27项，不作为全部30项的证据；发布任务必须跳过。交付前核实最新head，不将父分支绿色结果外推。

## 发现并处理的问题

- 首批全量的FFmpeg帧数问题以显式输出帧率修正（`2092bbbc`）；OpenAI发布声明和LOCAL测试替身按新契约更新（`8ce5605d`、`9140652e`），没有恢复生产ID白名单。均有先失败、修后通过的记录，不增加轮次。
- 最终安装检查首次1错，原因是隔离检出的公开入口脚本为0700，容器UID100不能读取。仅将验证副本恢复为CI使用的0755，最小读取探针1→0，完整8项通过；源码内容、Git可执行标志和凭据权限不变。
- 整体审查新增一项MCP正则Minor：任意已编译pattern的公开含义与整串匹配不同。选择显式注册三种已发布颜色/摘要表达式，未知表达式启动拒绝；保留运行匹配、尾部换行拒绝和公开JSON。17单元+13真实PG通过。原限范围格式检查未报告选中文件数量，完整检查发现一处换行；`4000b1ac`仅格式校正并经无缓存1108文件检查，报告已更正。

## 独立审查与边界

所有任务均通过规格/质量审查。整体集成审查覆盖`104483fd..4e302181`，0严重、0重要问题；新增正则Minor经同一最终修复波及限定复核关闭，最终`4000b1ac`无新增问题。整体审查是跨模块与具名代码/证据核查，不宣称逐行认证每个文件或替代实际全量测试。

字幕清理事件新增`subtitleId`；普通Task与清理继续保留不同的费用提示。审计清理的REQUIRES_NEW明确原本逐批提交合同，当前生产调用无外围事务；42项日志PG测试含第1001条故障后前1000条已提交、末条可续清的真实证明，不宣称额外测过外围事务调用。供应商原字节/全部音轨、刷新和下载重试不重投按issue#42及现行ADR0066维持，补证使用落库资产、真实FFmpeg/ffprobe和合成HTTP。

本次自主决定与代价：

1. 云技能脚本不可取得时使用等价私有记录流程；代价是原脚本的自动检查需以显式记录/差异包核对。
2. 以当前代码保留审计停止→日志→账本→诊断删除顺序，不按计划概念箭头重排SQL；若需求实际要求重排，需另行验证兼容性。
3. 正则注册限定现有三种表达式，保持全部现有输入行为；新pattern需显式扩充校验器和测试，不能只改JSON。
4. 接受测量值仅工厂构造时的已有列表复制，以及现有签名前校验测试；新构造入口须补复制，后者少一项直接mock断言但相邻证据保留。
5. 临时复制大私有图的优化延后，先做分配测量；大目录临时内存成本仍存在，当前投影无泄露。

## 可核对的扩展收益

[新增供应商前后文件对照](backend-extension-provider-example.md)使用SYNTHETIC_IMAGE：原中央声明/超时表、Task/Usage错误白名单及Worker/Task刷新/诊断分支，现在由适配器自带声明、预检、失败分类、原ID刷新和受限诊断契约承担，两项真实Spring/PostgreSQL链路证明扩展有效。它是合成传输，不是新商业供应商接通声明。新平台仍需enum/OpenAPI/Flyway/UI，特殊输入、真实HTTP和凭据仍由实际协议所有者实现。同协议RunningHub目标原本已配置驱动，未重复计收益。

其他具体收益包括显式存储/提示词来源注册，固定执行配置与只读结果身份，模型元数据/Agent工具/MCP路由及schema明确契约，三个文字purpose、工作线程和私有媒体目录共同生命周期。批读证明数据库往返增长受控：画布/资源1→16图为6→6/4→4；导出初次同形状1→12卡由39→204降为30→30；目录增加连接+41能力由23→106降为1→1。它们证明查询往返改善，不是吞吐/延迟百分比或恒定内存承诺。

交付为依次基于前一分支的六个草稿PR：[54](https://github.com/grayrepo-byte/Agenvas/pull/54)、[55](https://github.com/grayrepo-byte/Agenvas/pull/55)、[56](https://github.com/grayrepo-byte/Agenvas/pull/56)、[57](https://github.com/grayrepo-byte/Agenvas/pull/57)、[58](https://github.com/grayrepo-byte/Agenvas/pull/58)、[59](https://github.com/grayrepo-byte/Agenvas/pull/59)。没有合并到主分支、发布或部署，没有加载第三方代码或恢复HTTP/私网地址限制。

提交统计：相对基线共70提交（包含本文、测试、修复与记录，不是70轮）。首个实施提交为2026-11-18T14:54:35Z；至本地全量完成2026-11-18T20:02:06Z跨度5小时7分，不推算首次提交前的分析耗时。最终托管完成时间及确切head/run见PR说明。
