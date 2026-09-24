package dev.agenvas.llm.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 选择无需账户的本地演示网关或数据库托管凭据的模型来源。
 * @param mode 当前 LLM 模式；为空时采用本地 Mock
 */
@ConfigurationProperties(prefix = "agenvas.llm")
public record LlmModeProperties(Mode mode) {

    /** 未配置时应用本地默认模式，避免意外发起网络模型请求。
     * @param mode 部署指定的模式；为空时选择 MOCK
     */
    public LlmModeProperties {
        mode = mode == null ? Mode.MOCK : mode;
    }

    /** 模型网关运行模式。 */
    public enum Mode {
        /** 使用本地确定性演示实现。 */
        MOCK,
        /** 使用管理员配置的模型 Provider。 */
        CONFIGURED
    }
}
