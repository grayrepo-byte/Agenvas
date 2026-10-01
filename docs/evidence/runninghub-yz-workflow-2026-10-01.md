# RunningHub YZ 金鱼工作流真实验证

日期：2026-10-01。用户纠正目标为 [YZ金鱼-MiniMax H3-多参考生视频-优化版](https://www.runninghub.ai/zh-cn/post/2093983063180054529)，本轮使用隔离 PostgreSQL 17.11、生产 Client / Adapter / Task / Asset 服务和私有临时凭据文件。没有修改或部署现有运行系统，没有重新运行旧 Seedance 或新 AI 应用。

## 类型与实际结果

该 post 是 **ComfyUI 工作流**，另有“打开 AI 应用”入口。官方工作流 JSON 接口以 workflowId=`2093983063180054529` 返回 code=0 和实际节点内容，确认本次 API 工作流目标。它与先前编辑器链接 `2105501285645656066` 不同；后者返回 code810，用户随后已纠正，不再作为测试目标。

仅一次生成提交：`/openapi/v2/run/workflow/2093983063180054529`，原 Provider taskId **`2105513865767075841`**。本地 Task **SUCCEEDED、selected=true**，上传、V2 受理、原 ID 查询、私有结果清单持久化、MP4 解码归档、不可变版本创建及节点选用全部通过。后续 Worker 再运行为零，不重复生成或查询。

| 参数 | 真实映射与值 |
| --- | --- |
| 提示词 | `263.text`：合成红色几何球体与蓝色底座，静态相机、轻微运动，无人物或文字 |
| 时长 | `259.value`：`"5"`，保留作者已有帧数换算节点 |
| 比例 / 百万像素 | `252.aspect_ratio`：`16:9 (Widescreen)`；`252.megapixels`：`"0.2"` |
| 第一参考图 | `51.image`：1280×720 合成 PNG，FILE_NAME 真实上传 |
| 未使用的参考 | `49/50/43/19/23.image` 和 `48/14/15.audio` 均显式为 None；没有使用作者的人物样例 |
| 放大 / 附加 LoRA | `283.value`：`"1"`；`128.strength_model`：`"0"` |
| 两阶段采样 | 保留 `261.steps="12"` / `289.step="8"` 及作者的加速 LoRA，不改模型或节点连线 |
| 输出约束 | `214` 主视频；`264` 允许一采预览视频；实际仅返回 `214` 的 MP4 |
| 实例 / 保留 | instanceType=`default`，usePersonalQueue=false，未设置 retainSeconds |

真实文件 **608×352、5167 ms、170294 bytes**，SHA-256 `4769d8f5d82b37708f4c0a937ef2173c0aa525586fbda4ed30109c5e0fbef92e`。视频和脱敏回执保留在本机忽略目录 `backend/data/assets/runninghub-yz-workflow-2026-10-01/`。

Provider usage：consumeMoney=null、consumeCoins=19、taskCostTime=92、thirdPartyConsumeMoney=null。账户前后差额 **19 RH币、0.000 USD**，结束时活动任务数 0，与 usage 相符。92 秒算力时间不等于 5.167 秒的视频时长；null 不转为零，USD 零差额由账户查询确认。

与另一个作者的 [MinimaxH3 AI 应用](runninghub-minimax-h3-2026-10-01.md) 分别提交一次，两个新样例合计 **45 RH币、USD 钱包零差额**。不同工作流、参数或实例的费用不能从这两次样本保证。

## 配置与检查

新增 `backend/src/test/resources/runninghub/yz-minimax-h3-workflow-inputs.json`（选定的脱敏候选）与 `yz-minimax-h3-workflow-settings.json`（版本化能力配置）。同一个固定 RunningHub V2 Adapter 处理新目标，未新增适配器、接口形状或数据库迁移。5 秒限制及数值范围是本次已整理配置，不代表上游全部可用范围。

付费 IT 显式选择一个已核对的目标，默认 MINIMAX_APP，设 `AGENVAS_RUNNINGHUB_REAL_TARGET=YZ_WORKFLOW` 只提交本工作流。回执在上传与提交前安装；存在回执时拒绝再次生成，受理后还在私有目录保存隔离数据库快照，以便调查归档失败。Key 和主密钥不进入代码、Git、URL、调用日志、SSE 或媒体回执。

实际运行：

```sh
./mvnw -Dtest=RunningHubImportServiceTest,RunningHubDefinitionTest,RunningHubAdapterTest,RunningHubClientTest -Dit.test=RunningHubPostgresIT verify

AGENVAS_RUNNINGHUB_REAL_CALLS=true AGENVAS_RUNNINGHUB_REAL_TARGET=YZ_WORKFLOW AGENVAS_RUNNINGHUB_REAL_DIRECTORY=<private-directory> ./mvnw -Dtest=RunningHubImportServiceTest,RunningHubDefinitionTest -Dit.test=RunningHubRealProviderIT verify
```

**26 项单元 + 8 项 PostgreSQL / 假服务通过**（`/tmp/agenvas-runninghub-yz-local.log`）；另 **15 项单元 + 1 项真实付费全链路 IT 通过**（`/tmp/agenvas-runninghub-yz-real.log`），均 verify 成功。两次真实上传为 URL 上传验证与 Adapter 的 FILE_NAME 上传，没有重复生成。

同步最新 main 的个人资源库变化后，RunningHub 专项 **25 项单元 + 8 项 PostgreSQL / 假服务通过，2 项真实 IT skipped**；TS 从合并后的 OpenAPI 重新生成，前端类型检查和 56 项动态表单 / 草稿 / 设置测试通过。没有改写 main 的 V67–V70 / jOOQ 或资源库实现。

最终仅检查真实 IT 默认关闭：9 项定义单元通过，2 项真实 IT skipped；没有再次付费。差异和凭据扫描通过。私有明文 Key、主密钥、原始账户 / 工作流记录和临时数据库快照在完成后清理。

未运行全量测试、浏览器端到端、推送远端或部署。只真实验证上述单图参数组合；纯文字、多参考图、音频 / 视频参考、更长时长、更高像素 / 放大和关联 AI 应用未真实验证。
