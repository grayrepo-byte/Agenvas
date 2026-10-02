# AutoDL 多分辨率与分档估算价格（2026-10-02）

## 实现行为

同一 AutoDL 工作流能力可发布多个分辨率并指定默认档位，候选来自既有固定工作流声明。可选列表必须非空、去重、只包含受支持档位，默认值必须在列表中。发布与编辑共用表单；历史能力没有列表时保持原来的单一分辨率，可在编辑时明确开放更多档位。

估算价格区可逐档位配置单价、CNY/USD 和 VIDEO/SECOND 单位。金额仍使用十进制字符串，最多六位小数；零价格与未知费用区分。匹配档位价格优先，否则使用统一价格；两者都缺失显示费用未知。清空档位价格后保存即移除该覆盖价格。

画布尺寸面板提供已发布分辨率选择，切换比例保留所选分辨率，预估费用随档位即时变化。自动保存失败或冲突仍沿用既有草稿恢复交互。管理员移除已选档位后保留草稿值，界面提示且服务端拒绝运行；用户明确选择可用档位后恢复。显式切换到不兼容能力前提示分辨率重置与参考移除影响。

任务冻结有效档位、精确供应商分辨率枚举及匹配的 mediaPricing。配置后续改价或移除档位不改变原任务及取消释放的账本估算。当前只有 AutoDL 协议声明可选视频分辨率，其他固定视频适配器继续使用原来的统一价格。

## 文件与合约

- 前端：AutoDlWorkflowFields、VideoResolutionPricingFields、CapabilityConfigurationFields、MediaSettingsPage、MediaDraftEditor、shared/autodlWorkflows、shared/mediaPricing 及四语言文案。
- 后端：VideoGenerationParameters、AutoDlWorkflows、MediaCapabilityConfiguration、MediaCapabilityService、DirectMediaTaskService。
- 合约：contracts/openapi.yaml 新增 settings.videoResolutions、settings.pricingByResolution 及草稿 parameters.videoResolution；schema.ts 通过 OpenAPI 重新生成，无手工编辑。
- 同步规格 6.12、ADR 0019/0024、接入说明和任务清单。沿用既有 JSON 持久化，无 Flyway、数据库结构或 jOOQ 变更，无新增依赖。
- 升级需同步前后端；旧能力仍使用原档位和统一价格，旧任务缺失新增参数时继续使用已冻结的供应商枚举，无批量改写或重新提交生成。

## 实际检查

- `pnpm --dir frontend test src/features/settings/MediaSettingsPage.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/shared/mediaPricing.test.ts`：57 项通过。覆盖多档位发布、独立价格保存、画布选择与保存、比例切换保留档位、价格精确计算、移除档位后的阻断和恢复、未知与零价。
- `mvn -q -f backend/pom.xml -Dtest=AutoDlWorkflowsTest,MediaCapabilityConfigurationTest,UsageMediaPricingTest,AutoDlResolutionPricingPostgresIT,AutoDlVideoPostgresIT test`：27 项单元测试及既有 AutoDL 假 HTTP / PostgreSQL 集成用例通过；新增集成用例首次因测试 bootstrapSecret 长度不足而未启动，修正测试配置后以 `-Dtest=AutoDlResolutionPricingPostgresIT` 重跑通过，共 2 项 PostgreSQL 集成用例通过。
- `mvn -q -f backend/pom.xml -Dtest=VideoGenerationParametersTest test`：5 项通过（既有包 2 项、新增领域包 3 项），覆盖冻结档位、历史/其他视频协议参数保持缺省、非法值拒绝及既有比例校验。
- PostgreSQL 17.11 专项用例确认默认值受理、显式档位优先、精确供应商枚举、按秒单价快照、改价后的原估算取消释放、移除档位保留草稿并拒绝运行。没有发出远程生成请求。
- `pnpm --dir frontend api:generate`、TypeScript 检查、涉及文件的 ESLint 零警告、四语言文案检查及 Vite 生产构建通过；构建仍报告大于 500 kB 的 chunk 提示。
- `git diff --check` 通过。保留工作区原有界面相关改动，本次未修改依赖或生成数据库源码。

## 未验证范围

未运行全量测试、真实 AutoDL/其他 Provider 付费生成、浏览器端到端或生产部署。管理员设置仍是估算，不获取供应商分辨率/峰谷报价或实际账单。其他固定视频协议的分辨率选择及分档价格尚未开放。
