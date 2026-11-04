# ADR 0067：显式准入本地 LLM HTTP Origin

状态：接受（2026-11-04）。根据外部实测反馈修复本地模型连接路径；保留默认安全边界。

默认拒绝 HTTP 的决定已由 [ADR 0070](0070-operator-selected-http-endpoints.md) 按用户新要求替代；本 ADR 保留历史动机，精确 Origin 与独立回环开关继续有效。

## 背景

ADR 0007 已允许私网 HTTPS 和代理 fake-IP，但 HTTP 仍仅支持部署者开启后的字面 `127.0.0.1`。Docker 服务端的回环并不是宿主机；因此开启原有开关不能连接宿主机原生 Ollama。把全部私网 HTTP 默认放开会扩大出站与明文凭证暴露范围，不能作为本地连接修复。

## 决策

新增部署级 `agenvas.settings.llm.trusted-http-origins` / `AGENVAS_LLM_TRUSTED_HTTP_ORIGINS`，默认空列表。部署者可以显式准入最多 32 个精确 HTTP Origin（主机、有效端口）；禁止通配符、网段、路径、凭据、查询与片段。无效条目启动即失败，错误不回显原值。管理员模型表单不能修改部署策略。HTTPS 私网和 fake-IP 沿用 ADR 0007，不新增默认准入。

四个基础 Compose 继续直接声明可见配置，空字符串不依赖 `.env`。可选 `deploy/compose.ollama.yaml` 明确准入 `http://host.docker.internal:11434` 并加入 `host-gateway` 映射；使用者须显式加入 override 且在后续操作继续使用。用户指南见 [本地 Ollama](../ollama-local.md)。这只是容器到宿主机的地址与策略路径，模型监听、主机防火墙和 Windows/WSL 可达性仍由部署者实际验证。

保存配置仍做 URL 与地址校验；构造运行时客户端重新校验已保存 Origin，撤销部署例外并重建服务后旧配置不能继续发出请求。OkHttp 对字面 IP 绕过自定义 DNS，因此传输层构造时补充字面 IP 校验，域名连接保持固定 DNS 和全部答案检查。除既有 IPv4 云元数据、link-local、域名回环阻断外，补充拒绝 AWS `fd00:ec2::254` 与 GCP `fd20:ce::254` 两个 IPv6 ULA 元数据地址，避免被私网例外提前放行。这些阻断不因可信 Origin 而取消，字面 `127.0.0.1` 仍需旧开关。禁止重定向、自动传输重试、非配置目标与非固定补全路径的规则保持不变。

本地 Ollama 不验证 OpenAI 客户端占位 Key，但 Agenvas 的 8–4096 ASCII 凭据契约保持不变。指南使用 `ollama-local`；不为六字符示例 `ollama` 放宽整个模型配置 API，不修改持久数据、OpenAPI 或生成代码。

## 验证范围

定向策略测试复现默认拒绝及显式准入；合成传输测试覆盖撤销、元数据字面 IP 与 DNS 混合答案，并复用原重定向和固定目标检查。没有把合成测试描述成真实 Ollama、Windows/WSL 或上下文质量验证；实际执行命令与限制由本次交付记录提供。此变更无数据库迁移、依赖升级和默认外部模型调用。

IPv6 元数据依据：[AWS IMDS](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html)、[GCP VM metadata](https://docs.cloud.google.com/compute/docs/metadata/overview)。该补充仅落在 LLM 策略；其他传输策略未在此次扩大修改范围。
