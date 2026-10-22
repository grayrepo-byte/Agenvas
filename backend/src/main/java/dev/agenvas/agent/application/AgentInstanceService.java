package dev.agenvas.agent.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 管理项目助手的稳定身份、名称与指令；运行权限由冻结的画布范围决定。 */
@Service
public class AgentInstanceService {

    /** 项目内置导演的稳定档案标识。 */
    private static final String CREATOR_PROFILE_KEY = "creator";
    /** 初始化导演使用的内置档案版本。 */
    private static final int CREATOR_PROFILE_VERSION = 1;

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
     * 恢复项目唯一导演，首次打开时只创建配置，不调用模型。
     * 项目事件行锁串行化首次初始化；数据库唯一约束是最终防线。
     * 已有配置始终返回当前状态，不因默认提示词编辑或请求重试而回退。
     */
    @Transactional
    public AgentInstance ensureDirector(UUID ownerId, UUID projectId) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            var existing = agents.findProjectDirector(ownerId, projectId);
            if (existing.isPresent()) return ProjectEventService.Change.unchanged(existing.get());
            var directorPrompt = defaults.require(dev.agenvas.settings.application.PromptService.DIRECTOR_KEY,
                    dev.agenvas.settings.application.PromptService.Kind.AGENT);
            Instant now = clock.instant();
            AgentInstance director = new AgentInstance(UUID.randomUUID(), projectId,
                    CREATOR_PROFILE_KEY, CREATOR_PROFILE_VERSION, validateName(directorPrompt.name()),
                    validateInstruction(directorPrompt.content()), 0, now, now);
            agents.create(director);
            return ProjectEventService.Change.changed(director, agentEvent(director));
        }).value();
    }

    /** 快照只读恢复导演配置；未首次打开的项目返回空，不隐式创建配置。 */
    @Transactional(readOnly = true)
    public Optional<AgentInstance> findProjectDirector(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return agents.findProjectDirector(ownerId, projectId);
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
