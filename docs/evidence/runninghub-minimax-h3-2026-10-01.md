# RunningHub MinimaxH3 AI 应用真实验证

日期：2026-10-01。用户指定改用 [MinimaxH3多参生视频 量化加速V3版](https://www.runninghub.ai/zh-cn/ai-detail/2084320751339032577)，并沿用已授权的真实 API 验证。使用隔离 PostgreSQL 17.11 和私有临时凭据文件，没有修改现有部署的账户、项目或能力。Key 未写入代码、Git、URL 或验证日志。

## 请求与费用

实际 API 目标 `2084320751339032577`，提交路径 `/openapi/v2/run/ai-app/2084320751339032577`，原 Provider taskId **`2105502984593260545`**。仅提交一次，未调用旧 Seedance 样例、保留实例或重新生成。上传同一张合成几何图片，未使用个人素材。

| 参数 | 真实映射与值 |
| --- | --- |
| 提示词 | `150.value`：红色几何球体在蓝色底座上缓慢旋转，静态相机，无人物、无文字 |
| 时长 | `186.value`：`"5"` |
| 比例 | `115.aspect_ratio`：`16:9 (Widescreen)` |
| 百万像素 | `147.value`：`"0.2"` |
| 采样步数 | `192.value`：`"8"` |
| 参考图片 | `141.image`：真实 FILE_NAME 上传；其他 5 张图及 3 个音频输入显式为 `None` |
| 可选节点 | 高画质参考、参考视频、提示词优化、补帧、仅 ZIP 输出均关闭，LoRA 强度为 0 |
| 实例 | `default`，不使用个人队列，不设置 retainSeconds |

参考视频加载节点的官方样例保持不变，参考视频开关关闭，遵循当前应用说明“视频不能删”。页面元数据由公开 DOM 中 `__NUXT_DATA__` 核对；精简 fixture 的输入值是本轮测试值，不是原始页面默认值。素材字段候选仍不保留远程默认文件。

Provider 最终 SUCCESS，usage：consumeMoney=null、consumeCoins=26、taskCostTime=128、thirdPartyConsumeMoney=null。账户前后差额 **26 RH币、0.000 USD**，结束时活动任务数为 0。金额 null 保持未知；USD 零差额来自账户核对，不能把 usage 的 null 推断成零。本应用此次 128 秒是算力运行时间，视频实际时长约 5.17 秒。

## 结果与归档修复

Provider 返回节点 `155` 的 MP4 与节点 `157` 的附带 ZIP。即使“仅 ZIP 输出”关闭，ZIP 仍返回。原适配器拒绝 ZIP，首次付费 IT 的本地 Task 因查询重试耗尽而 BLOCKED / PROVIDER_POLL_RETRY_EXHAUSTED，**首次付费全链路检查失败，不能记为通过**。

修复后将 ZIP 作为附带归档文件跳过，不下载、解压或创建产物；仍必须有合法映射的主媒体结果，未知非 ZIP 类型、错误映射与超量媒体继续拒绝。COMBO 枚举导入也补充支持 `["COMBO", {"options": [...]}]`，保留比例的实际 Provider 值。

初始隔离测试库已经销毁，连接该库的 Worker 恢复尝试因数据库不可用失败，没有重新生成。后续在新的隔离 PostgreSQL 中**只查询原 Provider ID**，经过生产 Client、结果清单解析与 Asset 的任务视频归档方法完成下载、FFprobe / FFmpeg 解码和海报提取。没有重建或宣称原本地 Task 已恢复 SUCCEEDED，也没有修改其冻结能力版本。真实结果归档 IT 不调用上传或生成方法。

归档 MP4：**608×352、5167 ms、162010 bytes**，SHA-256 `4cb36f5d5f6889c5f71c153c31b15ac57c00c74d5bca5b6723b5d7fc8acf99f3`。媒体和脱敏回执保留在本机忽略目录 `backend/data/assets/runninghub-minimax-h3-2026-10-01/`。ZIP 不保存。

## 配置与检查

新增配置数据位于 `backend/src/test/resources/runninghub/minimax-h3-app-inputs.json` 和 `minimax-h3-app-settings.json`。后者是 `settings.runningHub` 的已整理参考图生视频配置，必需一张图，开放提示词、时长、比例、百万像素和高级采样步数；关闭的输入为固定映射。本轮只真实验证 5 秒 / 0.2MP / 8 步 / 16:9。其他配置范围来自页面说明或管理员配置，不代表每种组合均已实测。

`RunningHubRealProviderIT` 的付费样例已改为此应用，一次只提交一个生成；已有回执拒绝再次提交，默认不开启。首次执行：13 项单元通过，1 项付费 IT 失败，原因如上。修复后的原结果检查：

```sh
AGENVAS_RUNNINGHUB_REAL_RESULT_ARCHIVE=true AGENVAS_RUNNINGHUB_REAL_DIRECTORY=<private-directory> ./mvnw -Dtest=RunningHubImportServiceTest,RunningHubDefinitionTest,RunningHubAdapterTest,RunningHubClientTest -Dit.test=RunningHubPostgresIT,RunningHubRealResultArchiveIT verify
```

**25 项单元、8 项 PostgreSQL / 本地假服务集成、1 项原真实结果查询/下载/归档通过**，verify 成功，日志 `/tmp/agenvas-runninghub-minimax-final.log`。没有追加付费请求。

随后为假服务添加附带 ZIP，覆盖私有清单、断点归档、原任务 ID、节点选用与零 ZIP 下载。移除所有真实调用开关运行：

```sh
./mvnw clean -Dtest=RunningHubImportServiceTest,RunningHubDefinitionTest,RunningHubAdapterTest,RunningHubClientTest -Dit.test=RunningHubPostgresIT,RunningHubRealProviderIT,RunningHubRealResultArchiveIT verify
```

**25 项单元、8 项 PostgreSQL / 假服务通过，2 项真实 IT skipped**，verify 成功，日志 `/tmp/agenvas-runninghub-minimax-opt-in.log`。OpenAPI 说明同步，生成 TS、前端类型检查通过；无接口形状或 Flyway / jOOQ 变化。差异与凭据扫描通过。

未运行全量测试、浏览器端到端或部署。修复后没有再次付费运行完整新 Task 以验证最终选用；最终 Task 完成和选用由上述假服务 PostgreSQL 回归覆盖，真实补验范围为原 Provider 查询与 Asset 归档。多参考图、音频 / 视频输入、其他时长 / 尺寸 / 步数未真实验证。验证后清理私有明文 Key、加密主密钥和原始账户记录。
