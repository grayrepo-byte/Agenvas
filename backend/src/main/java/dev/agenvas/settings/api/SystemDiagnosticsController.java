package dev.agenvas.settings.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.settings.application.SystemDiagnosticsService;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员只读的本地安装诊断入口，不调用可能计费的外部 Provider。 */
@RestController
@RequestMapping("/api/v1/settings/diagnostics")
public class SystemDiagnosticsController {

    /** 汇总数据库、本地存储和已配置能力状态。 */
    private final SystemDiagnosticsService diagnostics;

    /** 注入纯本地诊断服务。
     * @param diagnostics 不发起 Provider 调用的安装状态检查
     */
    public SystemDiagnosticsController(SystemDiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** 返回不可缓存的本地诊断快照，不触发外部 Provider 请求。
     * @param administrator 当前认证管理员
     * @return 安装和配置能力状态
     */
    @GetMapping
    public ResponseEntity<SystemDiagnosticsService.Snapshot> get(
            @AuthenticationPrincipal AdminPrincipal administrator) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(diagnostics.snapshot());
    }
}
