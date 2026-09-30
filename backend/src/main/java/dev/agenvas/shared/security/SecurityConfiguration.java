package dev.agenvas.shared.security;

import dev.agenvas.identity.infrastructure.AdminAuthenticationProvider;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 配置会话认证、CSRF 防护及统一的 API 安全错误响应。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** 保护所有 API，仅初始化、登录和健康检查入口按规则开放。 */
    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/setup-status",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/setup",
                                "/api/v1/auth/login",
                                "/error",
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness")
                        .permitAll()
                        .requestMatchers("/api/v1/settings/media-connections/**",
                                "/api/v1/settings/media-connections",
                                "/api/v1/settings/media-defaults/**", "/api/v1/call-logs",
                                "/api/v1/settings/system-logs")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    /** 使用数据库管理员身份提供器处理显式 JSON 登录。 */
    @Bean
    AuthenticationManager authenticationManager(AdminAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /** 使用 Spring Security 的带算法版本标记格式保存密码哈希。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 通过 Spring Session JDBC 持久化认证上下文。 */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 设置可由前端读取的随机 CSRF Cookie，写请求必须通过自定义请求头回传。 */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    /** 将未认证过滤器错误交给统一 ProblemDetail 映射器。 */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, exception) -> resolver.resolveException(
                request,
                response,
                null,
                new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                        "需要登录",
                        "请登录后继续。",
                        false));
    }

    /** 将 CSRF 和授权错误交给统一 ProblemDetail 映射器。 */
    @Bean
    AccessDeniedHandler accessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, exception) -> resolver.resolveException(
                request,
                response,
                null,
                new ApiProblemException(
                        HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED",
                        "请求被拒绝",
                        "请求缺少有效的权限或 CSRF 凭据。",
                        false));
    }

    /** 提供可注入的 UTC 时间源，供认证策略和测试使用。 */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
