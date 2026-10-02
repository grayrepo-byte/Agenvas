package dev.agenvas.llm.infrastructure;

import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** 限定模型请求只能访问已准入端点，并在连接时校验 DNS 解析结果。 */
final class SafeLlmTransport {

    /** The protocol checkpoint remains capped at 1 MiB; repeated SSE envelopes get fixed wire overhead. */
    static final long MAX_STREAM_RESPONSE_BYTES = 4L * 1024 * 1024;

    /** 准入策略检查通过的配置端点，作为请求校验的来源。 */
    private final HttpUrl base;
    /** 固定的补全 API 路径；拒绝客户端请求其他端点。 */
    private final String completionPath;
    /** 禁止代理、重定向和自动重试，并在连接时验证 DNS 的客户端。 */
    private final OkHttpClient client;

    /** 使用系统 DNS 构造生产传输层。
     * @param endpoint 管理员配置的 LLM 端点
     * @param policy 检查主机名及解析地址是否允许访问的策略
     */
    SafeLlmTransport(String endpoint, LlmEndpointPolicy policy) {
        this(endpoint, policy, Dns.SYSTEM);
    }

    /** 使用可替换解析器执行与生产一致的地址校验。
     * @param endpoint 已准入的 LLM 端点
     * @param policy DNS 地址安全策略
     * @param resolver 域名解析器；测试可注入混合或变化地址
     */
    SafeLlmTransport(String endpoint, LlmEndpointPolicy policy, Dns resolver) {
        this.base = HttpUrl.get(endpoint);
        String path = base.encodedPath().replaceAll("/+$", "");
        this.completionPath = path + "/chat/completions";
        this.client = PinnedHttpClients.pinned(host -> {
            if (!host.equalsIgnoreCase(base.host())) {
                throw new UnknownHostException("LLM host changed");
            }
            List<InetAddress> addresses = resolver.lookup(host);
            if (addresses.isEmpty()) throw new UnknownHostException("LLM DNS returned no addresses");
            try {
                for (InetAddress address : addresses) {
                    policy.requireAllowedAddress(host, address);
                }
            } catch (RuntimeException unsafe) {
                throw new UnknownHostException("LLM DNS returned a blocked address");
            }
            return addresses;
        }, Duration.ofSeconds(10), Duration.ofSeconds(45), Duration.ofSeconds(60));
    }

    /** 为 Spring AI 安装拦截器，将请求转交给禁用重定向和自动重试的专用客户端。
     * @return 校验请求目标后执行网络调用的拦截器
     */
    Interceptor interceptor() {
        return chain -> execute(chain.request());
    }

    /** 校验 Spring AI 实际发出的 URL、方法和查询参数后执行请求，并拒绝重定向。
     * @param request Spring AI 构造的 HTTP 请求
     * @return 未消费的 Provider 响应，由调用方负责关闭
     * @throws IOException 请求目标不匹配、DNS 失败或网络调用失败
     */
    private Response execute(Request request) throws IOException {
        HttpUrl url = request.url();
        if (!url.scheme().equals(base.scheme()) || !url.host().equalsIgnoreCase(base.host())
                || url.port() != base.port() || !url.encodedPath().equals(completionPath)
                || url.query() != null || !"POST".equals(request.method())) {
            throw new IOException("LLM request target differs from the admitted completion endpoint");
        }
        Response response = client.newCall(request).execute();
        if (response.isRedirect()) {
            response.close();
            throw new IOException("LLM endpoint redirect is forbidden");
        }
        boolean streamingRequest = request.headers("Accept").stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains("text/event-stream"));
        MediaType responseType = response.body() == null ? null : response.body().contentType();
        boolean streamingResponse = responseType != null && responseType.type().equalsIgnoreCase("text")
                && responseType.subtype().equalsIgnoreCase("event-stream");
        return streamingRequest || streamingResponse ? boundStream(response) : response;
    }

    /** The bound precedes SDK line parsing and tool-fragment accumulation, including unknown lengths. */
    static Response boundStream(Response response) throws IOException {
        ResponseBody original = response.body();
        if (original == null) return response;
        if (original.contentLength() > MAX_STREAM_RESPONSE_BYTES) {
            response.close();
            throw new IOException("LLM stream response exceeds the transport size limit");
        }
        BufferedSource source = Okio.buffer(new ForwardingSource(original.source()) {
            private long consumed;

            @Override
            public long read(okio.Buffer sink, long byteCount) throws IOException {
                // Read at most one byte beyond the remaining allowance, so even an oversized
                // single SSE line is stopped before a parser can allocate its complete content.
                long read = super.read(sink, Math.min(byteCount, MAX_STREAM_RESPONSE_BYTES - consumed + 1));
                if (read != -1) consumed += read;
                if (consumed > MAX_STREAM_RESPONSE_BYTES) {
                    IOException exceeded = new IOException("LLM stream response exceeds the transport size limit");
                    try { super.close(); } catch (IOException closeFailure) { exceeded.addSuppressed(closeFailure); }
                    response.close();
                    throw exceeded;
                }
                return read;
            }
        });
        return response.newBuilder().body(new ResponseBody() {
            @Override public MediaType contentType() { return original.contentType(); }
            @Override public long contentLength() { return original.contentLength(); }
            @Override public BufferedSource source() { return source; }
        }).build();
    }
}
