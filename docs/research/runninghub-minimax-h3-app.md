# RunningHub MinimaxH3 多参应用核查

核查日期：2026-10-01。目标：[MinimaxH3多参生视频 量化加速V3版](https://www.runninghub.ai/zh-cn/ai-detail/2084320751339032577)。本轮后台工作仅阅读公开 RunningHub 来源，没有登录、使用 Key、上传、提交生成或调用付费接口；主线程的参数读取和真实测试另行记录。

## 应用身份与资料版本

- 用户指定中文页面的应用 ID 为 `2084320751339032577`，页面标题为“MinimaxH3多参生视频 量化加速V3版”。[目标页面](https://www.runninghub.ai/zh-cn/ai-detail/2084320751339032577)
- 同 ID 的英文页面本轮可读到正文，标题仍是“MinimaxH3 Multi-parameter Video Generation Quantization Acceleration V2”，作者为 Glory-ai，分类包含视频生视频、文生视频、图生视频。中文/英文名称存在版本差异；英文介绍不能自动视为中文 V3 版所有控件的当前契约。[同 ID 英文页面](https://www.runninghub.ai/ai-detail/2084320751339032577)
- 标题中的 V3 是应用名称的一部分，没有证据表明它表示 RunningHub HTTP API V3。不能根据应用名选择请求协议。

## 最小时长与尺寸

主线程本轮在当前中文 V3 页面读到：官方默认时长 **5–15 秒**；作者说明二十几秒也可能生成，显存不足失败时可用 Plus。分辨率表列出 **0.2MP、16:9 → 608×352**，**0.4MP、16:9 → 864×480**，没有在介绍文字中写明像素最低 0.4MP。[当前中文页面](https://www.runninghub.ai/zh-cn/ai-detail/2084320751339032577)

后台读取的同 ID 英文 V2 介绍仍写时长 5–15 秒、0.4–0.9MP、Plus 48GB。该差异进一步说明不能把旧介绍变成中文 V3 的最小像素约束。[同 ID 英文介绍](https://www.runninghub.ai/ai-detail/2084320751339032577)

主线程观察到中文时长数字输入没有 min/max，像素输入允许范围很宽，**0.2MP 可以被表单接受**。UI 接受数值和换算表均不保证后端模型支持；本轮尚未确认最小可成功生成的时长/像素，作者的二十几秒说明也不是稳定上限保证。[当前中文表单](https://www.runninghub.ai/zh-cn/ai-detail/2084320751339032577)

未确认事项：后端实际输入范围及步长、其他比例的尺寸映射、输出 fps、必填参考数量、删除参考后的行为、纯文字运行支持、实例选择与实际资源之间的关系。上述内容由主线程当前参数读取和真实结果补充。

## 计费

目标同 ID 的英文应用页标为 **Charged by actual usage**，因此不能把输出视频时长直接当成 GPU 计费时长，也没有在该公开正文中看到固定每次费用。[应用页面](https://www.runninghub.ai/ai-detail/2084320751339032577)

RunningHub 企业共享 API 官方说明按 GPU 使用秒数计费，峰值可能排队；专用 API 按 GPU 订阅付费。这是平台一般规则，不是对本应用单次金额的承诺。[About Enterprise ComfyUI API](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287465)

主线程在当前中文应用的计费弹窗读到以下网页 RH 币费率：Lite **0.02 RH 币/秒**，当前 Standard **0.2 RH 币/秒**，Plus **0.4 RH 币/秒**，Ultra **0.6 RH 币/秒**。页面按钮金额是预估，实际以运行时长结算；使用第三方 API 的节点另从钱包计费，发起即扣费且不返还。[当前中文应用计费弹窗](https://www.runninghub.ai/zh-cn/ai-detail/2084320751339032577)

主线程将像素改成 0.2MP 后，按钮预估仍为 **53 RH 币**；当前未登录观察不足以证明该估值会随参数重新计算，因此不能拿它保证本次低参数成本。未确认事项包括任务最低计费时长、加载或解码计费边界、该应用是否含第三方收费节点、失败/取消的 GPU 费用、网页 RH 币与 API Key 钱包费率的对应关系。不得把网页费率直接当作任意 API Key 的实际费率。

后台检索索引曾在国际站共享价格页显示 Plus 48GB 的 `$0.9/hour`；本轮直接访问该 URL 已跳转到 Key 控制台，未取得当前费率表。这项索引信息不作为当前预算依据。[共享 API 原页面](https://www.runninghub.ai/enterprise-api/sharedApi)

## API 目标与参数映射

官方 AI 应用集成示例说明 `ai-detail/<数字>` 的末尾数字是 `webappId`；因此 `2084320751339032577` 是本应用的 API 目标 **候选 ID**，还需在本应用 API 页面/元数据中核实实际提交路径。[Complete integration example](https://www.runninghub.ai/runninghub-api-doc-en/doc-8287469)

本轮没有从公开正文取得该应用的 `nodeInfoList`、`nodeId`、`fieldName`、默认值或完整 Schema，不能只凭“多参”“0.4MP”“5 秒”编造提交映射。官方的应用参数发现接口为 `GET /api/webapp/apiCallDemo`，但示例把 apiKey 放在 URL 查询参数；项目禁止 Key 进入 URL，后台研究没有调用这个接口。需要先核实 header-only 方式，或使用无密钥的参数 JSON 导入。[官方参数发现接口](https://www.runninghub.ai/runninghub-api-doc-en/api-425761097)

应用 API 与 RunningHub 标准模型 MiniMax-H3 API 是不同的执行目标。标准模型的 `resolution=2K`、`duration=5` 等字段不能移植成此自定义 ComfyUI 应用的字段。本应用使用 megapixels 的公开说明正好表明需要独立映射。[应用介绍](https://www.runninghub.ai/ai-detail/2084320751339032577)、[标准模型 H3 API](https://www.runninghub.ai/runninghub-api-doc-en/api-495380675)

## 后台核查范围

主线程待执行的受控试跑计划：**仅一次**，5 秒、0.2MP、8 步、Standard，不保留实例；仅上传一张合成几何图片，其余图片/音频为 None。参考视频保留官方文件名但开关 false；关闭优化、高画质参考、补帧、ZIP，LoRA 强度为 0。**这是计划，尚未在本文件确认受理、生成成功、输出尺寸或实际费用。**真实结果由主线程另写证据。

已做：阅读目标页面/同 ID 英文正文、对照 RunningHub 官方计费及参数发现文档，记录语言与版本差异。未做：认证元数据调用、生成、上传、取消、账单查询或实际输出验证；功能测试未运行（仅新增研究文档）。本文件不代表该应用已接入项目，后续主线程测试证据应明确写出实际请求映射、任务 ID、输出时长/尺寸和真实费用。

## 后续真实结果

主线程已用 V2 路径受理 `2084320751339032577`，一次 5 秒 / 0.2MP / 8 步生成 SUCCESS，608×352、5167 ms。账户差额 26 RH币、0 USD。原结果含 MP4 与 ZIP，兼容修复后未重新生成，仅查询、下载与归档原 ID；初始本地 Task 失败和补验边界见 [真实证据](../evidence/runninghub-minimax-h3-2026-10-01.md)。此结果只证明本轮参数组合，前文其他范围的未确认事项仍有效。
