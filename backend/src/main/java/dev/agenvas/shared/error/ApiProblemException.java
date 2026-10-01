package dev.agenvas.shared.error;

import dev.agenvas.shared.i18n.ApiMessage;
import org.springframework.http.HttpStatus;

/** 携带稳定公开错误码和 HTTP 状态的业务异常，不应将内部堆栈或敏感细节写入响应。 */
public final class ApiProblemException extends RuntimeException {

    /** 映射到客户端的实际 HTTP 状态码。 */
    private final HttpStatus status;
    /** API 合约定义的稳定机器可读错误码。 */
    private final String code;
    /** 面向用户的简短错误标题。 */
    private final ApiMessage title;
    private final ApiMessage detail;
    /** 是否建议客户端在条件允许时重试。 */
    private final boolean retryable;

    /** 创建公开问题异常；保留资源键和参数，响应映射器按请求语言渲染。
     * RuntimeException 的消息仅保留中文诊断说明，不能作为本地化 HTTP 详情使用。
     * @param status 对应的 HTTP 状态码
     * @param code 稳定错误码
     * @param title 标题资源键和参数
     * @param detail 详情资源键和参数，不应包含堆栈或密钥
     * @param retryable 客户端是否可以在后续重试
     */
    public ApiProblemException(
            HttpStatus status, String code, ApiMessage title, ApiMessage detail, boolean retryable) {
        super(detail.source());
        this.status = status;
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.retryable = retryable;
    }

    /** 返回映射到 HTTP 响应的状态码。 */
    public HttpStatus status() {
        return status;
    }

    /** 返回供客户端分支处理的稳定错误码。 */
    public String code() {
        return code;
    }

    /** 返回面向用户展示的错误标题。 */
    public ApiMessage title() {
        return title;
    }

    /** Retains interpolation values for HTTP responses and recoverable local-transfer failures. */
    public ApiMessage detail() {
        return detail;
    }

    /** 返回错误是否具有可重试语义。 */
    public boolean retryable() {
        return retryable;
    }
}
