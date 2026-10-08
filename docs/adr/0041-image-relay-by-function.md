# ADR 0041：按功能控制图片资源中继

接受（2026-10-09）。用户要求在已配置媒体中继时，把 OpenAI 的 LLM 图片 base64 输入改为图片 URL，并对支持 URL 的生图接口采用相同方式；LLM 与图片生成功能可以独立关闭中继。

复用现有独立中继连接、72 小时 HTTPS GET 签名与 7 天临时副本清理。新增 `llmRelayEnabled`、`imageRelayEnabled` 两个持久开关，与中继连接在一次 `expectedVersion`/CAS 写入中保存。选择了中继连接且相应开关开启才启用；没有连接或开关关闭时保留原来的 data URL/文件上传。视频参考继续按 [ADR 0023](0023-per-asset-object-storage.md) 执行。V17 两个开关默认开启，因此已配置中继的安装升级后会对这两类图片调用启用中继；未配置中继的安装仍发送内联图片。

OpenAI 兼容 LLM 在每次模型调用前读取开关，只把已完成授权与精确版本校验的图片字节转换为 Spring AI `Media` URL；普通与流式调用共用此路径。项目图片预览、Skill 图片预览保持原来的缩略图、大小和数量约束，不扩大输入范围，不让模型提供地址或开关。持久检查点、公开会话与事件继续只保存图片版本引用，签名只存在于该次服务端发送路径中。

固定 OpenAI Images 编辑协议使用 JSON `images[].image_url`，蒙版使用 `mask.image_url`。只调整传输，上传与原文件路径相同的缩放、留白参考 PNG 和 alpha 蒙版，不改画面内容、提示词或结果归档。关闭图片中继继续 multipart 文件上传；Google、ComfyUI、RunningHub 等未声明此协议的适配器不改变输入方式。普通草稿生成、批准的 Agent 媒体生成与图片派生处理都通过现有任务受理固定连接身份；已排队任务不因切换设置改变传输，没有中继字段的旧任务继续原协议。

在上传前用短事务固定连接位置并登记清理对象，提交事务后才进行 PUT/签名。连接编辑、删除必须等登记或任务受理提交，再执行已有引用保护。图片临时副本上传失败仍保留清理记录；中继预检或准备失败使用 `MEDIA_RELAY_PREPARATION_FAILED`，生成尚未提交，不进入 UNKNOWN。URL 接口的明确拒绝或受理不确定继续原失败/UNKNOWN 分类，不通过另一次文件上传请求自动兜底。

[ADR 0020](0020-opt-in-debug-call-bodies.md) 的 LLM 正文保留规则增加一个精确例外：只隐藏本次调用由服务端登记的中继 URL 查询签名，包括正文中回显的同一地址。普通远程图片 URL、提示词、参数与工具定义仍保持原内容；实际网络请求不修改。媒体 debug 继续原有签名过滤。中继 URL 不写入任务、版本来源、SSE、项目导出或普通日志。

合约新增必填开关；前后端、OpenAPI/生成 TypeScript、V17 和生成 jOOQ 需一起发布。永久归档位置及已有素材不迁移。真实 Provider 与对象桶可达性不由模拟 HTTP 测试证明，验证范围见开发清单。

协议依据：[OpenAI Images 编辑 API](https://developers.openai.com/api/reference/resources/images/methods/edit)、[Spring AI 多模态 Media](https://docs.spring.io/spring-ai/reference/api/multimodality.html)。
