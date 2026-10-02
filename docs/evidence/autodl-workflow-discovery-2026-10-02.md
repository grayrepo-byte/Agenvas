# AutoDL 工作流发现、发布定义与分辨率核对

日期：2026-10-02。实际实现与定向检查记录；不作为真实付费生成已完成的证明。

## 行为与契约

- 官方公开目录在获取时有 17 个目标；内置 14 个视频工作流的全部精确分辨率枚举与官方一致，没有发现遗漏。原始来源、完整档位表与另外 3 个协议差异见 [目录研究](../research/autodl-workflow-catalog-2026-10-02.md)。匿名读取公开目录和详情，没有使用凭证或生成请求。
- 管理员可刷新目录、读取官方输入规则、离线导入官方详情或手工配置同协议定义。导入是候选，只在「发布能力」/「保存能力」后写入新的版本；失败保留草稿。读取同一目标保留仍有效的已发布档位与分档价格。
- `settings.workflowDefinition` schemaVersion=1 保存完整受限输入定义；档位允许三至四位正整数加 p，每个档位/比例只有一个精确枚举。新工作流 ID 与档位不再受内置枚举限制。供应商图、节点映射、脚本、地址不保存或执行；固定协议之外的输入拒绝导入。
- `GET /api/v1/settings/autodl-workflows` 返回目录候选，`POST /api/v1/settings/autodl-workflows/preview` 返回数据定义；管理员权限、写请求 CSRF、no-store 与固定地址/响应上限生效。目录读取无 API Key，未提交生成。
- 自定义定义沿用能力 JSON 和不可变能力版本，已提交任务使用原定义、连接和精确枚举继续轮询；排队任务仍受原有能力变更预检约束。无需数据库迁移或 jOOQ 再生成。OpenAPI 与生成 TS、规格 6.12、ADR 0024、接入说明和任务清单同步。

## 实际运行的检查

1. 后端 57 项定向单元测试通过：AutoDlWorkflowDiscoveryTest（19，含 14 条官方输入规则快照）、AutoDlWorkflowsTest（17）、AutoDlClientTest（7）、两个 VideoGenerationParametersTest（5）、MediaCapabilityConfigurationTest（7）、MessageCatalogTest（2）。覆盖精确枚举全量导入、新目标、离线回退、目录失败、无凭证请求、未知输入协议、禁止图/地址、槽位编号和定义版本。
   `mvn -q -f backend/pom.xml -Dtest=AutoDlWorkflowDiscoveryTest,AutoDlWorkflowsTest,AutoDlClientTest,VideoGenerationParametersTest,MediaCapabilityConfigurationTest,MessageCatalogTest test`
2. 2 项 PostgreSQL 17 集成用例通过：AutoDlVideoPostgresIT 和 AutoDlResolutionPricingPostgresIT。真实数据库、假 AutoDL HTTP；验证新 ID/720p/18 秒定义发布与提交、提交后改定义继续按原版本归档，匿名及非管理员拒绝、CSRF 拒绝、预览响应、多分辨率价格快照与移除档位拒绝。保留原 UNKNOWN、取消、归档重试等回归断言。
   `mvn -q -f backend/pom.xml -Dit.test=AutoDlVideoPostgresIT,AutoDlResolutionPricingPostgresIT test-compile failsafe:integration-test failsafe:verify`
3. 前端 64 项定向测试通过：AutoDlWorkflowFields（5）、MediaSettingsPage（19）、MediaDraftEditor（37）、mediaPricing（3）。覆盖目录新增候选、离线/手工定义、错误保留与同目标刷新保价、新目标的实际发布 payload、未来 2160p 档位在画布选择及原价格回归。
   `pnpm --dir frontend test src/features/settings/AutoDlWorkflowFields.test.tsx src/features/settings/MediaSettingsPage.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/shared/mediaPricing.test.ts`
4. OpenAPI 生成 TS、TypeScript 类型检查、涉及文件的定向 ESLint、四语言资源检查、前端构建、差异空白检查通过。Vite 仍提示现有大块超过 500 KiB；没有为此改动代码拆包。后端编译包含在上述 Maven 检查中。

## 未验证范围

未运行全量测试、浏览器端到端、真实付费生成、部署或迁移。官方目录和详情为网站使用的第一方接口，没有稳定性承诺，离线导入与手工定义可作回退。公开元数据的一致性不能证明逐档位实际生成；本次新目标与 2160p 测试都是协议夹具。定义版本固定本地输入契约，不保证供应商远程图不会变动。视频参考、自动音频时长与 IndexTTS 音频输出仍需要专用协议适配。

## main 提交前隔离复验

2026-10-02，以 main `c6c5b44` 为基线，仅叠加本次 AutoDL 修改；与其他未提交界面改动重叠的画布编辑器、测试和翻译文件按内容拆分。隔离副本中的 64 项前端测试、57 项后端单元、类型检查（包含在构建中）、前端构建和四语言检查通过；未重新运行 PostgreSQL 集成，沿用本次实现已通过的 2 项结果。其他界面修改保留在原工作区。本次未运行全量、付费生成、部署或推送。
