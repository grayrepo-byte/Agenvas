# ADR 0044：万相、Seedream 与 Google 原生媒体生成

状态：接受。用户要求新增万相图片/视频、Seedream 图片及 Google 当前视频生成协议，并补齐所选主力模型的生成参数。

发布固定适配器 `WAN_IMAGE`、`WAN_VIDEO`、`ARK_SEEDREAM_IMAGE`、`GOOGLE_VEO`、`GOOGLE_OMNI_VIDEO`。编译目录包含万相四个模型/操作组合、Seedream 三个模型的七个组合、Google 四个模型的八个组合，共十九个 profile。具体 Model ID 与参数以服务端只读 `/settings/media-profiles`、编译模型及[接入说明](../native-media-api-integration.md)为准，不增加每模型一份适配器，不从用户地址或任意 JSON 推导协议。

`NativeMediaProtocol` 与 `CreatorInputField` 统一现有能力发布、动态草稿、精确素材槽和运行冻结；各供应商模块保留地址、组合约束、元数据、原生请求与结果协议。服务器由 model/operation 派生 `nativeInputs`，客户端只消费该契约，不能编辑底层图节点或伪造云 API 为工作流。既有 Kling 完整协议和旧适配器继续使用自己的版本化目录，不借此改写历史任务。

发布能力固定 model/operation 与服务器编译的输入定义。`parameters.dynamicValues` 存储原生标量和精确 ArtifactVersion UUID；Prompt、画布时长仅在该 profile 声明对应 source 时参与有效参数。无时长来源的 Omni 与自动时长的万相保留创作草稿但不将旧值写成运行时长。管理员只能配置声明范围内的默认值和限制，不能通过设置扩大供应商原生组合。

受理时冻结 `mediaInput.providerParameters.native={adapterId,model,operation,values}` 四字段，values 与 `mediaInput.parameters.dynamicValues` 完全一致，并固定能力/连接版本、mapping hash、素材、价格和输入视频秒数。槽值只能指向本项目已授权的精确版本，素材准备校验原始字节、摘要、格式和实际探测；网络、签名和上传不持有数据库事务。

协议显式声明可复用素材的槽集合，默认为空。Google 的 GENERATE profile 仅允许 first_frame 与 last_frame 复用同一精确图片版本，用一条授权 mediaInputs 与冻结来源表达两个帧角色；参考槽、EDIT 与 EXTEND 不开放此复用。复用不会扩大版本授权，也不改变原生角色顺序。

万相图片/视频使用区域 Workspace 异步 API，按原 task_id 查询，签名视频/音频只来自已冻结的中转配置。Seedream 使用单次同步图片 API，固定 b64_json 与完整不可变结果清单；同步受理不确定不能通过重 POST 恢复。Google Veo 使用 long-running operation，Omni 使用保留单轮身份的 Interactions，所有查询、下载和归档重试只使用已受理身份。失败和 UNKNOWN 的边界保持现有任务内核语义，用户重新生成必须创建新尝试。

一个收费请求的组图、拆层或连续出图全部归档为同一任务的多个结果。结果身份、顺序和图层 metadata 固定后可恢复，追加节点不复制生成请求；自动数量按实际归档计数，视频按实际探测时长结算估算。万相视频将冻结输入视频秒数与实际输出秒数相加；未配置价格或未知输出数量/时长保持未知，不把估算宣称为供应商账单。

结果下载遵守固定来源、DNS、重定向、大小、MIME 与实际媒体检查。Wan 仅允许已确认的精确结果桶，Seedream 使用内联结果避免任意 URL 下载；Google Key 只用于固定官方 API/Files 路径，单跳 GCS 请求重新校验并移除凭据。Veo 扩展先核对精确版本为数据库不可变 TASK 来源，再核对安全 `files/<id>` 及模型、连接/版本 metadata；用户正文不能通过复制 metadata 伪造来源。不将原始 URI、签名或 Key 放入公开产物内容。Omni 只解析 model_output 媒体，debug 采集去除 thought 与媒体 Base64。

双向能力切换复用已有通用草稿迁移：兼容标量和精确素材版本保留，目标没有的字段/槽删除并同步清理结构化标签及输入连线；模型约束变化产生运行阻断而不覆盖用户草稿。无须为原生 API 新建画布交互方式；普通图像 ratio/resolution/count 控件与原生 size/组图参数互斥，视频时长按 profile source 决定。

V21 扩展连接平台约束以增加 WAN；ARK 与 GOOGLE 沿用现有加密连接。V22 将 Task、ProviderAttempt 与 CallLog 的 provider_request_id 扩为512字符，与新协议允许的有界原始身份一致，避免外部成功受理后因旧240字符存储上限丢失查询身份。V23 仅将 NATIVE_MEDIA 的内部结果清单上限扩为17，满足 Seedream 拆层；其他协议保持16，既有清单约束不被统一放宽。能力、结果清单和扩展 metadata 复用不可变版本化 JSON；合约与生成 TypeScript 同步，jOOQ 由隔离 PostgreSQL 执行迁移后重新生成，普通构建不连接生成数据库。前后端需一起升级。

官方依据分别见[万相研究](../research/wan-generation-protocol.md)、[Seedream 研究](../research/seedream-image-protocol.md)、[Google 视频研究](../research/google-video-protocol.md)。定向测试使用 fake HTTP、合成素材和真实 PostgreSQL；实际检查结果据实记入开发清单。未调用付费 API、未部署，真实账号、真实模型组合、区域可用性、结果域名和供应商费用仍未验收。
