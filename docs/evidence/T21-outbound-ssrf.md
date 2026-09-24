# T21：出站目标与重定向边界

2026-09-24。ComfyUI 客户端仅从部署者配置读取精确的 IPv4 `scheme://host:port`，拒绝主机名、用户信息、路径、查询、片段及普通 HTTP 公网地址；模型工具不接收 endpoint。客户端关闭代理与 HTTP 重定向，固定 `/prompt`、`/history/{id}`、`/queue`、`/upload/image` 和 `/view` 路由，输出下载仅接受安全文件名。IPv4 字面量不经过 DNS，因此不能通过该客户端做 DNS rebinding。特定内网 IP 仍可由部署者显式配置，这不是任意用户或模型 URL 导入能力。

`ComfyUiClientTest` 使用两个本地 HTTP 服务，让固定服务的 `/prompt` 与 `/view` 返回指向另一端点的 302；提交和下载均被拒绝，目标服务请求数为 0。原有构造测试还拒绝 `localhost`、云元数据地址、带用户信息和路径的地址。`SafeLlmTransportTest` 使用可注入 DNS 返回云元数据 IP，验证 LLM 出站在连接前拒绝；不同路径/端口和跨端点重定向也被拒绝，凭证不发送至目标。`LlmProviderConfigPostgresIT` 的管理员配置入口拒绝云元数据 URL。

定向 `./mvnw -q -Dtest=ComfyUiClientTest,SafeLlmTransportTest test` 与完整后端 `./mvnw -q verify` 均退出码 0；Surefire/Failsafe XML 无失败或错误。未连接真实云 Provider、真实 ComfyUI，也未进行外部 DNS rebinding 基础设施演练；此证据证明代码的固定目标和模拟 DNS/重定向边界，不证明生产网络层的独立隔离。
