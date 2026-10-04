# RunningHub API 研究与接入说明

核实与实施日期：2026-10-01。第 1–9 节记录官方资料、用户示例、第三方 SDK 与原始设计依据；第 10 节记录已实施的固定 V2 接入、动态表单与使用方式。已通过本地假 HTTP Provider 和真实 PostgreSQL 定向验证；随后使用用户提供的 Key，对两份示例分别完成真实上传、生成和归档，见 真实验证记录（开发记录不随源码公开）。

## 1. 用户示例实际使用的协议

通过浏览器读取页面加载完成后的请求代码。详情页的数字是 API 目录记录 ID，不能直接当作 `workflowId` / `webappId`；必须读取页面展示的真实接口。

| 示例 | API 目录记录 ID | 实际提交接口 |
| --- | --- | --- |
| ComfyUI 工作流 | `2039234531057524737`，`apiType=3` | `POST https://www.runninghub.ai/openapi/v2/run/workflow/2037454919065673729` |
| AI 应用 | `2039234279646748673`，`apiType=2` | `POST https://www.runninghub.ai/openapi/v2/run/ai-app/2039199752025280513` |

两个页面均使用 `Authorization: Bearer <API Key>`、JSON 请求体及 `POST /openapi/v2/query` 查询任务。URL 中的 `apiType=2/3` 与请求路径版本不同，不能据此选择 API V2/V3。来源：[工作流示例](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234531057524737?apiType=3)、[AI 应用示例](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234279646748673?apiType=2)。

工作流页面列出 `nodeInfoList`、`addMetadata`、`instanceType`、`usePersonalQueue`、`retainSeconds`、`webhookUrl`。AI 应用页面列出同组参数，但没有 `addMetadata`。`instanceType` 列出 `default` / `plus` / `ultra`。`retainSeconds` 仅企业共享 Key 生效，范围 10–180 秒，任务完成后保留实例以减少冷启动，保留期间另行计费。来源：[工作流请求参数](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234531057524737?apiType=3#submit-request-parameters)、[应用请求参数](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234279646748673?apiType=2#submit-request-parameters)。

**示例缺口：**工作流请求代码的 `nodeInfoList` 是空数组；AI 应用请求代码未包含 `nodeInfoList`，参数表却标为必填。两页将 `usePersonalQueue` 声明为 Boolean，但示例值是字符串 `"false"`。所以这两份详情页不足以恢复特定工作流/应用的完整输入表单，也不能把示例直接视为已验证的请求 Schema。来源仍为上述两份用户示例。

## 2. 参数发现与映射

### ComfyUI 工作流

官方提供 `POST /api/openapi/getJsonApiFormat`，JSON Body 示例为 `{ "apiKey": "…", "workflowId": "…" }`。成功响应的 `data.prompt` 是 **JSON 字符串**；解析后的示例按节点 ID 键控，每个节点包含 `class_type`、`inputs`、`_meta`。这是 ComfyUI API-format 数据，不是包含画布位置的 UI graph。来源：[Get Workflow JSON](https://www.runninghub.ai/runninghub-api-doc-en/api-425761094)。

`nodeId` 是工作流节点号，`fieldName` 是该节点 `inputs` 下的键，`fieldValue` 是覆盖值。官方说明：API-format 中数组值通常是节点连线，不建议修改；前端专用字段和 group 逻辑不能通过 API 使用；API 会重置 seed，需要固定时必须显式放入 `nodeInfoList`。也可以在 RunningHub 编辑器中“导出工作流 API”，作为本地导入依据。来源：[About nodeInfoList](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287464)。

**推断边界：**API-format 可以给出节点、字段和当前值，但上述返回例没有字段必填性、完整枚举、数值范围或控件定义。不能仅凭当前值判断它是提示词、普通字符串、资源引用、模型名称或受约束数值，更不能默认把每个节点输入都向最终用户开放。

### AI 应用

官方提供 `GET /api/webapp/apiCallDemo`，示例查询参数为 `apiKey`、`webappId`，同时展示 Bearer 请求头。响应包含应用名、调用示例与 `data.nodeInfoList`。字段示例包含 `nodeId`、`nodeName`、`fieldName`、`fieldValue`、`fieldType`、`fieldData`、`description`、`descriptionEn`。来源：[Get API call examples for AI application](https://www.runninghub.ai/runninghub-api-doc-en/api-425761097)。

官方响应示例的 `fieldType` 包含 `IMAGE`、`LIST`、`STRING`，`fieldData` 是需要再次解析的 JSON 字符串；LIST 示例携带选项和默认值。官方交互示例按 STRING 文本输入、LIST 下拉、IMAGE/AUDIO/VIDEO 上传控件生成表单。来源：[AI 应用参数发现](https://www.runninghub.ai/runninghub-api-doc-en/api-425761097)、[Advanced Integration Example](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287470)。

**未实测：**没有使用 Key 查询用户示例对应的应用或工作流，因此其权限要求、实际字段、必填规则、元数据完整性和远程变更行为仍待验证。文档提供发现接口不等于所有目标均能无人工确认地导入。官方更新记录还说明，启用加密访问的已发布应用/工作流可需要 `accessPassword`；该密码不应进入可共享的能力定义。来源：[Log of Update](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287462)。

## 3. 任务、结果与归档

V2 提交响应包含 `taskId`、`status`、错误字段、`clientId`、`promptTips`；生成结果提交时可以为 null。页面列出的常见状态是 `QUEUED`、`RUNNING`、`SUCCESS`、`FAILED`。V2 查询以 Bearer 鉴权，Body 为 `{ "taskId": "…" }`。结果是列表，每项可包含 `url`、`nodeId`、`outputType`、`text`；`usage` 包含运行金额、RH 币、运行时长和第三方消费金额。来源：[工作流结果说明](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234531057524737?apiType=3#query-result-section)、[V2 查询文档](https://www.runninghub.ai/runninghub-api-doc-en/api-425767807)。

两份用户页面明确要求在任务完成后 **24 小时内下载/转存结果**，否则结果 URL 永久失效。应用文档也说明 AI App API 生成的媒体不包含工作流信息。来源：[AI 应用结果字段](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234279646748673?apiType=2#query-result-fields)、[Start AI App Task](https://www.runninghub.ai/runninghub-api-doc-en/api-425761096)。

提交可指定 `webhookUrl`，任务结束回调的例子是 `event=TASK_END`、`eventData` 和 `taskId`。本轮资料没有确认回调签名、验签算法、顺序保证、投递重试 SLA；回调不能直接作为可信的业务成功依据，应通过已保存的 taskId 做 Provider 查询核对。来源：[工作流 Webhook 字段](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234531057524737?apiType=3#query-result-webhook-fields)。后一句是对接约束推断，不是 Provider 保证。

## 4. 上传有两种不同语义

| 接口 | 文档行为 | 对接注意 |
| --- | --- | --- |
| `POST /task/openapi/upload` | multipart 字段为 `apiKey`、`file`、`fileType=input`；返回 `fileName`，给 LoadImage / LoadAudio / LoadVideo 等节点使用 | `fileName` 是 Provider 内部文件引用，不能自行拼成公开 URL |
| `POST /openapi/v2/media/upload/binary` | Bearer 鉴权，multipart `file`；返回可下载 URL 和文件标识 | 用户详情页称上传 URL 有效期 1 天；应按目标节点要求明确使用 URL 或内部引用 |

来源：[旧资源上传](https://www.runninghub.ai/runninghub-api-doc-en/api-425761099)、[新文件上传](https://www.runninghub.ai/runninghub-api-doc-en/api-425761098)、[用户页面上传说明](https://www.runninghub.ai/zh-cn/call-api/api-detail/2039234279646748673?apiType=2#files-rh-upload)。

旧资源上传说明列出图片 JPG/PNG/JPEG/WEBP、音频 MP3/WAV/FLAC、视频 MP4/AVI/MOV/MKV、ZIP，正文称单文件上限 30MB，但响应示例标签仍写“File exceeds 10MB”；实际限制没有带 Key 验证。新上传官方通用页返回例为 `code=200`、`filename`，用户详情页则示例为 `code=0`、`fileName`。这些字段/成功码差异需要实测并形成固定协议解析，不能混抄两页。来源：[旧上传说明](https://www.runninghub.ai/runninghub-api-doc-en/api-425761099)、[新上传返回例](https://www.runninghub.ai/runninghub-api-doc-en/api-425761098)。

## 5. 旧协议、取消、费用与不确定性

官方文档仍列出旧式 `POST /task/openapi/create`（工作流）和 `POST /task/openapi/ai-app/run`（应用），Body 分别携带 `workflowId` / `webappId`、`apiKey`、`nodeInfoList`。旧式与本轮用户详情页的 V2 path/header 协议不能视为同一请求格式。旧工作流接口还接受完整 `workflow` 字符串；这不意味着本项目应开放任意工作流执行。来源：[旧工作流提交](https://www.runninghub.ai/runninghub-api-doc-en/api-425761093)、[旧应用提交](https://www.runninghub.ai/runninghub-api-doc-en/api-425761096)。

官方列出 `POST /task/openapi/cancel`，Body 为 `apiKey`、`taskId`，成功例是 `code=0`。页面标题仅承诺 ComfyUI 任务取消，未在本轮明确确认对所有 V2 AI App 任务的适用范围、任务阶段限制、晚到结果处理或退款规则。来源：[Cancel ComfyUI Task](https://www.runninghub.ai/runninghub-api-doc-en/api-425761095)。

企业共享资源文档描述按 GPU 使用秒数计费、峰值可能排队，专用资源按 GPU 订阅付费。账户信息接口 `POST /uc/openapi/accountStatus` 的 Body 使用小写 `apikey`，返回例有 `remainCoins`、`currentTaskCounts`、`remainMoney`、`currency`、`apiType`。本轮没有核实可以在提交前准确获取任意工作流任务费用的报价 API；余额也不等于本次任务报价。来源：[Enterprise ComfyUI API](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287465)、[Get Account Information](https://www.runninghub.ai/runninghub-api-doc-en/api-425761030)。

官方错误码区分运行中 `804` 和排队中 `813`，明确不需要再次提交；还有参数/节点不匹配、余额不足、并发上限、任务不存在等业务错误。来源：[API Error Code Reference](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287467)。

**本轮未发现并核实的保证：**提交幂等键、按客户端命令键查任务、提交超时后按客户端键恢复、目标工作流/应用的不可变 revision、历史任务保存期、取消退款规则、API V3 工作流/AI 应用协议。不能把“未找到”写成 Provider 不支持；实现前须补官方确认或受控实测。`clientId` 文档描述为会话标识，没有证据证明它可作提交幂等键。

## 6. 对接约束结论

以下是依据以上外部事实的推断，尚非项目产品决策：

- 可以共用固定的 RunningHub 任务提交/查询/上传协议，而每个工作流或应用仍需要独立的输入映射与输出选择规则。
- AI 应用元数据适合生成表单候选；工作流 API-format 适合抽取候选字段。二者都需要本地保存受约束的字段定义，不能把远程 JSON、任意 endpoint 或任意执行脚本当作可信配置。
- 一个远程任务可以返回多项结果。结果的选择与归档应处理整个列表，不能为每个结果重新提交同一收费任务。
- 数据库必须保存 Provider taskId、固定输入映射和协议版本；缺乏远程幂等/按客户端键恢复保证时，提交响应丢失仍须保留 UNKNOWN，不能自动再次生成。
- 成功生成与下载归档是不同阶段。必须在 24 小时窗口内归档，下载重试不能变成再次生成。
- 共享运行环境会更新且工作流可能变化；官方要求开发者维护发布的工作流。接口绑定和字段 Schema 需要检测变化并重新校验，不能假定 ID 不变意味着参数契约不变。来源：[共享环境说明](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287465)。

## 7. 初始研究的核实范围（历史记录）

已运行：官方文档读取、用户两个详情页的只读浏览器核对、请求示例与返回示例比对。未运行：认证元数据请求、文件上传、真实生成、查询真实任务、取消、费用预检和失效窗口实测；未修改代码、API 合约、数据库迁移、规格或 ADR；功能测试未运行（仅新增研究文档）。

## 8. 接入设计依据（原始提案）

以下为研究阶段的原始设计依据。用户随后要求实施 RunningHub API 与动态表单，已接受 [ADR 0025](../adr/0025-runninghub-versioned-input-contracts.md)，覆盖 ADR 0002 的 RunningHub 暂缓部分。实际实现范围以第 10 节和规格 6.13 为准；原提案中的可识别链接、cURL 导入和运行前远程摘要核验没有实施。

### 8.1 复用连接和能力目录

沿用 `MediaProviderConnection → MediaCapability → MediaCapabilityVersion → Task`。一个 RunningHub 连接保存服务端加密凭据与受控站点配置，可以发布多个不同工作流和应用能力。能力有面向创作者的名字，例如“商品换背景”“人物换装”“参考图生成视频”；名称无需等于模型名。

Provider 模块新增编译期维护的 RunningHub 协议实现，内部区分 `WORKFLOW` / `AI_APP` 两类提交。它隐藏鉴权、字段编码、上传引用、提交、查询和错误解释；每新增一个同协议工作流，只新增能力配置，不新增 Java Adapter。协议实现放在现有 `MediaAdapter` seam，继续由 Task 模块管理账本、租约、fencing、事件与业务完成。原有固定模型能力继续使用原定义。

版本化能力定义建议包含：

| 定义 | 用途 |
| --- | --- |
| `targetType`、真实 `targetId`、`protocolVersion` | 区分工作流与应用，固定实际请求路径与协议；不使用目录 SKU ID 代替目标 ID |
| `inputSchema` | 字段稳定键、类型、必填性、默认值、范围、枚举、允许资源类型及条件校验 |
| `uiSchema` | 中文标签、说明、顺序、分组、常用/高级、默认隐藏规则；不包含运行代码 |
| `inputBindings` | 本地字段到 `nodeId + fieldName` 的映射、固定值及允许的值编码 |
| `outputBindings` | 主输出类型/节点、应归档的额外输出、支持的数量与文件格式 |
| `executionOptions` | 受控实例类型等；会额外计费的选项明确展示，实例保留默认不启用 |
| 来源快照及摘要 | 记录导入依据、字段契约与映射的 hash，配合现有版本和 CAS |

这些可以组成现有能力版本 `spec_json` 中的专用定义，不需要为了接入 RunningHub 新建通用工作流引擎。实际 API 应为固定能力与 RunningHub 能力定义增加可辨别的类型，统一生成 TypeScript；不能把供应商请求 JSON 原样开放为任意对象，也不能手改生成类型。

### 8.2 接入向导

管理员负责连接和能力发布；创作者使用已发布能力。自托管部署者通常同时扮演两个角色，因此操作入口应清楚说明“添加能力”和“使用能力”。

1. **连接**：选择 RunningHub 站点，保存 Key。导入模板不包含 Key；复制模板可以绑定已有连接。
2. **导入**：选择工作流或 AI 应用，提供真实目标 ID/可识别链接。优先通过受控元数据接口获取候选；也允许导入已脱敏的参数 JSON 或 ComfyUI API-format 导出文件。文档 URL 的目录 ID 必须先解析成真实 ID，不允许猜测。
3. **整理字段**：AI 应用根据 `fieldType/fieldData/description` 生成初始表单。工作流列出常量输入候选，管理员选定向创作者开放的字段，补充中文名、类型、默认值和限制；节点连线、模型内部配置和路径不自动开放。
4. **映射输入输出**：把提示词、尺寸、时长等常用字段映射到已有交互；额外字段进入能力专属区域。图片、音频及后续视频参考用具名槽位，例如“人物图”“衣服图”“背景图”，避免单纯按 Image 1/2 猜用途。选择主输出节点/类型，并明确额外输出的归档方式。
5. **预览与发布**：先显示自动表单、缺失项、输入输出及估算费用；离线校验不提交生成。可由管理员显式发起一次有费用提示的真实测试。保存/发布不自动产生付费调用，测试失败保留配置草稿，发布版本不可变。

参数映射示意（下列节点号仅是假设，不属于用户两个示例）：

| 创作者字段 | 控件/来源 | RunningHub 参数 |
| --- | --- | --- |
| `prompt`，画面描述 | 多行文本 | `nodeId=6, fieldName=text` |
| `personImage`，人物图 | 归档图片的精确版本 | `nodeId=10, fieldName=image` |
| `strength`，变化强度 | 0–1 数值 | `nodeId=3, fieldName=denoise` |

应用运行时提交稳定字段键与资源精确版本；服务端使用冻结的能力版本编码成 `nodeInfoList`。资源字段定义明确上传后使用内部文件引用还是 URL，不能统一强转成公开 URL。映射仅允许固定值、字段引用及明确实现的类型转换，不引入表达式语言、Shell 或 Groovy。

导入 cURL 时只解析白名单请求结构、目标 ID 与参数，不执行命令；凭据先剔除，原始含 Key 文本不得进入日志、模板、导出或 Prompt。表单 schema 使用受限 JSON Schema 子集和受控 UI 元素，不执行远程脚本、不解析远程 `$ref`。

**自动发现的凭据限制：**官方应用元数据例把 `apiKey` 放在 GET query；项目 AGENTS 第 12 节禁止 Key 进入 URL。需要验证该接口是否接受仅 Bearer 头或另有安全接口，在验证前不能照抄 query Key。无安全自动发现路径时先提供脱敏参数导入/手动字段定义，保留现有凭据规则。

### 8.3 画布使用体验

创作者选择“商品换背景”等能力，看到其必需的提示词、具名素材和常用参数；高级参数折叠。没有提示词输入的工作流不显示强制 Prompt，没有可控时长的视频任务不要求输入无效时长。不宣称工作流都支持首尾帧或全能参考，而是按能力真实定义展示输入。

草稿持久保存能力字段值和精确资源引用，Zustand 只保存交互草稿；运行前由服务端重新校验。切换能力时根据稳定键、语义和约束匹配保留字段，列出不兼容字段与素材影响，显式确认后转换；不能按相同中文名盲目迁移。后台能力更新导致不兼容时保留完整旧草稿并阻断运行。

具名槽位必须进入持久草稿、任务快照、精确引用和来源关系，不能只做前端标签。当前图片/音频有精确引用基础，视频上传与视频参考仍须作为独立增量实施；用户示例标为“视频生视频”，仅支持现有图片/音频输入的首个切片不能宣称已完整接通这个应用。

### 8.4 需要修改的现有位置

| 位置 | 现状与所需改动 |
| --- | --- |
| `provider/domain/MediaAdapterRegistry.java` | 声明按 Adapter ID 固定为一种 Task Kind 和一套限制；需要区分编译协议支持范围与每个已发布能力的具体 Schema，不能给所有 RunningHub 工作流写一个统一图片数/时长范围 |
| `provider/application/MediaCapabilityService.java`、`contracts/openapi.yaml` | settings 当前为固定字段白名单；增加 RunningHub 定义的受控校验、版本化导入与发布，不只是新增一个下拉项 |
| `artifact` 媒体草稿与 `canvas` 输入来源 | 持久保存能力专属参数及具名媒体槽位；保持 CAS、精确版本和资源鉴权 |
| `task/application/DirectMediaTaskService.java` | 当前所有媒体强制 Prompt，视频强制可选时长；改为按能力输入契约预检，固定实际参数、目标、映射、schema 与价格快照 |
| `frontend` 媒体设置和 `MediaDraftEditor.tsx` | 增加导入向导、表单预览及能力驱动的编辑区，保留保存失败、冲突、取消和 UNKNOWN 状态 |
| `provider/domain/MediaPayload.java` 及任务完成归档 | 当前一个完成结果是一条媒体流；多输出工作流需要一远程任务对多个结果的持久、可恢复归档，不能创建 N 次相同的收费提交 |

### 8.5 任务与费用约束

提交前保存固定尝试与输入；提交受理后保存 Provider taskId，使用定时查询而非持续占用 Worker 等待。已有 taskId 的查询/下载失败重试原任务；丢失受理结果且无可靠核对方式则 UNKNOWN，不自动重提。供应商排队与本地 READY 分开显示，外部并发错误不转成本项目新增的项目级产品配额。

结果先保存返回清单，再在 24 小时内进行逐项受控归档；使用节点 ID 与输出类型选择结果，不能默认取列表第一个，也不能静默丢弃预期额外结果。每项结果保存归档状态/身份，以便下载或数据库写入失败后继续，不重新生成。额外输出节点只关联同一次任务，重复查询不会重复创建节点。首个切片可以限制经确认的一种媒体主输出；多结果归档未完成前，明确阻断不兼容能力发布。

取消保留项目既有语义：停止后续编排，不承诺供应商停止或退款；晚到结果进入历史且不自动选用。Webhook 可以作为后续加速，先用 Provider 查询核对，不依赖自托管实例有公网回调地址。

运行前只展示管理员估算或未知费用，RunningHub 返回的 `usage` 另存供应商原始用量；未知/null 不记为零。GPU 执行时长不等于输出视频时长；RH 币不直接相加到 CNY/USD 金额；币种、各金额的包含关系未确认前不生成总实付结论。账本预留与结算仍由 Usage 模块维护。

本地能力版本只冻结本地契约，不能证明供应商内部工作流也不可变。更新时显式同步新版本并比较字段/输出变化；能获取远程定义时预检其摘要。若 Provider 没有不可变 revision 或提交时一致性保证，即使前后做 hash 检查仍有时间窗口，界面与审计不能宣称完整冻结云端实现。

### 8.6 实施顺序及验收

1. 固定 V2 Client + 工作流/AI 应用两种提交、原 taskId 查询与单媒体归档；元数据导入与单输出纵向切片一起交付。
2. 接入向导 + 版本化字段定义/映射 + 草稿动态表单。用两个参数不同的目标验证：接入第二个同协议目标只配置数据，不改 Java 或 React 代码。
3. 增加具名多素材与单任务多输出；视频参考/混合结果按需要独立扩展，不能用“通用参数对象”跳过领域设计。

专项检查应覆盖：API-format 字符串二次解码、应用 LIST 值与显示名、字段校验、图片/音频引用鉴权、Key 脱敏、目标 ID 解析、上传两种语义、提交响应丢失、重启后查询原任务、重复成功响应、逐结果归档恢复、用户修改草稿后的晚到结果、远程字段变化和费用未知。涉及迁移按增量 Flyway、重新生成 jOOQ，API 同步生成 TS；只运行对应单元与 PostgreSQL 集成测试，真实 Provider 测试单列。

## 9. 第三方 Go SDK 参考

用户补充 [difyz9/runninghub-sdk-go](https://github.com/difyz9/runninghub-sdk-go)。本轮只读审阅固定提交 `07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd`，没有安装、运行或测试该 SDK。以下是该仓库实现事实，不是 RunningHub 官方保证。

- [V2 wrappers](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/endpoints_v2.go#L94) 区分 workflow / ai-app 提交并共用 query，可作为 Java Client 的协议结构参考。SDK 默认 `.cn`，本轮两份用户例为 `.ai`；连接应明确站点，不混用域名和凭据。
- [应用元数据实现](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/endpoints_ai_app.go#L33) 把 `apiKey` 加到 GET query，通用 Client 又加 Bearer；不能照抄到禁止 Key 进入 URL 的项目。安全替代尚须验证。
- [工作流 JSON](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/endpoints_comfy.go#L130) 对 `data.prompt` 字符串做二次 JSON 解析，印证 API-format 导入需要这一步。此处 Go `map[string]any` 不等于完成了字段契约验证。
- [参数 DTO](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/types.go#L268) 保存类型与描述；[NodeModifier](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/node_modifier.go#L23) 是参数拼装辅助，并未实现通用表单校验。Text 等快捷方法预设字段名，项目仍须按能力保存 `nodeId + fieldName` 映射。
- [上传实现](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/endpoints_upload.go#L45) 只接受 `code=0`，[DTO](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/types.go#L23) 使用 `fileName`；测试仅模拟这一响应，不能解决官方新上传通用页 `code=200/filename` 的不一致。
- [WaitForCompletion](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/compatibility.go#L139) 默认 5 秒轮询、30 分钟等待；查询错误立即返回，FAILED/CANCELLED 等终态也可能返回 nil error，调用方必须检查业务状态。项目应使用数据库持久下一次查询时间，不能移植成长期占用 Worker 的内存等待循环。
- [Client](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/client.go#L68) 默认 HTTP 超时 60 秒，未实现应用层生成提交自动重试循环；响应解码中的 retry 仅重新解码已有响应。SDK 不提供本项目所需的 UNKNOWN、租约和 fencing 恢复。
- [结果下载](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/download.go#L73) 遍历同一任务的 results，不重复生成；纯文本的空 URL 项被跳过，项目若以后支持文本工作流需专门归档 text 字段。
- [取消](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/endpoints_comfy.go#L59) 仍调用 legacy 接口，不能证明对全部 V2 AI App 生效或退款。[PricePreview](https://github.com/difyz9/runninghub-sdk-go/blob/07a49a9cca11b73012eeb6c3c7c8ab9422aa99fd/runninghub/endpoints_standard_model.go#L91) 注释限定模型 API，也不能据此承诺工作流与应用可靠报价。

SDK 可用于核对请求与响应、构造本地假 HTTP 协议测试；项目按现有 Java/Spring Boot 架构自行实现协议，不新增 Go 服务、动态 endpoint 执行或兼容层。


## 10. 已实施的接入与使用方式

规格与验收跟踪：[GitHub Issue #26](https://github.com/grayrepo-byte/Agenvas/issues/26)。

### 10.1 统一协议，独立能力契约

项目新增固定的 `RUNNINGHUB_IMAGE` / `RUNNINGHUB_VIDEO` / `RUNNINGHUB_AUDIO` 适配器；它们使用同一个 Java V2 Client，根据 `WORKFLOW` / `AI_APP` 选择提交路径，按原 taskId 查询。每个工作流或应用使用现有 `MediaCapabilityVersion` 保存自己的 `settings.runningHub` 定义，复用连接、加密凭据、发布版本、Task、用量和画布草稿。新增同协议目标无需编写适配器或修改画布组件。

字段支持 STRING、NUMBER、INTEGER、BOOLEAN、SELECT 与 IMAGE/AUDIO/VIDEO 具名槽位，以及必填、默认值、范围、说明、高级参数、简单等值条件、节点映射和提交编码。素材字段只保存项目内精确 ArtifactVersion UUID；服务端校验授权与媒体类型，上传实际归档字节后替换为 RunningHub 内部文件名或其上传接口返回的 URL。用户不能填写任意外部素材 URL。

### 10.2 管理员接入下一个目标

1. 打开 **设置 → 媒体服务**，添加 RunningHub 连接，填写 HTTPS API 根地址（默认 `https://www.runninghub.ai`），保存 Key；一个连接可复用在多个能力上。API、上传返回值与下载不使用域名白名单；保留 URL / DNS 检查和禁止重定向，下载不携带 Key。
2. 添加图片、视频或音频的 RunningHub 能力，命名为用户能理解的用途，选择“ComfyUI 工作流”或“AI 应用”，填写真实目标 ID。本文两个示例分别为 `2037454919065673729` 和 `2039199752025280513`；不要填写目录 SKU ID。
3. 点击导入候选。自动发现失败时展开“导入脱敏 JSON”，粘贴 `nodeInfoList` 或 ComfyUI API-format JSON，再导入。应用发现只发送 Bearer；如果上游还要求 query Key，请采用本地 JSON 导入。不要粘贴 Key、密码或完整 cURL 请求。
4. 先选择节点，再删除不应开放的候选，整理该节点的用户名称、类型、默认值、范围、必填规则及固定参数；切换节点保留编辑内容。把文件输入改成对应素材类型并选择 `FILE_NAME`（Load 节点）或 `URL`（URL 输入节点）；不要把所有工作流都套成相同首尾帧或参考图接口。仅有提示词/时长语义时选择对应来源。检查输出节点、媒体类型、主输出和最大结果数。输出类型保存后仍可编辑；修改主输出会同步能力类型，清空不兼容计费单位的估算价格，保存时清除原类型中指向该能力的默认选择；旧任务继续使用原版本。
5. 查看离线表单预览，勾选“已核对开放字段、素材格式与输出映射”后直接发布或保存当前能力配置，成功后关闭窗口。校验或保存失败保留草稿，修正契约后重新核对，失败不自动重试。保存、导入、预览和发布都不提交生成。价格只能作为管理员估算；保留实例默认关闭，启用会增加 Provider 费用。
6. 回到对应画布节点选择该能力，填写专属表单、为每个素材槽位选择精确版本或上传，保存后显式运行。每个新目标仍需单独验证参数兼容性；已验证原两份视频示例及 YZ 金鱼工作流（开发记录不随源码公开），另有 MinimaxH3 AI 应用（开发记录不随源码公开） 的生成与归档记录，不能据此承诺其他目标。

对于 ComfyUI，普通字符串 `image` 或 `text` 字段不能可靠说明其业务用途，所以导入器不会自动把它们标成图片或提示词；不支持的界面辅助字段会跳过并提示核对。AI 应用的 LIST 导入支持标量选项、`{label,value}` 选项、ComfyUI `[选项数组, 控件设置]` 和 `["COMBO", {"options": [...]}]`，无法识别的 `fieldData` 会提示管理员补充。将表单参数键保持稳定，有助于切换能力时保留兼容输入。

### 10.3 运行、恢复与多结果

- 草稿参数放在 `parameters.dynamicValues`，不完整草稿可保存；运行时解析默认值并校验活动必填字段。没有 Prompt 或时长参数的应用可运行。声明的视频时长来源支持 1–60 整数秒，普通固定渠道仍按原范围校验。
- 任务受理后固定能力和连接版本、有效参数、精确图片/音频/视频输入以及目标节点；随后编辑能力配置或草稿不会改变已固定输入。外部提交前发现连接/能力或草稿已变化时沿用现有阻断规则，由用户重新检查；外部已受理后仍按原绑定查询与归档。切换能力清除不兼容内容前需要用户确认。
- 一次请求最多 16 个受映射约束的媒体结果。主输出第一个结果追加当前节点；同类额外输出创建独立节点并复制受理时的草稿，混合类型额外输出创建对应产物和空白草稿。所有结果版本、节点与任务终结同事务提交，冲突与取消结果仅归档历史。
- 查询成功后先保存私有结果清单，再用任务 ID 与稳定结果序号归档。恢复时读取数据库清单、跳过已完成归档，只继续下载失败项；不会重复生成，也不依赖进程内等待。临时 URL 不进入 Task DTO、SSE 或导出。
- 生成提交超时、5xx、重定向或无法确认 taskId 时为 UNKNOWN；HTTP Client 关闭自动重试，状态查询与归档恢复不会重提生成。
- 供应商返回的四类 usage 字段保留 null 和独立含义，未确认币种时不合并为总实付。未知媒体时长的 SECOND 估算为未知，不能用 GPU 执行时间补齐。

### 10.4 合约、升级与验收范围

OpenAPI 新增管理员只读导入预览 `POST /api/v1/settings/media-connections/{connectionId}/runninghub/preview`，以及受约束的 RunningHub 定义、动态值、平台和 `VIDEO_REFERENCE`。前端类型从合约重新生成。V66 为原有连接/媒体引用枚举增加值，并添加任务的私有 `provider_result_manifest`；jOOQ 由隔离 PostgreSQL 17.11 执行 Flyway 后重新生成。没有删除旧字段或清空既有数据，前后端与迁移需一起升级；旧客户端不识别新平台/引用枚举，不能单独沿用旧前端管理新能力。

定向测试覆盖固定路径和鉴权、导入候选/类型/拒绝凭据、动态表单、发布前人工核对、具名视频与跨项目拒绝、UNKNOWN、能力版本固定、多结果断点恢复、混合输出、取消晚到结果、fencing 及既有媒体行为。实际命令与计数见 专项证据（开发记录不随源码公开）。

**当前限制：**已完成原两份视频示例和 YZ 金鱼工作流（开发记录不随源码公开） 的真实上传、生成、归档、Task 选用与费用核对；另有 MinimaxH3 应用（开发记录不随源码公开） 的一次真实生成和原结果查询/归档，首次本地 Task 因 ZIP 处理失败，未追加付费验证最终 Task 选用。未列出的目标、音频/视频输入和结果失效恢复未真实验收。本地契约版本与来源摘要不冻结云端工作流，也未实现运行前远程摘要核验。目录链接自动解析、cURL 解析、文本结果、旧式生成、Webhook、远程取消、密码保护目标不在范围。视频输入使用具名槽位手动选择/上传，未新增视频输入画布连线。结果 URL 超过上游有效期时不能保证恢复；本次没有验证上游是否可以刷新过期 URL，失败保持可见，不能重新生成来掩盖归档失败。
