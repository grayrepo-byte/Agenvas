package dev.agenvas.agent.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 管理项目助手的稳定身份、名称与指令；运行权限由冻结的画布范围决定。 */
@Service
public class AgentInstanceService {

    /** MVP 内置创作 Agent 的稳定档案标识。 */
    private static final String CREATOR_PROFILE_KEY = "creator";
    /** 新建 Agent 使用的内置档案版本。 */
    private static final int CREATOR_PROFILE_VERSION = 1;
    private static final int MAX_CREATE_KEY_LENGTH = 200;
    private static final java.time.Duration CREATE_KEY_RETENTION = java.time.Duration.ofDays(7);

    /** 校验项目归属及是否仍可编辑。 */
    private final ProjectService projects;
    /** 保存助手配置。 */
    private final AgentInstanceRepository agents;
    /** 与 Agent 更新在同一项目序号事务中记录事件。 */
    private final ProjectEventService events;
    /** 构造仅含 Agent ID 的项目事件。 */
    private final ObjectMapper objectMapper;
    /** 为配置创建时间提供统一时间源。 */
    private final Clock clock;
    private final dev.agenvas.settings.application.PromptService defaults;

    /** 组装项目助手配置的项目授权、持久化和事件写入边界。
     * @param projects 校验项目所有者和活动状态
     * @param agents 保存助手配置
     * @param events 与助手变更事务提交项目事件
     * @param objectMapper 构造结构化事件负载
     * @param clock 为创建和更新时间提供统一时钟
     */
    public AgentInstanceService(
            ProjectService projects,
            AgentInstanceRepository agents,
            ProjectEventService events,
            ObjectMapper objectMapper,
            Clock clock,
            dev.agenvas.settings.application.PromptService defaults) {
        this.projects = projects;
        this.agents = agents;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.defaults = defaults;
    }

    /**
     * 创建未运行的项目助手；助手配置和项目事件在同一事务中写入。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 项目助手所属项目
     * @param requestedName 展示名称，去除首尾空白后限 120 字符
     * @param requestedInstruction 助手指令，去除首尾空白后限 8,000 字符
     * @return 已持久化并回读的 Agent 配置
     */
    @Transactional
    public AgentInstance create(
            UUID ownerId,
            UUID projectId,
            String requestedName,
            String requestedInstruction) {
        return create(ownerId, projectId, requestedName, requestedInstruction, null);
    }

    @Transactional
    public AgentInstance create(UUID ownerId, UUID projectId, String requestedName, String requestedInstruction,
            String promptKey) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentInstance created = createLocked(
                            ownerId, projectId, requestedName, requestedInstruction, promptKey);
                    return ProjectEventService.Change.changed(created, agentEvent(created));
                })
                .value();
    }

    /** The original request hash excludes resolved defaults, so a later preset edit cannot alter replay. */
    @Transactional
    public CreateResult createIdempotent(UUID ownerId, UUID projectId, String name, String instruction,
            String requestedKey) {
        return createIdempotent(ownerId, projectId, name, instruction, requestedKey, null);
    }

    @Transactional
    public CreateResult createIdempotent(UUID ownerId, UUID projectId, String name, String instruction,
            String requestedKey, String promptKey) {
        if (requestedKey == null) return new CreateResult(create(ownerId, projectId, name, instruction, promptKey), false);
        projects.requireActiveProject(ownerId, projectId);
        String key = requestedKey.trim();
        if (key.isEmpty() || key.length() > MAX_CREATE_KEY_LENGTH) throw validation(
                ApiMessage.of("api.artifact-service.idempotency-key-must-be-1-to-200-characters"));
        String scope = "project:" + projectId + ":create-agent";
        var request = objectMapper.createObjectNode().put("name", name).put("instruction", instruction);
        if (promptKey != null) request.put("promptKey", promptKey);
        String hash = dev.agenvas.shared.crypto.Sha256.hex(objectMapper.writeValueAsString(request));
        Instant now = clock.instant();
        if (!agents.reserveCreateKey(ownerId, scope, key, hash, now, now.plus(CREATE_KEY_RETENTION))) {
            var prior = agents.findCreateKey(ownerId, scope, key).orElseThrow(() -> new ApiProblemException(
                    HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", ApiMessage.of("api.artifact-service.the-same-request-is-being-processed"),
                    ApiMessage.of("api.artifact-service.please-try-again-later-with-the-same-idempotency-key"), true));
            if (!hash.equals(prior.requestHash())) throw new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    ApiMessage.of("api.artifact-service.idempotent-keys-have-been-used-for-different-requests"),
                    ApiMessage.of("api.artifact-service.please-use-new-idempotency-key-for-different-product-content"), false);
            require(ownerId, projectId, prior.agentId());
            return new CreateResult(objectMapper.readValue(prior.responseJson(), AgentInstance.class), true);
        }
        AgentInstance created = create(ownerId, projectId, name, instruction, promptKey);
        if (!agents.completeCreateKey(ownerId, scope, key, hash, created.id(), objectMapper.writeValueAsString(created), now))
            throw new IllegalStateException("Agent creation command could not be completed");
        return new CreateResult(created, false);
    }

    public record CreateResult(AgentInstance agent, boolean replayed) {}

    /** 调用方须持有项目事件锁；检查项目后创建助手配置。 */
    private AgentInstance createLocked(
            UUID ownerId,
            UUID projectId,
            String requestedName,
            String requestedInstruction,
            String promptKey) {
        projects.requireActiveProject(ownerId, projectId);
        var preset = requestedName == null || requestedInstruction == null || promptKey != null
                ? defaults.require(promptKey == null ? dev.agenvas.settings.application.PromptService.DIRECTOR_KEY : promptKey,
                        dev.agenvas.settings.application.PromptService.Kind.AGENT) : null;
        Instant now = clock.instant();
        AgentInstance instance = new AgentInstance(
                UUID.randomUUID(),
                projectId,
                CREATOR_PROFILE_KEY,
                CREATOR_PROFILE_VERSION,
                validateName(requestedName == null ? preset.name() : requestedName),
                validateInstruction(requestedInstruction == null ? preset.content() : requestedInstruction),
                0,
                now,
                now);
        agents.create(instance);
        return require(ownerId, projectId, instance.id());
    }

    /** 在项目归属范围内列出项目助手配置。 */
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
     * 以调用方读取到的版本替换名称和指令；旧版本冲突时不覆盖并发修改。
     * 成功后助手版本和项目事件在同一事务中提交。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId Agent 所属项目
     * @param agentId 要修改的 Agent
     * @param expectedVersion 调用方读取到的乐观锁版本
     * @param requestedName 新展示名称
     * @param requestedInstruction 新助手指令
     * @return 更新后从数据库读取的 Agent 配置
     */
    @Transactional
    public AgentInstance update(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            long expectedVersion,
            String requestedName,
            String requestedInstruction) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentInstance updated = updateLocked(
                            ownerId,
                            projectId,
                            agentId,
                            expectedVersion,
                            requestedName,
                            requestedInstruction);
                    return ProjectEventService.Change.changed(updated, agentEvent(updated));
                })
                .value();
    }

    /** 在事件事务内锁定 Agent 行，完成版本比较后更新配置。 */
    private AgentInstance updateLocked(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            long expectedVersion,
            String requestedName,
            String requestedInstruction) {
        projects.requireActiveProject(ownerId, projectId);
        AgentInstance current = agents.findForUpdate(ownerId, projectId, agentId)
                .orElseThrow(this::notFound);
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        Instant now = clock.instant();
        AgentInstance replacement = new AgentInstance(
                current.id(),
                current.projectId(),
                current.profileKey(),
                current.profileVersion(),
                validateName(requestedName),
                validateInstruction(requestedInstruction),
                current.version(),
                current.createdAt(),
                current.updatedAt());
        if (!agents.update(ownerId, replacement, expectedVersion, now)) {
            throw versionConflict();
        }
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

    /** Agent 事件只包含 ID 和版本，不广播助手指令或媒体内容。 */
    private ProjectEventService.EventDraft agentEvent(AgentInstance agent) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agentId", agent.id().toString());
        return new ProjectEventService.EventDraft(
                "agent.instance.changed", 1, agent.id(), agent.version(), payload);
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

    /** 去除助手指令首尾空白并限制为 1 至 8,000 个字符。 */
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

    /** 将字段校验失败映射为稳定的 HTTP 400 错误。 */
    private ApiProblemException validation(ApiMessage detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.agent-instance-service.agent-configuration-is-invalid"),
                detail,
                false);
    }

}
