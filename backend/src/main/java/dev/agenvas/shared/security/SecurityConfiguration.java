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

/** Defines session authentication, CSRF protection, and uniform API security failures. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Protects every API except the small setup/login bootstrap surface and health probes. */
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

    /** Uses the database administrator provider for explicit JSON login. */
    @Bean
    AuthenticationManager authenticationManager(AdminAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /** Encodes passwords with Spring Security's versioned delegating format. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Persists authenticated contexts in Spring Session JDBC. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Exposes a readable random CSRF cookie that must be echoed through a custom header. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    /** Delegates unauthenticated filter failures to the shared ProblemDetail mapper. */
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

    /** Delegates CSRF and authorization failures to the shared ProblemDetail mapper. */
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

    /** Provides an injectable UTC time source for authentication policy and tests. */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
