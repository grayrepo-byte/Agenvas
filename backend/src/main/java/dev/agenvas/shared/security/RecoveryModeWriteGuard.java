package dev.agenvas.shared.security;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 运维完成外部提交核对前，将恢复中的安装限制为只读。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "true")
public class RecoveryModeWriteGuard implements WebMvcConfigurer {

    /** 注册只拦截写方法的恢复模式守卫。
     * @param registry Spring MVC 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WriteGuard())
                .addPathPatterns("/api/v1/projects", "/api/v1/projects/**",
                        "/api/v1/settings", "/api/v1/settings/**",
                        "/api/v1/media-templates", "/api/v1/media-templates/**",
                        "/api/v1/skills", "/api/v1/skills/**");
    }

    /** 保留认证相关入口，冻结项目资源的写操作。 */
    private static final class WriteGuard implements HandlerInterceptor {
        /** 恢复模式仅允许状态检查和认证读取，拒绝所有写请求。
         * @param request 当前 HTTP 请求
         * @param response 当前 HTTP 响应
         * @param handler Spring MVC 选定的处理器
         * @return 非恢复模式或安全读取请求返回 true
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                Object handler) {
            String method = request.getMethod();
            if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
                return true;
            }
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RECOVERY_MODE_READ_ONLY", ApiMessage.of("api.recovery-mode-write-guard.restore-verification-mode"),
                    ApiMessage.of("api.recovery-mode-write-guard.database-and-media-backups-are-being-reconciled-project-and-model"), false);
        }
    }
}
