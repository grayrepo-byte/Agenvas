package dev.agenvas.skill.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.skill.application.SkillRunService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Every execution configuration route requires an existing, authorized AgentInstance. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agents/{agentId}")
public class AgentSkillBindingController {
    private final SkillRunService skills;
    private final ApiMessages messages;
    public AgentSkillBindingController(SkillRunService skills, ApiMessages messages) {this.skills=skills;this.messages=messages;}
    @GetMapping("/skill-binding")
    public SkillRunService.BindingResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID agentId) {
        return skills.getBinding(principal.userId(),projectId,agentId);
    }
    @PutMapping("/skill-binding")
    public SkillRunService.BindingResponse save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID agentId, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody BindingRequest request) {
        return skills.saveBinding(principal.userId(),projectId,agentId,request.expectedAgentVersion(),request.skills(),key);
    }
    @PostMapping("/skill-installations") @ResponseStatus(HttpStatus.ACCEPTED)
    public SkillRunService.Installation install(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID agentId, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody InstallRequest request,
            HttpServletRequest http) {
        return localized(skills.install(principal.userId(),projectId,agentId,request.skillId(),request.skillVersionId(),key),http);
    }
    @GetMapping("/skill-installations/{operationId}")
    public SkillRunService.Installation operation(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID agentId, @PathVariable UUID operationId, HttpServletRequest http) {
        return localized(skills.getInstallation(principal.userId(),projectId,agentId,operationId),http);
    }
    private SkillRunService.Installation localized(SkillRunService.Installation response, HttpServletRequest http) {
        return new SkillRunService.Installation(response.id(),response.status(),response.skillId(),response.skillVersionId(),
                response.errorCode(),messages.persisted(response.errorDetail(),http));
    }
    public record BindingRequest(@NotNull @PositiveOrZero Long expectedAgentVersion, @Valid @NotNull @jakarta.validation.constraints.Size(max = SkillRunService.MAX_SKILLS) java.util.List<SkillRunService.VersionRef> skills) {}
    public record InstallRequest(@NotNull UUID skillId, @NotNull UUID skillVersionId) {}
}
