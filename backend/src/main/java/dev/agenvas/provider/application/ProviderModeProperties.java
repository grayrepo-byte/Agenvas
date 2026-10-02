package dev.agenvas.provider.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 选择管理员目录、无需外部账户的模拟 Provider 或旧环境 ComfyUI 适配器。
 * @param mode 启用的媒体生成模式；未配置时使用 MOCK
 */
@ConfigurationProperties(prefix = "agenvas.provider")
public record ProviderModeProperties(Mode mode) {

    /** 未显式选择模式的本地开发仍使用 Mock；部署由 Compose 选择 CONFIGURED。 */
    public ProviderModeProperties {
        mode = mode == null ? Mode.MOCK : mode;
    }

    /** 可选择的服务端媒体 Provider。 */
    public enum Mode {
        /** 生成明确标记为模拟的本地 fixture。 */
        MOCK,
        /** 调用配置好的 ComfyUI 固定工作流。 */
        COMFYUI,
        /** 仅使用管理员发布的真实媒体能力，禁止新请求使用 Mock。 */
        CONFIGURED
    }
}
