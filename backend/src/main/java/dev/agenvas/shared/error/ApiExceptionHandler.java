package dev.agenvas.shared.error;

import jakarta.servlet.http.HttpServletRequest;
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

/** Converts application and validation failures into the stable ProblemDetail contract. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A browser closing an SSE socket is normal and has no writable HTTP response. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleDisconnectedStream(AsyncRequestNotUsableException exception) {
        // The servlet response has already been committed or the client has gone away.
    }

    /** Maps an explicitly classified application failure. */
    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(
            ApiProblemException exception, HttpServletRequest request) {
        return build(
                exception.status(),
                exception.code(),
                exception.title(),
                exception.getMessage(),
                exception.retryable(),
                request,
                null);
    }

    /** Maps request-body validation failures without leaking rejected values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                "请修正标记的字段后重试。",
                false,
                request,
                fields);
    }

    /** Multipart parser limits must remain a client error, not a generic server failure. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleLargeUpload(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE", "素材过大",
                "上传文件超过大小限制。", false, request, null);
    }

    /** Maps validation failures raised outside request DTO binding. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                "请求未通过安全校验。",
                false,
                request,
                null);
    }

    /** Maps malformed JSON and missing required headers as client validation failures. */
    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            Exception exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                "请求正文或必需请求头缺失。",
                false,
                request,
                null);
    }

    /** Hides unexpected implementation failures while preserving a trace identifier in logs. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        LOGGER.error("Unhandled API failure traceId={} path={}", traceId, request.getRequestURI(), exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "服务端暂时无法完成请求。");
        problem.setType(URI.create("urn:agenvas:problem:internal-error"));
        problem.setTitle("服务端错误");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("traceId", traceId);
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ResponseEntity<ProblemDetail> build(
            HttpStatus status,
            String code,
            String title,
            String detail,
            boolean retryable,
            HttpServletRequest request,
            List<FieldError> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:agenvas:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString().replace("-", ""));
        problem.setProperty("retryable", retryable);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(problem);
    }

    /** Public validation error representation that never includes submitted values. */
    public record FieldError(String field, String message) {}
}
