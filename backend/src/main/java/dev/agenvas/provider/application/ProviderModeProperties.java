package dev.agenvas.provider.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 选择无需外部账户的模拟 Provider 或固定 ComfyUI 适配器。
 * @param mode 启用的媒体生成模式；未配置时使用 MOCK
 */
@ConfigurationProperties(prefix = "agenvas.provider")
public record ProviderModeProperties(Mode mode) {

    /** 自托管安装默认使用本地生成的 Mock 媒体。 */
    public ProviderModeProperties {
        mode = mode == null ? Mode.MOCK : mode;
    }

    /** 可选择的服务端媒体 Provider。 */
    public enum Mode {
        /** 生成明确标记为模拟的本地 fixture。 */
        MOCK,
        /** 调用配置好的 ComfyUI 固定工作流。 */
        COMFYUI
    }
}
