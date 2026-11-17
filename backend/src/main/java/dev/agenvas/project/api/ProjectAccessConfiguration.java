package dev.agenvas.project.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.project.application.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** User HTTP reads must not expose deleted projects through owner-scoped resource repositories.
 * Execution services retain their separate access for already accepted work and audit.
 */
@Configuration(proxyBeanMethods = false)
public class ProjectAccessConfiguration implements WebMvcConfigurer {
    private final ProjectService projects;

    public ProjectAccessConfiguration(ProjectService projects) {
        this.projects = projects;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (variables instanceof Map<?, ?> path && path.get("projectId") instanceof String id
                        && request.getUserPrincipal() instanceof Authentication authentication
                        && authentication.getPrincipal() instanceof AdminPrincipal principal) {
                    // Only the root DELETE bypasses visibility so a lost successful response can be replayed.
                    boolean deleteReplay = request.getMethod().equals("DELETE")
                            && handler instanceof HandlerMethod mapped
                            && mapped.getBeanType() == ProjectController.class;
                    UUID projectId;
                    try {
                        projectId = UUID.fromString(id);
                    } catch (IllegalArgumentException invalidId) {
                        return true;
                    } // Let MVC report path validation.
                    if (!deleteReplay)
                        projects.get(principal.userId(), projectId);
                }
                return true;
            }
        }).addPathPatterns("/api/v1/projects/{projectId}", "/api/v1/projects/{projectId}/**");
    }
}
