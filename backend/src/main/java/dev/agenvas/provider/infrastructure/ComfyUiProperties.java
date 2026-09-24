package dev.agenvas.provider.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 管理员配置的 ComfyUI 唯一服务来源，不接受 Agent 工具参数覆盖。
 * @param endpoint 固定 ComfyUI API 基地址
 */
@ConfigurationProperties(prefix = "agenvas.provider.comfyui")
public record ComfyUiProperties(String endpoint) {}
