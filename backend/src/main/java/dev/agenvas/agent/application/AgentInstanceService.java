package dev.agenvas.agent.application;

import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Manages Creator Agent card configuration and exact-version input bindings. */
@Service
public class AgentInstanceService {

    private static final String CREATOR_PROFILE_KEY = "creator";
    private static final int CREATOR_PROFILE_VERSION = 1;
    private static final int MAX_BINDINGS = 40;

    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final AgentInstanceRepository agents;
    private final ProjectEventService events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentInstanceService(
            ProjectService projects,
            ArtifactService artifacts,
            AgentInstanceRepository agents,
            ProjectEventService events,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.agents = agents;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Creates one idle Creator Agent configuration with only explicitly supplied inputs. */
    @Transactional
    public AgentInstance create(
            UUID ownerId,
            UUID projectId,
            String requestedName,
            String requestedInstruction,
            List<BindingInput> requestedBindings) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentInstance created = createLocked(
                            ownerId, projectId, requestedName, requestedInstruction, requestedBindings);
                    return ProjectEventService.Change.changed(created, agentEvent(created));
                })
                .value();
    }

    private AgentInstance createLocked(
            UUID ownerId,
            UUID projectId,
            String requestedName,
            String requestedInstruction,
            List<BindingInput> requestedBindings) {
        projects.requireActiveProject(ownerId, projectId);
        Instant now = clock.instant();
        List<AgentInstance.Binding> bindings =
                validateBindings(ownerId, projectId, requestedBindings, now);
        AgentInstance instance = new AgentInstance(
                UUID.randomUUID(),
                projectId,
                CREATOR_PROFILE_KEY,
                CREATOR_PROFILE_VERSION,
                validateName(requestedName),
                validateInstruction(requestedInstruction),
                UUID.randomUUID(),
                0,
                now,
                now,
                bindings);
        agents.create(instance);
        agents.replaceBindings(projectId, instance.id(), bindings);
        return require(ownerId, projectId, instance.id());
    }

    /** Lists all project Agent cards and their explicit inputs. */
    @Transactional(readOnly = true)
    public List<AgentInstance> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return agents.list(ownerId, projectId);
    }

    /** Reads one owner-scoped Agent configuration. */
    @Transactional(readOnly = true)
    public AgentInstance get(UUID ownerId, UUID projectId, UUID agentId) {
        projects.get(ownerId, projectId);
        return require(ownerId, projectId, agentId);
    }

    /** Replaces editable configuration and bindings under one optimistic version. */
    @Transactional
    public AgentInstance update(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            long expectedVersion,
            String requestedName,
            String requestedInstruction,
            List<BindingInput> requestedBindings) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentInstance updated = updateLocked(
                            ownerId,
                            projectId,
                            agentId,
                            expectedVersion,
                            requestedName,
                            requestedInstruction,
                            requestedBindings);
                    return ProjectEventService.Change.changed(updated, agentEvent(updated));
                })
                .value();
    }

    private AgentInstance updateLocked(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            long expectedVersion,
            String requestedName,
            String requestedInstruction,
            List<BindingInput> requestedBindings) {
        projects.requireActiveProject(ownerId, projectId);
        AgentInstance current = agents.findForUpdate(ownerId, projectId, agentId)
                .orElseThrow(this::notFound);
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        Instant now = clock.instant();
        List<AgentInstance.Binding> bindings =
                validateBindings(ownerId, projectId, requestedBindings, now);
        AgentInstance replacement = new AgentInstance(
                current.id(),
                current.projectId(),
                current.profileKey(),
                current.profileVersion(),
                validateName(requestedName),
                validateInstruction(requestedInstruction),
                current.outputGroupId(),
                current.version(),
                current.createdAt(),
                current.updatedAt(),
                bindings);
        if (!agents.update(ownerId, replacement, expectedVersion, now)) {
            throw versionConflict();
        }
        agents.replaceBindings(projectId, agentId, bindings);
        return require(ownerId, projectId, agentId);
    }

    private ProjectEventService.EventDraft agentEvent(AgentInstance agent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agentId", agent.id().toString());
        return new ProjectEventService.EventDraft(
                "agent.instance.changed", 1, agent.id(), agent.version(), payload);
    }

    private List<AgentInstance.Binding> validateBindings(
            UUID ownerId,
            UUID projectId,
            List<BindingInput> requestedBindings,
            Instant now) {
        List<BindingInput> inputs = requestedBindings == null ? List.of() : requestedBindings;
        if (inputs.size() > MAX_BINDINGS) {
            throw validation("Agent 输入最多绑定 40 个 Artifact。");
        }
        Set<UUID> artifactIds = new HashSet<>();
        return inputs.stream()
                .map(input -> {
                    if (input == null
                            || input.artifactId() == null
                            || input.selectedVersionId() == null
                            || !artifactIds.add(input.artifactId())) {
                        throw validation("输入绑定必须完整且不能重复 Artifact。");
                    }
                    artifacts.requireVersion(
                            ownerId,
                            projectId,
                            input.artifactId(),
                            input.selectedVersionId());
                    return new AgentInstance.Binding(
                            UUID.randomUUID(),
                            input.artifactId(),
                            input.selectedVersionId(),
                            AgentInstance.BindingType.INPUT,
                            now);
                })
                .toList();
    }

    private AgentInstance require(UUID ownerId, UUID projectId, UUID agentId) {
        return agents.find(ownerId, projectId, agentId).orElseThrow(this::notFound);
    }

    private String validateName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw validation("Agent 名称必须为 1 至 120 个字符。");
        }
        return normalized;
    }

    private String validateInstruction(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 8_000) {
            throw validation("Agent 指令必须为 1 至 8000 个字符。");
        }
        return normalized;
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Agent 不存在",
                "Agent 不存在或当前用户无权访问。",
                false);
    }

    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "AGENT_VERSION_CONFLICT",
                "Agent 配置已更新",
                "Agent 配置已被其他请求修改，请刷新后重试。",
                false);
    }

    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Agent 配置无效",
                detail,
                false);
    }

    /** Untrusted client request to bind one exact immutable input. */
    public record BindingInput(UUID artifactId, UUID selectedVersionId) {}
}
