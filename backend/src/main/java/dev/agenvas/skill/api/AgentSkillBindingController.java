package dev.agenvas.skill.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.skill.application.SkillRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Every execution configuration route requires an existing, authorized AgentInstance. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agents/{agentId}")
public class AgentSkillBindingController {
    private final SkillRunService skills;
    public AgentSkillBindingController(SkillRunService skills) {this.skills=skills;}
    @GetMapping("/skill-binding")
    public SkillRunService.BindingResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID agentId) {
        return skills.getBinding(principal.userId(),projectId,agentId);
    }
    @PutMapping("/skill-binding")
    public SkillRunService.BindingResponse save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID agentId, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody BindingRequest request) {
        return skills.saveBinding(principal.userId(),projectId,agentId,request.expectedAgentVersion(),request.skills(),key);
    }
    public record BindingRequest(@NotNull @PositiveOrZero Long expectedAgentVersion, @Valid @NotNull @jakarta.validation.constraints.Size(max = SkillRunService.MAX_SKILLS) java.util.List<SkillRunService.VersionRef> skills) {}
}
