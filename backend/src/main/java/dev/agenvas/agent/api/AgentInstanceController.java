package dev.agenvas.agent.api;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for persistent Creator Agent card configuration and exact input bindings. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agents")
public class AgentInstanceController {

    private final AgentInstanceService agents;

    public AgentInstanceController(AgentInstanceService agents) {
        this.agents = agents;
    }

    /** Creates an idle Creator Agent with no implicit project-wide read scope. */
    @PostMapping
    public ResponseEntity<AgentResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateAgentRequest request) {
        AgentInstance instance = agents.create(
                principal.userId(),
                projectId,
                request.name(),
                request.instruction(),
                toInputs(request.bindings()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentResponse.from(instance));
    }

    /** Lists project Agent cards and their pinned inputs. */
    @GetMapping
    public AgentListResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return new AgentListResponse(agents.list(principal.userId(), projectId).stream()
                .map(AgentResponse::from)
                .toList());
    }

    /** Reads one nested Agent card. */
    @GetMapping("/{agentId}")
    public AgentResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID agentId) {
        return AgentResponse.from(agents.get(principal.userId(), projectId, agentId));
    }

    /** Replaces editable fields and explicit bindings under optimistic concurrency. */
    @PatchMapping("/{agentId}")
    public AgentResponse update(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID agentId,
            @Valid @RequestBody UpdateAgentRequest request) {
        return AgentResponse.from(agents.update(
                principal.userId(),
                projectId,
                agentId,
                request.expectedVersion(),
                request.name(),
                request.instruction(),
                toInputs(request.bindings())));
    }

    private List<AgentInstanceService.BindingInput> toInputs(List<AgentBindingRequest> bindings) {
        return bindings.stream()
                .map(binding -> new AgentInstanceService.BindingInput(
                        binding.artifactId(), binding.selectedVersionId()))
                .toList();
    }

    /** Complete request for one built-in Creator Agent card. */
    public record CreateAgentRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 8000) String instruction,
            @NotNull @Size(max = 40) List<@Valid AgentBindingRequest> bindings) {}

    /** Complete optimistic replacement request. */
    public record UpdateAgentRequest(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 8000) String instruction,
            @NotNull @Size(max = 40) List<@Valid AgentBindingRequest> bindings) {}

    /** Exact immutable ArtifactVersion selected as an Agent input. */
    public record AgentBindingRequest(
            @NotNull UUID artifactId, @NotNull UUID selectedVersionId) {}

    /** Public Agent configuration; runtime thread/user state is deliberately absent. */
    public record AgentResponse(
            UUID id,
            UUID projectId,
            String profileKey,
            int profileVersion,
            String name,
            String instruction,
            UUID outputGroupId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<BindingResponse> bindings) {

        public static AgentResponse from(AgentInstance instance) {
            return new AgentResponse(
                    instance.id(),
                    instance.projectId(),
                    instance.profileKey(),
                    instance.profileVersion(),
                    instance.name(),
                    instance.instruction(),
                    instance.outputGroupId(),
                    instance.version(),
                    instance.createdAt(),
                    instance.updatedAt(),
                    instance.bindings().stream().map(BindingResponse::from).toList());
        }
    }

    /** Public binding representation. */
    public record BindingResponse(
            UUID id,
            UUID artifactId,
            UUID selectedVersionId,
            AgentInstance.BindingType bindingType) {

        static BindingResponse from(AgentInstance.Binding binding) {
            return new BindingResponse(
                    binding.id(),
                    binding.artifactId(),
                    binding.selectedVersionId(),
                    binding.bindingType());
        }
    }

    /** Agent list response. */
    public record AgentListResponse(List<AgentResponse> items) {}
}
