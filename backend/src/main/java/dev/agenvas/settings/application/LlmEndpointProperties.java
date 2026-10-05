package dev.agenvas.settings.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM 出站地址策略配置；环回 HTTP 必须显式开启，不放开整个私网。
 * @param allowLoopbackHttp 是否允许本机环回地址上的开发 Provider
 */
@ConfigurationProperties(prefix = "agenvas.settings.llm")
public record LlmEndpointProperties(boolean allowLoopbackHttp) {}
