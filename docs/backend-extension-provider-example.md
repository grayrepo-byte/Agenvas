# 新增供应商协议：可核对的前后对照

对照基点 `104483fd` 与本次第1–12次改造。实例是 `SYNTHETIC_IMAGE`：编译型图片协议、自己的预检错误和公开诊断、原请求查询、结果URL过期后单次刷新。传输为合成实现，数据库、Spring装配、发布和Task链路真实；这不是已接通一家付费供应商的声明。

实例实现和Bean装配均在[MediaAdapterExtensionPostgresIT](../backend/src/test/java/dev/agenvas/provider/MediaAdapterExtensionPostgresIT.java)中。实际接入时，将适配器放入`provider/application/`，HTTP客户端/解码器放入`provider/infrastructure/`，并保留相应协议测试；本次没有把测试供应商加入生产服务。

|这一个协议需要的工作|基点代码需要修改|现在的修改位置|
|---|---|---|
|实现提交、原ID查询和下载|新增适配器、客户端，并实现无调用方的`supports(PortInput)`|仍实现真实协议；`MediaAdapter`只保留使用中的执行入口|
|声明能力、种类和输入上限|`provider/domain/MediaAdapterRegistry.java`中央`DECLARATIONS`|适配器自己的`declaration()`；Spring Bean集合注册，无需向中央表加入`SYNTHETIC_IMAGE`|
|发布HTTP默认时限（真实HTTP实现需要）|`provider/domain/MediaHttpTimeouts.java`中央默认表|适配器自己的`publicationHttpTimeouts()`；历史缺省版本仍使用原协议回退|
|增加`SYNTHETIC_RESOURCE_UNAVAILABLE`预检码|`task/application/TaskService.java`与`usage/application/UsageService.java`两份白名单|适配器的`preflightFailure()`；通用Task内核确认未提交并签发同事务释放凭证|
|识别协议异常、刷新过期结果URL|`provider/application/MediaExecutionWorker.java`的供应商异常判断，以及`TaskService.refreshProviderResultUrls()`的RunningHub专属准入|适配器的`classifyPollFailure()`与`refreshResults()`；Task仍统一核对结果身份、CAS和租约，不再为这个ID加分支|
|公开受限诊断并保留自己的错误码|`TaskService.recordProviderFailure()`的供应商白名单；需避开`ProviderFailure.code()`的旧协议映射|适配器的`permitsPublicDiagnostic()`与独立错误码；注册器签发绑定ID的授权，Task复核后只保存有界诊断|

“现在无需改内核”限于上表这个已有平台/通用输入形状的扩展，不表示全部供应商差异已被抽象。

## 现在仍需要显式修改的边界

- **新平台身份**：示例沿用已有`MOCK`平台。真正新增平台仍需[MediaPlatform.java](../backend/src/main/java/dev/agenvas/provider/domain/MediaPlatform.java)、[OpenAPI](../contracts/openapi.yaml)、新的Flyway迁移扩展`ck_media_connection_platform`，以及前端`mediaAdapterCatalog.ts`、`mediaConnectionConfiguration.ts`、`MediaSettingsPage.tsx`等平台/凭据界面；生成类型按合约重新生成，不能手改。不能把合成例子当作这些工作已经消失。
- **新的原生模型/特殊参数**：显式原生协议仍加入`NativeMediaProfiles`装配；专属输入、发布规范化或公开元数据仍须修改其真正所有者，例如`MediaCapabilityService.normalizeAdapterSettings()`、协议profile与对应参数编辑器。没有开放任意JSON、自动UI或第三方代码加载。
- **真实网络和凭据**：适配器仍负责实际HTTP协议、认证格式、输出解码与供应商兼容性。固定历史配置可复用`PinnedMediaExecution`；当前已迁移OpenAI Image 2/Ark Seedance 2，其他家族需要自己的兼容证据。
- **同协议配置目标**：RunningHub目标/工作流原本就依ADR0025配置驱动，继续保留已发布契约；不将既有能力重新记作本次新增收益。

完整接入触点见[Provider接入指南](agents/adding-media-provider.md)，本次不恢复HTTP/私网/主机白名单限制。

## 已执行的行为证明

`MediaAdapterExtensionPostgresIT`的两个真实PostgreSQL用例随第8次补证执行通过：

1. `aNewCompiledAdapterPublishesExecutesAndRefreshesThroughTheExistingTaskKernel`：只增加测试Bean即完成能力发布、草稿和Task。新预检码阻断时submit计数不增加，释放账目实际费用仍未知；正常路径submit一次、查询原ID一次、刷新一次、下载两次，最终归档成功，证明刷新没有变成重新生成。
2. `installedAdapterPersistsOnlyItsPublicDiagnosticAndFencesThePreviousPollEpoch`：同worker旧epoch写入被拒；正常Worker把自己的`SYNTHETIC_PARAMETER_INVALID`和有界诊断持久化，私有异常正文不进入任务公开结果。

另[MediaAdapterRegistrationTest](../backend/src/test/java/dev/agenvas/provider/MediaAdapterRegistrationTest.java)检查自带声明/执行、自定义HTTP发布默认与历史回退、重复注册拒绝、未安装实现的稳定错误；它们证明注册契约，不证明真实供应商HTTP兼容。真实上游仍需独立端到端验证，不用更多结构断言凑测试数量。
