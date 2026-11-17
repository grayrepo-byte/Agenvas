package dev.agenvas.mcp.security;

import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpHttpEndpoints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Only the exact MCP transport and binary upload endpoints use native-client Bearer security. */
@Configuration(proxyBeanMethods = false)
public class McpSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http, McpTokenService tokens,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) throws Exception {
        return http
                .securityMatcher(request -> (request.getContextPath() + McpHttpEndpoints.TRANSPORT)
                        .equals(request.getRequestURI())
                        || (request.getContextPath() + McpHttpEndpoints.UPLOAD).equals(request.getRequestURI()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(new McpBearerAuthenticationFilter(tokens, resolver),
                        AnonymousAuthenticationFilter.class)
                .addFilterAfter(new McpRequestLimitsFilter(resolver, recoveryMode), McpBearerAuthenticationFilter.class)
                .build();
    }
}
