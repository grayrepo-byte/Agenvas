package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MockFixture;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 配置 Mock Provider 返回的确定性结果；模型计划不能自行选择 fixture。
 * @param fixture 测试所需的成功、失败或 UNKNOWN 结果
 */
@ConfigurationProperties(prefix = "agenvas.provider.mock")
public record MockProviderProperties(MockFixture fixture) {

    /** 未指定时使用成功 fixture；故障 fixture 必须由部署配置显式选择。
     * @param fixture 部署选择的模拟结果；为空时采用 SUCCESS
     */
    public MockProviderProperties {
        fixture = fixture == null ? MockFixture.SUCCESS : fixture;
    }
}
