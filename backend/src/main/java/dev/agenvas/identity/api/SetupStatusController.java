package dev.agenvas.identity.api;

import dev.agenvas.identity.application.SetupStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员初始化前可调用的只读安装状态入口，不公开账户资料。 */
@RestController
@RequestMapping("/api/v1/auth")
public class SetupStatusController {

    /** 查询是否仍需完成首次管理员初始化。 */
    private final SetupStatusService setupStatusService;

    /** 注入初始化状态查询服务。
     * @param setupStatusService 返回当前安装是否需要初始化
     */
    public SetupStatusController(SetupStatusService setupStatusService) {
        this.setupStatusService = setupStatusService;
    }

    /** 返回不可缓存的初始化标记，不暴露账户数据。
     * @return 当前安装是否需要创建管理员
     */
    @GetMapping("/setup-status")
    public ResponseEntity<SetupStatusResponse> getSetupStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SetupStatusResponse(setupStatusService.isSetupRequired()));
    }

    /** 对外公开的最小安装状态。
     * @param setupRequired 尚未初始化管理员时为 true
     */
    public record SetupStatusResponse(boolean setupRequired) {}
}
