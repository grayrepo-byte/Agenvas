package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 本地 Mock 图片适配器；返回值明确标记模拟，不调用真实媒体 Provider。 */
@Component
public class MockGenerationGateway implements GenerationGateway {

    /** 按固定 fixture 返回确定性结果，不发起外部网络请求。
     * @param request 项目作用域、稳定请求键和明确标记的模拟结果
     * @return 模拟 Provider 接受、拒绝或结果未知的状态
     */
    @Override
    public GenerationResult submit(GenerationRequest request) {
        String providerRequestId = deterministicRequestId(request);
        return switch (request.fixture()) {
            case SUCCESS -> new GenerationResult(
                    GenerationResult.Status.COMPLETED, providerRequestId, true, null);
            case FAILURE -> new GenerationResult(
                    GenerationResult.Status.FAILED,
                    providerRequestId,
                    true,
                    "MOCK_PROVIDER_REJECTED");
            case UNKNOWN -> new GenerationResult(
                    GenerationResult.Status.UNKNOWN,
                    providerRequestId,
                    true,
                    "MOCK_PROVIDER_SUBMISSION_UNKNOWN");
        };
    }

    /** 从项目和请求键派生稳定 ID，使同一任务重放得到相同的 Provider 标识。
     * @param request 已校验的 Mock 请求
     * @return 仅用于 Mock 状态关联的请求 ID
     */
    private String deterministicRequestId(GenerationRequest request) {
        String source = request.projectId() + ":" + request.requestKey();
        UUID id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "mock-" + id;
    }
}
