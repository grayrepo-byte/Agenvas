# 图片中转站真实测试与 Nano Banana 修复

2026-09-30（Asia/Shanghai），在本地 Compose 应用中使用管理员已有的 grsai 连接执行真实付费调用。Nano Banana 的接口路径和画幅参数兼容问题已修复并部署，文字生图和单张参考图编辑均成功；GPT 本轮调用在 5 分钟后超时，保持 UNKNOWN，不能据此声称本轮生成成功。

## 配置与原因

- GPT 能力显示名称为 `gpt-image-2`，实际配置模型为 `gpt-image-2.5`，Base URL 为 `https://grsai.dakka.com.cn/v1`。
- Nano Banana 的实际配置模型为 `nano-banana-2-lite`，不是完整版 `nano-banana-2`。原 Base URL 同为 `/v1`；Key 可用。
- 原 Google 客户端使用绝对 `/v1/models/{model}:generateContent`，因此配置的版本/路径前缀会被丢弃。原请求实测 HTTP 404（363 ms）；保持 Key、模型和正文不变，只换 `/v1beta` 后 HTTP 200（8170 ms）。
- 仅修路径仍有画幅问题：`generationConfig.responseFormat.image` 的 `1:1` 被中转站忽略，返回 1408×768。只改为 `generationConfig.imageConfig` 后 HTTP 200（6159 ms），返回 1024×1024。

现在客户端保留 API Base URL 的显式前缀，空配置和裸主机继续使用稳定 `/v1`；`/v1beta` 使用 `imageConfig`，稳定路径使用原 `responseFormat.image`。请求仍使用 `x-goog-api-key`，不增加路径探测、重定向、自动提交重试或其他协议。管理员连接已通过带 expectedVersion 的 API 更新为 `https://grsai.dakka.com.cn/v1beta`，保留原 Key，并创建不可变连接版本 3（连接 CAS version 2）；旧连接和旧任务不改写。

协议参考：[中转站文档](https://grsai.com/zh/dashboard/documents/nano-banana)声明支持 Gemini 格式；[Google 图片生成文档](https://ai.google.dev/gemini-api/docs/generate-content/image-generation)提供稳定 REST 格式。`/v1beta` 和参数差异的结论来自上述真实差分请求，而非仅凭文档推断。

## 应用内验收

独立测试项目：[真实接口验收 2026-09-30](http://localhost:8088/projects/4865ff76-a7a8-4d40-b65b-dc7c0102a7f6)。通过现有认证会话、CSRF、服务端应用 API、持久 Task、Worker、版本与 Asset 路径验证，所有调用日志 `mock=false`。

| 测试 | 任务 | 结果 | 调用耗时 |
| --- | --- | --- | --- |
| GPT 文字生图 | `f372f28d-3a67-4200-9ea4-1adae7066651` | UNKNOWN，`PROVIDER_CALL_TIMEOUT`，无本地结果 | 300042 ms |
| Nano Banana 文字生图 | `07a8c264-9c7e-48a4-aac6-2b1f8ea6d8c5` | SUCCEEDED，选用归档的 1024×1024 JPEG | 5814 ms |
| Nano Banana 智能编辑 | `2d0064bf-3cf1-487e-862a-62a10db96345` | SUCCEEDED，独立派生节点选用 1024×1024 JPEG | 8516 ms |

文字提示为橘猫和蓝色陶瓷杯插画；编辑固定文字生图的精确版本，提交一张归档参考图，要求只把杯子改为红色。检查图片确认杯子变红；来源节点仍选用原版本，派生节点选用编辑结果。这里不承诺模型逐像素保留未编辑区域。

| 归档图 | Asset | 字节数 | SHA-256 |
| --- | --- | --- | --- |
| [文字生图](real-image-relay-2026-09-30/nano-text.jpg) | `a51c3ea5-cad5-367f-831c-43e0a5e21921` | 560041 | `667c7568ea356f28044b5a79eb8c3fedf1238641bf200d3a521f21937017a0c8` |
| [参考图编辑](real-image-relay-2026-09-30/nano-edit.jpg) | `c757e2cf-ad86-32e9-b0fd-a96bb1136b8a` | 399439 | `60f1dbcdcac7460d4f4a73bd6d2349aecbe38f4162bc5e7ee589996313e035c4` |

两张图均通过鉴权 Asset content API 下载，字节数和 SHA-256 与数据库一致；实际解码尺寸为 1024×1024。没有把直接探测的图片冒充应用内成功结果。

GPT 在本轮前有多次 `mock=false` 的成功调用记录（例如 2026-09-30 的 `2aa17a4a-20f8-4151-aa19-8123b9adacb5`，296430 ms），表明既有配置曾跑通。本轮超时只能证明应用未在 5 分钟内拿到结果，不能确定中转站未受理、未完成或未计费；无原请求 ID，不自动重发。未更改 GPT 的模型、连接或超时。

## 代码与检查

涉及 `GoogleNanoBananaClient`、客户端回归测试和 `GoogleNanoBananaPostgresIT`；同步 OpenAPI 的 Base URL 语义说明、生成的 TypeScript 注释、MVP 规格、开发清单及 README。没有新依赖、数据库迁移或新增 API 字段。已有 `/v1` 和裸地址保持原请求协议；此前被忽略的显式路径现在生效，因此既有 Google 连接若误填任意路径，需要修正为供应商 API Base URL。

- 路径回归先红：`./mvnw -q -Dtest=GoogleNanoBananaClientTest#configuredBetaPrefixIsUsedWithoutTryingTheStableEndpoint test`，旧实现 beta 请求次数 0，预期 2。
- 画幅回归先红：同一回归用例增加 `imageConfig` 断言，旧正文未提供该字段，失败。
- 修复后：`./mvnw -q -Dtest=GoogleNanoBananaClientTest test -Dit.test=GoogleNanoBananaPostgresIT failsafe:integration-test failsafe:verify`，7 个客户端测试、1 个真实 PostgreSQL 集成测试，全部通过。集成测试的 Provider 是本地假服务，覆盖持久 beta 连接、两张有序参考图、归档/选用、断线 UNKNOWN 不重提及越权引用阻断。
- 此前同轮运行 `./mvnw -q -Dtest=GoogleNanoBananaClientTest,OpenAiImage2ClientTest test`，7 个 Google、17 个 OpenAI 客户端测试通过；之后只修改 Google 正文，重新运行上述 Google 专项测试。
- `./mvnw -q -DskipTests package` 成功，完成编译与打包，未借此声称全量测试通过。
- 重新从 OpenAPI 生成 TypeScript；前端 `tsc --noEmit` 通过。
- 从既有运行镜像更新应用 JAR，本地 Compose server 更新后 postgres/server health check 通过；数据库和资产卷保留。
- 下载校验：通过应用 API 读取任务/版本/Asset，再下载文件，对照数据库的字节数、SHA-256 与尺寸。

## 限制

未运行全量测试，未测试 Google/OpenAI 官方端点、完整版 Nano Banana 2、4K、14 张参考图、GPT 本轮参考图编辑、实际账单核对或浏览器端到端。没有宣称所有模型、分辨率或并发场景稳定。2026-09-30 后续按用户确认，媒体配置页已移除写死的“未实测”文案，连接只显示“已配置”。API 的通用 `NOT_CHECKED` 字段仍不代表逐配置版本的验证，真实结论以本记录和持久调用日志为准。没有记录 Key、认证 Cookie、主密钥、原始模型响应或私有推理。
