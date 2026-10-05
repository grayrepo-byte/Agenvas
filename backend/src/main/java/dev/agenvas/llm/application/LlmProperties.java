package dev.agenvas.llm.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 与 Provider 凭证分离的模型配置版本和能力验证标记。
 * @param configVersion 环境配置来源的 LLM 版本
 * @param toolCallingVerified 是否通过工具调用能力验证
 */
@ConfigurationProperties(prefix = "agenvas.llm")
public record LlmProperties(int configVersion, boolean toolCallingVerified) {

    /** 拒绝非正配置版本，避免模型响应无法追溯到明确配置。
     * @param configVersion 环境配置版本
     * @param toolCallingVerified 工具调用验证状态
     */
    public LlmProperties {
        if (configVersion < 1) {
            throw new IllegalArgumentException("LLM configVersion must be positive");
        }
    }
}
