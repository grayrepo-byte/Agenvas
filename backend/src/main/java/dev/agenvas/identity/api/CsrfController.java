package dev.agenvas.identity.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 向前端提供写请求必须回传的 CSRF Token。 */
@RestController
@RequestMapping("/api/v1/auth")
public class CsrfController {

    /** 触发延迟 Token 创建，并仅返回前端构造请求头所需的信息。 */
    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    /** 前端提交写请求所需的 CSRF 元数据。
     * @param headerName 后续请求必须使用的 CSRF 请求头名称
     * @param token 当前会话的 CSRF token
     */
    public record CsrfResponse(String headerName, String token) {}
}
