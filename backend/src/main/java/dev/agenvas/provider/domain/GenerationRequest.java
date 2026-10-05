package dev.agenvas.provider.domain;

import java.util.Objects;
import java.util.UUID;

/** Mock 图片 Provider 的一次确定性请求；不含任意工作流或用户可控网络地址。
 * @param projectId 请求所属项目
 * @param requestKey 由持久化任务派生的稳定键，用于选择重复一致的 fixture
 * @param fixture 已通过领域约束校验的 Mock 输出
 */
public record GenerationRequest(UUID projectId, String requestKey, MockFixture fixture) {

    /** 验证 Mock Provider 不会接收缺少作用域、幂等键或输出数据的请求。
     * @param projectId 请求所属项目
     * @param requestKey 稳定非空请求键
     * @param fixture 可归档的模拟生成结果
     */
    public GenerationRequest {
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey must not be blank");
        }
        Objects.requireNonNull(fixture, "fixture must not be null");
    }
}
