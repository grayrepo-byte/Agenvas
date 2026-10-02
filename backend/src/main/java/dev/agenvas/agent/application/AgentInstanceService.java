package dev.agenvas.agent.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 管理 Creator Agent 卡片配置，并将每个输入绑定固定到不可变产物版本。 */
@Service
public class AgentInstanceService {

    /** MVP 内置创作 Agent 的稳定档案标识。 */
    private static final String CREATOR_PROFILE_KEY = "creator";
    /** 新建 Agent 使用的内置档案版本。 */
    private static final int CREATOR_PROFILE_VERSION = 1;
    /** 单个 Agent 可绑定的输入产物上限。 */
    private static final int MAX_BINDINGS = 40;

    /** 校验项目归属及是否仍可编辑。 */
    private final ProjectService projects;
    /** 校验每个被绑定的产物版本属于当前项目且可访问。 */
    private final ArtifactService artifacts;
    /** 保存 Agent 及其显式输入绑定。 */
    private final AgentInstanceRepository agents;
    /** 与 Agent 更新在同一项目序号事务中记录事件。 */
    private final ProjectEventService events;
    /** 构造仅含 Agent ID 的项目事件。 */
    private final ObjectMapper objectMapper;
    /** 为绑定创建时间提供统一时间源。 */
    private final Clock clock;

    /** 组装 Agent 配置的项目授权、输入版本校验、持久化和事件写入边界。
     * @param projects 校验项目所有者和活动状态
     * @param artifacts 校验绑定产物及精确版本
     * @param agents 保存卡片及绑定关系
     * @param events 与卡片变更事务提交项目事件
     * @param objectMapper 构造结构化事件负载
     * @param clock 为创建和更新时间提供统一时钟
     */
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

    /**
     * 创建未运行的 Creator Agent；输入必须逐项显式绑定，不会自动读取项目所有产物。
     * Agent 行、绑定和项目事件在同一事务中写入。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId Agent 卡片所属项目
     * @param requestedName 展示名称，去除首尾空白后限 120 字符
     * @param requestedInstruction 卡片指令，去除首尾空白后限 8,000 字符
     * @param requestedBindings 用户选定的产物 ID 与精确版本 ID；最多 40 项且不可重复
     * @return 已持久化并回读的 Agent 配置
     */
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

    /** 调用方须持有项目事件锁；检查项目后创建 Agent 和绑定行。 */
    private AgentInstance createLocked(
            UUID ownerId,
            UUID projectId,
            String requestedName,
            String requestedInstruction,
            List<BindingInput> requestedBindings) {
        projects.requireActiveProject(ownerId, projectId);
        Instant now = clock.instant();
        List<AgentInstance.Binding> bindings =
                validateBindings(ownerId, projectId, requestedBindings, now, Map.of());
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

    /** 在项目归属范围内列出 Agent 卡片及其显式输入绑定。 */
    @Transactional(readOnly = true)
    public List<AgentInstance> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return agents.list(ownerId, projectId);
    }

    /** 在项目归属范围内读取 Agent 配置，不存在与越权统一返回 404。 */
    @Transactional(readOnly = true)
    public AgentInstance get(UUID ownerId, UUID projectId, UUID agentId) {
        projects.get(ownerId, projectId);
        return require(ownerId, projectId, agentId);
    }

    /**
     * 以调用方读取到的版本替换可编辑字段和全部绑定；旧版本冲突时不覆盖并发修改。
     * 成功后 Agent 版本、绑定集合和项目事件在同一事务中提交。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId Agent 所属项目
     * @param agentId 要修改的 Agent
     * @param expectedVersion 调用方读取到的乐观锁版本
     * @param requestedName 新展示名称
     * @param requestedInstruction 新卡片指令
     * @param requestedBindings 要完整替换成的显式产物版本绑定
     * @return 更新后从数据库读取的 Agent 配置
     */
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

    /** 在事件事务内锁定 Agent 行，完成版本比较后替换绑定集合。 */
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
        Map<UUID, AgentInstance.Binding> connectedImages = current.bindings().stream()
                .filter(binding -> artifacts.get(ownerId, projectId, binding.artifactId())
                        .artifact().kind()
                        == dev.agenvas.artifact.domain.Artifact.Kind.IMAGE)
                .collect(java.util.stream.Collectors.toMap(
                        AgentInstance.Binding::artifactId, binding -> binding));
        Map<UUID, UUID> allowedImageVersions = connectedImages.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().selectedVersionId()));
        List<AgentInstance.Binding> validated = validateBindings(ownerId, projectId,
                requestedBindings, now, allowedImageVersions);
        List<AgentInstance.Binding> bindings = new java.util.ArrayList<>(validated.stream()
                .filter(binding -> !connectedImages.containsKey(binding.artifactId()))
                .toList());
        // IMAGE bindings are a projection of persistent canvas connections. Settings edits may
        // retain them but cannot create or remove them independently of that topology.
        bindings.addAll(connectedImages.values());
        if (bindings.size() > MAX_BINDINGS) {
            throw validation(ApiMessage.of("api.agent-instance-service.agent-input-can-bind-up-to-40-artifacts"));
        }
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

    /** Advances the shared configuration CAS for independent Agent settings in the caller's project transaction. */
    public AgentInstance touchConfigurationWithinChange(UUID ownerId, UUID projectId, UUID agentId, long expectedVersion) {
        projects.requireActiveProject(ownerId, projectId);
        AgentInstance current = agents.findForUpdate(ownerId, projectId, agentId).orElseThrow(this::notFound);
        if (current.version() != expectedVersion || !agents.update(ownerId, current, expectedVersion, clock.instant())) {
            throw versionConflict();
        }
        return require(ownerId, projectId, agentId);
    }

    /** Adds an exact image version captured by a CanvasItem connection. */
    public AgentInstance addImageBindingWithinChange(UUID ownerId, UUID projectId,
            UUID agentId, long expectedVersion, UUID artifactId, UUID versionId) {
        AgentInstance current = agents.findForUpdate(ownerId, projectId, agentId)
                .orElseThrow(this::notFound);
        if (current.version() != expectedVersion) throw versionConflict();
        ArtifactService.ArtifactView target = artifacts.get(ownerId, projectId, artifactId);
        if (target.artifact().kind() != dev.agenvas.artifact.domain.Artifact.Kind.IMAGE) {
            throw validation(ApiMessage.of("api.agent-instance-service.agent-image-connections-can-only-be-bound-to-the-image"));
        }
        artifacts.requireVersion(ownerId, projectId, artifactId, versionId);
        AgentInstance.Binding existing = current.bindings().stream()
                .filter(binding -> binding.artifactId().equals(artifactId))
                .findFirst().orElse(null);
        if (existing != null) {
            if (!existing.selectedVersionId().equals(versionId)) {
                throw validation(ApiMessage.of("api.agent-instance-service.the-same-agent-cannot-bind-different-versions-of-the-same"));
            }
            return current;
        }
        if (current.bindings().size() >= MAX_BINDINGS) {
            throw validation(ApiMessage.of("api.agent-instance-service.agent-input-can-bind-up-to-40-artifacts"));
        }
        List<AgentInstance.Binding> bindings = new java.util.ArrayList<>(current.bindings());
        bindings.add(new AgentInstance.Binding(UUID.randomUUID(), artifactId, versionId,
                clock.instant()));
        return replaceBindingsWithinChange(ownerId, current, bindings);
    }

    /** Removes an image binding after its final CanvasItem connection disappears. */
    public AgentInstance removeImageBindingWithinChange(UUID ownerId, UUID projectId,
            UUID agentId, long expectedVersion, UUID versionId) {
        AgentInstance current = agents.findForUpdate(ownerId, projectId, agentId)
                .orElseThrow(this::notFound);
        if (current.version() != expectedVersion) throw versionConflict();
        List<AgentInstance.Binding> bindings = current.bindings().stream()
                .filter(binding -> !binding.selectedVersionId().equals(versionId)).toList();
        return bindings.size() == current.bindings().size()
                ? current : replaceBindingsWithinChange(ownerId, current, bindings);
    }

    private AgentInstance replaceBindingsWithinChange(UUID ownerId, AgentInstance current,
            List<AgentInstance.Binding> bindings) {
        Instant now = clock.instant();
        AgentInstance replacement = new AgentInstance(current.id(), current.projectId(),
                current.profileKey(), current.profileVersion(), current.name(),
                current.instruction(), current.outputGroupId(), current.version(),
                current.createdAt(), current.updatedAt(), List.copyOf(bindings));
        if (!agents.update(ownerId, replacement, current.version(), now)) {
            throw versionConflict();
        }
        agents.replaceBindings(current.projectId(), current.id(), bindings);
        return require(ownerId, current.projectId(), current.id());
    }

    /** Agent 事件只包含 ID 和版本，不广播卡片指令或绑定的媒体内容。 */
    private ProjectEventService.EventDraft agentEvent(AgentInstance agent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agentId", agent.id().toString());
        return new ProjectEventService.EventDraft(
                "agent.instance.changed", 1, agent.id(), agent.version(), payload);
    }

    /** 每个绑定都重新鉴权并检查精确版本；拒绝空项、重复产物及超过 40 项的请求。 */
    private List<AgentInstance.Binding> validateBindings(
            UUID ownerId,
            UUID projectId,
            List<BindingInput> requestedBindings,
            Instant now,
            Map<UUID, UUID> allowedImageVersions) {
        List<BindingInput> inputs = requestedBindings == null ? List.of() : requestedBindings;
        if (inputs.size() > MAX_BINDINGS) {
            throw validation(ApiMessage.of("api.agent-instance-service.agent-input-can-bind-up-to-40-artifacts"));
        }
        Set<UUID> artifactIds = new HashSet<>();
        return inputs.stream()
                .map(input -> {
                    if (input == null
                            || input.artifactId() == null
                            || input.selectedVersionId() == null
                            || !artifactIds.add(input.artifactId())) {
                        throw validation(ApiMessage.of("api.agent-instance-service.input-bindings-must-be-complete-and-cannot-duplicate-artifacts"));
                    }
                    ArtifactService.ArtifactView target = artifacts.get(
                            ownerId, projectId, input.artifactId());
                    artifacts.requireVersion(ownerId, projectId, input.artifactId(),
                            input.selectedVersionId());
                    if (target.artifact().kind()
                            == dev.agenvas.artifact.domain.Artifact.Kind.IMAGE
                            && !input.selectedVersionId().equals(
                                    allowedImageVersions.get(input.artifactId()))) {
                        throw validation(ApiMessage.of("api.agent-instance-service.agent-image-inputs-can-only-be-added-or-removed-via"));
                    }
                    return new AgentInstance.Binding(
                            UUID.randomUUID(),
                            input.artifactId(),
                            input.selectedVersionId(),
                            now);
                })
                .toList();
    }

    /** 在所有者和项目范围内读取，避免暴露其他项目中 Agent 是否存在。 */
    private AgentInstance require(UUID ownerId, UUID projectId, UUID agentId) {
        return agents.find(ownerId, projectId, agentId).orElseThrow(this::notFound);
    }

    /** 去除名称首尾空白并限制为 1 至 120 个字符。 */
    private String validateName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw validation(ApiMessage.of("api.agent-instance-service.agent-name-must-be-1-to-120-characters"));
        }
        return normalized;
    }

    /** 去除卡片指令首尾空白并限制为 1 至 8,000 个字符。 */
    private String validateInstruction(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 8_000) {
            throw validation(ApiMessage.of("api.agent-instance-service.agent-directive-must-be-1-to-8000-characters"));
        }
        return normalized;
    }

    /** 将不存在和无权访问映射为相同的 404，避免资源枚举。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.agent-instance-service.agent-does-not-exist"),
                ApiMessage.of("api.agent-instance-service.the-agent-does-not-exist-or-the-current-user-does"),
                false);
    }

    /** 乐观锁未更新任何行时返回稳定冲突码，调用方需刷新后重试。 */
    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "AGENT_VERSION_CONFLICT",
                ApiMessage.of("api.agent-instance-service.agent-configuration-updated"),
                ApiMessage.of("api.agent-instance-service.the-agent-configuration-has-been-modified-by-other-requests-please"),
                false);
    }

    /** 将绑定或字段校验失败映射为稳定的 HTTP 400 错误。 */
    private ApiProblemException validation(ApiMessage detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.agent-instance-service.agent-configuration-is-invalid"),
                detail,
                false);
    }

    /** 未可信客户端提交的单条绑定请求；服务层仍需重新校验资源权限与版本。
     * @param artifactId 用户要绑定的产物
     * @param selectedVersionId 用户选择的不可变版本
     */
    public record BindingInput(UUID artifactId, UUID selectedVersionId) {}
}
