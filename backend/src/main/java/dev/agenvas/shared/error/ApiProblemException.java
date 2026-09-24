package dev.agenvas.shared.error;

import org.springframework.http.HttpStatus;

/** 携带稳定公开错误码和 HTTP 状态的业务异常，不应将内部堆栈或敏感细节写入响应。 */
public final class ApiProblemException extends RuntimeException {

    /** 映射到客户端的实际 HTTP 状态码。 */
    private final HttpStatus status;
    /** API 合约定义的稳定机器可读错误码。 */
    private final String code;
    /** 面向用户的简短错误标题。 */
    private final String title;
    /** 是否建议客户端在条件允许时重试。 */
    private final boolean retryable;

    /** 创建公开问题异常；detail 作为 RuntimeException 消息供问题响应映射器读取。
     * @param status 对应的 HTTP 状态码
     * @param code 稳定错误码
     * @param title 用户可读标题
     * @param detail 针对本次错误的说明，不应包含堆栈或密钥
     * @param retryable 客户端是否可以在后续重试
     */
    public ApiProblemException(
            HttpStatus status, String code, String title, String detail, boolean retryable) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
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
    public String title() {
        return title;
    }

    /** 返回错误是否具有可重试语义。 */
    public boolean retryable() {
        return retryable;
    }
}
