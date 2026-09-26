# T21：出站目标与重定向边界

2026-09-24。ComfyUI 客户端仅从部署者配置读取精确的 IPv4 `scheme://host:port`，拒绝主机名、用户信息、路径、查询、片段及普通 HTTP 公网地址；模型工具不接收 endpoint。客户端关闭代理与 HTTP 重定向，固定 `/prompt`、`/history/{id}`、`/queue`、`/upload/image` 和 `/view` 路由，输出下载仅接受安全文件名。IPv4 字面量不经过 DNS，因此不能通过该客户端做 DNS rebinding。特定内网 IP 仍可由部署者显式配置，这不是任意用户或模型 URL 导入能力。

`ComfyUiClientTest` 使用两个本地 HTTP 服务，让固定服务的 `/prompt` 与 `/view` 返回指向另一端点的 302；提交和下载均被拒绝，目标服务请求数为 0。原有构造测试还拒绝 `localhost`、云元数据地址、带用户信息和路径的地址。`SafeLlmTransportTest` 使用可注入 DNS 返回云元数据 IP，验证 LLM 出站在连接前拒绝；不同路径/端口和跨端点重定向也被拒绝，凭证不发送至目标。`LlmProviderConfigPostgresIT` 的管理员配置入口拒绝云元数据 URL。

2026-09-26 补充：出站请求的**不重复提交**边界。OkHttp 的 `retryOnConnectionFailure(false)` 只关闭连接失败恢复，不覆盖另一条路径 —— `RetryAndFollowUpInterceptor` 单独处理 503，读到 `Retry-After` 为数字 `0` 时会把原请求重发一次（`priorResponse` 判断保证最多一次）。五个出站客户端（LLM 补全、OpenAI、Google、Ark 提交与结果下载）原先都暴露在这条路径上，静默重发等于对非幂等且计费的调用重复下单。

修复放在共享客户端工厂 `PinnedHttpClients`：一个网络拦截器剔除响应上的 `Retry-After`，使 `retryAfter` 回落到上限值、重发分支不再命中。必须是网络拦截器而非应用拦截器 —— 后者位于 `RetryAndFollowUpInterceptor` 外层，拿到的是重发结束之后的响应。

`PinnedHttpClientsTest` 让本地假服务对 `POST` 返回带 `Retry-After: 0` 的 503，断言服务端只收到一次请求且响应正文仍可读。该用例已验证非假绿：暂时移除拦截器后它立即失败（观察到两次请求）。同测试另覆盖 302 不跟随。

定向 `./mvnw -q -Dtest=PinnedHttpClientsTest,ComfyUiClientTest,SafeLlmTransportTest test` 与完整后端 `./mvnw -q verify` 均退出码 0；Surefire/Failsafe XML 无失败或错误。未连接真实云 Provider、真实 ComfyUI，也未进行外部 DNS rebinding 基础设施演练；此证据证明代码的固定目标、模拟 DNS/重定向边界与单次提交语义，不证明生产网络层的独立隔离。
