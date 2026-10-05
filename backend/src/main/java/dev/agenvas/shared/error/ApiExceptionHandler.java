package dev.agenvas.shared.error;

import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.servlet.http.HttpServletRequest;
import dev.agenvas.shared.i18n.ApiMessages;
import java.util.Locale;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 将应用异常和校验失败转换为稳定的 ProblemDetail 响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 记录异常类别和追踪上下文，不记录请求字段值、提示词或密钥。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiMessages messages;

    public ApiExceptionHandler(ApiMessages messages) {
        this.messages = messages;
    }

    /** 浏览器关闭 SSE 连接属于正常断开，此时响应已不可写。 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleDisconnectedStream(AsyncRequestNotUsableException exception) {
        // The servlet response has already been committed or the client has gone away.
    }

    /** 将已分类的应用异常映射为约定的 HTTP 错误。 */
    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(
            ApiProblemException exception, HttpServletRequest request) {
        return build(
                exception.status(),
                exception.code(),
                exception.title(),
                exception.detail(),
                exception.retryable(),
                request,
                null);
    }

    /** 映射请求体校验错误，不回显被拒绝的字段值。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage() != null
                        ? error.getDefaultMessage() : messages.invalidField(request)))
                .toList();
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.identity-service.invalid-request"),
                ApiMessage.of("api.api-exception-handler.correct-the-marked-fields-and-try-again"),
                false,
                request,
                fields);
    }

    /** 将 multipart 大小限制映射为客户端错误，而不是通用服务端错误。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleLargeUpload(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE", ApiMessage.of("api.local-asset-storage.material-is-too-large"),
                ApiMessage.of("api.api-exception-handler.the-uploaded-file-exceeds-the-size-limit"), false, request, null);
    }

    /** 映射 DTO 绑定之外触发的参数校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.identity-service.invalid-request"),
                ApiMessage.of("api.api-exception-handler.the-request-failed-the-security-check"),
                false,
                request,
                null);
    }

    /** 将无效 JSON 和缺少必需请求头映射为客户端校验错误。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            Exception exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.identity-service.invalid-request"),
                ApiMessage.of("api.api-exception-handler.the-request-body-parameter-format-or-required-request-headers-are"),
                false,
                request,
                null);
    }

    /** 隐藏未预期的实现细节，并在日志中保留追踪标识。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        String traceId = traceId(request);
        LOGGER.error("Unhandled API failure traceId={} path={}", traceId, request.getRequestURI(), exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, messages.text(ApiMessage.of("api.api-exception-handler.the-server-cannot-complete-the-request-right-now"), request));
        problem.setType(URI.create("urn:agenvas:problem:internal-error"));
        problem.setTitle(messages.text(ApiMessage.of("api.api-exception-handler.server-error"), request));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("traceId", traceId);
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /** 构造遵循 ProblemDetail 形状的稳定错误响应，并附加追踪与字段错误信息。
     * @param status 对外 HTTP 状态码
     * @param code 稳定机器可读错误码
     * @param title 面向用户的错误标题
     * @param detail 不包含堆栈和提交值的错误说明
     * @param retryable 客户端是否可在条件允许时重试
     * @param request 当前请求，用于填充 instance 和 trace ID
     * @param fieldErrors 可选的字段级校验错误
     * @return 使用给定 HTTP 状态和问题详情的响应
     */
    private ResponseEntity<ProblemDetail> build(
            HttpStatus status,
            String code,
            ApiMessage title,
            ApiMessage detail,
            boolean retryable,
            HttpServletRequest request,
            List<FieldError> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.text(detail, request));
        problem.setType(URI.create("urn:agenvas:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(messages.text(title, request));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId(request));
        problem.setProperty("retryable", retryable);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(problem);
    }

    /** 优先使用过滤器写入的可信请求 ID；非 Servlet 场景生成本地备用 ID。 */
    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
        return value instanceof String id && id.matches("[0-9a-f]{32}")
                ? id : UUID.randomUUID().toString().replace("-", "");
    }

    /** 对外字段校验错误，不回显用户提交的字段值。
     * @param field 出错字段路径
     * @param message 已净化的校验说明
     */
    public record FieldError(String field, String message) {}
}
