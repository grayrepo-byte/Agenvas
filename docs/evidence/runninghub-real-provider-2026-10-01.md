# RunningHub 真实 Provider 验证

日期：2026-10-01。用户提供 Key 并明确要求真实验证。本轮调用官方 `https://www.runninghub.ai`，使用隔离 PostgreSQL 17.11、应用的生产 Client / Adapter / Task / Asset 服务和私有临时存储；没有配置或修改现有部署的账户、项目或凭据。Key 不写入代码、Git、URL 或日志。

## 实际结果

两次生成均使用同一张程序生成的 1280 × 720 几何参考图，明确覆盖提示词、时长 `"4"`、`480p` 和 `16:9`。仅各提交一次生成，没有保留实例、重新生成或自动提交重试。URL 上传和 FILE_NAME 上传均真实成功。

| 类型 | 真实目标 ID | 原 Provider taskId | 结果 |
| --- | --- | --- | --- |
| 工作流 | `2037454919065673729` | `2105467637658337282` | SUCCESS；MP4，864 × 496，4042 ms，667587 bytes |
| AI 应用 | `2039199752025280513` | `2105467643571011585` | SUCCESS；MP4，864 × 496，4096 ms，609575 bytes |

通过真实 Task 受理、精确图片版本引用、素材上传、V2 提交、原 ID 查询、私有清单持久化、下载、FFprobe / FFmpeg 解码与归档、不可变版本创建和节点选用。两个 Task 最终 SUCCEEDED、selected=true，随后 Worker 再运行没有重新提交或重复查询。

Provider 的 `consumeMoney` 均为 null。工作流返回 consumeCoins=5、taskCostTime=225、thirdPartyConsumeMoney=0.4；应用分别为 3、143、0.4。GPU 时间与视频时长分别记录。调用前后账户的 USD 余额差额为 0.800，积分差额为 8，与两份 usage 一致；USD 与积分不换算或合并。结束时账户活动任务数为 0。

生成文件保留在本机忽略目录 `backend/data/assets/runninghub-real-2026-10-01/workflow.mp4` 和 `app.mp4`，不提交媒体字节。SHA-256：

- 工作流：`592c07b8e3479df5bab8908f8b641243460af381be447b0f938965abcfcfaf0e`
- 应用：`dc09fecbf200900e15a065e5f85f148c7f70577fb902f2605aa1544d3d8b9149`

## 真实调用发现与修复

- 工作流的 `choose video file to upload` 是不支持的界面辅助映射；导入器现在跳过并提示核对，保留实际 `file` 输入。
- 应用页面的 LIST 使用 `[选项数组, 控件设置]`；导入现在保留枚举值和当前选择。应用的生成节点为 15、首个图片节点为 12，与工作流的节点 1 / 2 不同。
- Bearer-only 的应用自动发现返回 `code=500 / UNKNOWN_ERROR`。本轮从公开页面核对字段，采用选定的脱敏输入 JSON；没有把 Key 放进 query。该自动发现限制仍存在。
- V2 上传返回 `rh-hk-images-switch.xiaoyaoyou.com`，初始名单拦截了返回值。用户随后明确取消域名白名单，API 地址、上传返回值和下载均不按域名列表限制；仍保留 URL / DNS 地址检查、无重定向和无凭据下载。设置改为 HTTPS API 地址输入框，规格和 ADR 同步。
- 首次 legacy 上传超时，尚未调用生成接口，Task 明确 FAILED / RUNNINGHUB_UPLOAD_FAILED。将鉴权和 fileType multipart 字段放在文件部分之前后，真实上传成功。失败回执留作核对；只在确认尚未提交生成时重新测试。
- 真实 usage 的三项非空数值为数字字符串，初始解析丢弃了这些字段。现已支持有界、非负的数字字符串。原测试库的历史 Task 没有原地改写；修复后仅再次查询上述原 taskId，通过生产解析器验证完整费用，不重新生成。

## 实际检查

付费链路检查 `RunningHubRealProviderIT`：1 项通过，后端 verify 成功。运行命令使用私有目录环境变量，Key 只从目录内文件读取：

```sh
AGENVAS_RUNNINGHUB_REAL_CALLS=true AGENVAS_RUNNINGHUB_REAL_DIRECTORY=<private-directory> ./mvnw -Dtest=RunningHubImportServiceTest,RunningHubDefinitionTest,RunningHubClientTest -Dit.test=RunningHubRealProviderIT verify
```

最终修复后的定向检查：

```sh
AGENVAS_RUNNINGHUB_REAL_CALLS=true AGENVAS_RUNNINGHUB_REAL_DIRECTORY=<private-directory> ./mvnw -Dtest=RunningHubImportServiceTest,RunningHubDefinitionTest,RunningHubClientTest,RunningHubAdapterTest -Dit.test=RunningHubPostgresIT,RunningHubRealUsageIT verify
```

22 项单元测试、8 项 PostgreSQL + 假服务集成测试、1 项原真实任务费用查询通过，0 失败 / 0 错误，verify 成功。费用检查只查询已保存 ID，没有生成方法。

真实检查默认不启用。移除环境变量后定向运行两项真实 IT：均 skipped，2 项费用解析单元测试通过，verify 成功。付费测试在提交前安装回执文件，存在回执时拒绝重新提交；新一轮付费检查必须使用独立私有目录。公开应用输入 fixture 位于 `backend/src/test/resources/runninghub/seedance-app-inputs.json`，不含凭据。

前端：`MediaSettingsPage.test.tsx` 17 项通过；OpenAPI 生成 TS、TypeScript / Vite 生产构建与两个修改文件的 ESLint 通过。Vite 大于 500 kB 的既有产物提示仍存在。最终 `git diff --check` 通过；对 tracked diff、未跟踪源码、验证日志及 Surefire / Failsafe 报告扫描，Key 命中数为 0。

## 验证边界

仅证明上述两个目标和参数组合。其他工作流、AI 应用、真实音频 / 视频输入、混合输出、24 小时结果有效期和过期 URL 恢复未真实验证。浏览器端到端、全量测试、升级演练与部署未运行。能力版本固定本地契约，不冻结 Provider 内部工作流。取消不保证外部停止或退款。

本轮没有新增合约形状或迁移；API origin 说明与生成 TS 同步。全部实现仍在当前工作区，未提交或部署。验证结束后移除私有明文 Key 和加密主密钥临时文件。
