package dev.agenvas.identity.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 部署时校验的管理员初始化安全配置。
 * @param bootstrapSecret 首次创建管理员时要求提供的运维秘密
 */
@Validated
@ConfigurationProperties(prefix = "agenvas.identity")
public record IdentityProperties(
        @NotBlank @Size(min = 24, max = 512) String bootstrapSecret) {

    /** 即使不通过 Compose 部署，也拒绝使用遗留示例密钥。 */
    public IdentityProperties {
        if ("local-bootstrap-secret-change-me".equals(bootstrapSecret)
                || "replace-with-a-long-random-bootstrap-secret".equals(bootstrapSecret)) {
            throw new IllegalArgumentException(
                    "Administrator bootstrap secret must be deployment-specific");
        }
    }
}
