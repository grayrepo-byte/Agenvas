package dev.agenvas.shared.http;

import java.net.Proxy;
import java.time.Duration;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * 所有出站 HTTP 客户端共用的固定约束：直连不走代理、不跟随重定向、不自动重试连接失败。
 * 这几条是 SSRF 与请求改道的防线，集中在一处以免新增客户端时漏掉其中任何一条。
 *
 * <p>DNS 策略不在这里决定，由调用方传入：内置云媒体传输用 {@code FixedCloudDns}
 * 校验解析结果，LLM 传输用它自己的端点准入规则，两套规则各自演算，只共享管道。
 */
public final class PinnedHttpClients {

    /**
     * 剔除响应上的 {@code Retry-After}，阻断 OkHttp 对 503 的重发。
     *
     * <p>{@code retryOnConnectionFailure(false)} 管的是连接失败恢复，拦不住这条路径：
     * {@code RetryAndFollowUpInterceptor} 单独处理 503，读到 {@code Retry-After} 为数字 0
     * 时会原样重发一次请求。这些出站调用（生成、提交、模型补全）都非幂等且计费，
     * 静默重发等于重复下单。剔除该头后 {@code retryAfter} 回落到上限值，重发分支不再命中。
     *
     * <p>必须是网络拦截器：应用拦截器位于 {@code RetryAndFollowUpInterceptor} 外层，
     * 拿到的是重发结束之后的响应，改不到它的输入。
     */
    private static final Interceptor DROP_RETRY_AFTER = chain ->
            chain.proceed(chain.request()).newBuilder().removeHeader("Retry-After").build();

    private PinnedHttpClients() {}

    /**
     * 构造一个受约束的客户端。
     *
     * @param dns 域名解析器；对字面 IP 目标 OkHttp 会绕过它，字面地址的准入由调用方在构造前校验
     * @param connectTimeout 建立连接的上限
     * @param readTimeout 单次读取的上限，含等待响应首字节；{@code Duration.ZERO} 表示不限
     * @param callTimeout 整次调用的上限，含读完响应体；{@code Duration.ZERO} 表示不限
     * @return 禁止代理、重定向与自动重试的客户端
     */
    public static OkHttpClient pinned(Dns dns, Duration connectTimeout, Duration readTimeout,
            Duration callTimeout) {
        return new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(dns)
                .addNetworkInterceptor(DROP_RETRY_AFTER)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .callTimeout(callTimeout)
                .build();
    }
}
