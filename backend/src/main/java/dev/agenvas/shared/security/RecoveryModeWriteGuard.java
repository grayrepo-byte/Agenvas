package dev.agenvas.shared.security;

import dev.agenvas.shared.error.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Makes a restored installation read-only until an operator reconciles external submissions. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "true")
public class RecoveryModeWriteGuard implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WriteGuard())
                .addPathPatterns("/api/v1/projects", "/api/v1/projects/**",
                        "/api/v1/settings", "/api/v1/settings/**");
    }

    /** Authentication endpoints remain available; resource mutations stay frozen. */
    private static final class WriteGuard implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                Object handler) {
            String method = request.getMethod();
            if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
                return true;
            }
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RECOVERY_MODE_READ_ONLY", "恢复核对模式",
                    "数据库与媒体备份正在核对；项目和模型配置暂时只读。", false);
        }
    }
}
