package dev.agenvas.settings.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.settings.application.LlmProviderConfigService;
import dev.agenvas.settings.application.LlmDiagnosticService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员专用的 LLM 配置入口；响应禁用缓存且从不序列化密钥明文。 */
@RestController
@RequestMapping("/api/v1/settings/llm")
public class LlmProviderConfigController {

    /** 读取和替换加密保存的当前配置。 */
    private final LlmProviderConfigService configs;
    /** 执行用户显式发起的连通性与工具调用诊断。 */
    private final LlmDiagnosticService diagnostics;

    /** 注入配置管理与诊断用例。
     * @param configs 当前配置查询和版本化替换服务
     * @param diagnostics 可能产生 Provider 费用的主动诊断服务
     */
    public LlmProviderConfigController(LlmProviderConfigService configs,
            LlmDiagnosticService diagnostics) {
        this.configs = configs;
        this.diagnostics = diagnostics;
    }

    /** 读取公开配置元数据，不返回密钥或加密 nonce。
     * @param administrator 当前认证管理员
     * @return 禁止缓存的配置状态
     */
    @GetMapping
    public ResponseEntity<LlmSettingsResponse> get(
            @AuthenticationPrincipal AdminPrincipal administrator) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LlmSettingsResponse.from(configs.status()));
    }

    /** 完整替换 LLM 配置；客户端必须提交当前版本和新的密钥。
     * @param administrator 当前认证管理员
     * @param request 新端点、模型、密钥和预期配置版本
     * @return 替换后的非敏感配置状态
     */
    @PutMapping
    public ResponseEntity<LlmSettingsResponse> replace(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @Valid @RequestBody ReplaceLlmSettingsRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LlmSettingsResponse.from(configs.replace(request.expectedVersion(),
                        request.endpoint(), request.modelId(), request.apiKey())));
    }

    /** 执行需要用户明确确认费用风险的两轮诊断，不会调用应用工具。
     * @param administrator 当前认证管理员
     * @param request 期望配置版本及费用确认
     * @return 诊断后的配置状态
     */
    @PostMapping("/diagnose")
    public ResponseEntity<LlmSettingsResponse> diagnose(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @Valid @RequestBody DiagnoseLlmRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LlmSettingsResponse.from(diagnostics.diagnose(
                        request.expectedVersion(), request.acknowledgeCost())));
    }

    /** LLM 诊断命令。
     * @param expectedVersion 用户查看到的配置版本；变化后拒绝执行旧诊断
     * @param acknowledgeCost 用户是否确认 Provider 可能对两次调用收费
     */
    public record DiagnoseLlmRequest(@Min(1) int expectedVersion,
            boolean acknowledgeCost) {}

    /** 完整替换配置的命令；必须显式提供新密钥，不支持以空值沿用旧密钥。
     * @param expectedVersion 客户端读取配置时的版本
     * @param endpoint Provider API 地址
     * @param modelId 要调用的模型标识
     * @param apiKey 服务端加密保存的新密钥
     */
    public record ReplaceLlmSettingsRequest(@Min(0) int expectedVersion,
            @NotBlank @Size(max = 500) String endpoint,
            @NotBlank @Size(max = 160) String modelId,
            @NotBlank @Size(min = 8, max = 4096) String apiKey) {}

    /** 可安全展示给 UI 的配置状态。
     * @param configured 当前是否已配置 Provider
     * @param version 并发更新版本
     * @param endpoint 已配置的 API 地址
     * @param modelId 当前模型标识
     * @param keyMask 密钥掩码，仅用于确认密钥已配置
     * @param toolCallingVerified 最近诊断是否验证了工具调用
     * @param updatedAt 最近更新时间
     */
    public record LlmSettingsResponse(boolean configured, int version, String endpoint,
            String modelId, String keyMask, boolean toolCallingVerified, Instant updatedAt) {
        /** 映射服务状态到不含密钥材料的响应 DTO。
         * @param value 配置服务生成的安全状态视图
         * @return UI 可用的配置状态
         */
        static LlmSettingsResponse from(LlmProviderConfigService.Status value) {
            return new LlmSettingsResponse(value.configured(), value.version(),
                    value.endpoint(), value.modelId(), value.keyMask(),
                    value.toolCallingVerified(), value.updatedAt());
        }
    }
}
