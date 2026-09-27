package dev.agenvas.provider.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务端持有的 Provider 模式与配置版本；媒体任务固定记录此版本。
 * @param mode 当前 Provider 模式名称
 * @param configVersion 管理员配置版本，必须为正数
 */
@ConfigurationProperties(prefix = "agenvas.provider")
public record ProviderProperties(String mode, int configVersion) {

    /** 启动时校验配置身份，避免创建无法追溯的媒体任务。
     * @param mode Provider 模式名称
     * @param configVersion 当前 Provider 配置版本
     */
    public ProviderProperties {
        if (mode == null || mode.isBlank() || configVersion < 1) {
            throw new IllegalArgumentException("Provider mode and positive config version are required");
        }
    }
}
