package dev.agenvas.run.application;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.domain.AgentConversation;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 会话创建、选择和消息序号；选择会话只改变当前指针，不改动活动 Run。 */
@Service
public class AgentConversationService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_LENGTH = 40;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);
    private final AgentConversationRepository conversations;
    private final AgentRunRepository runs;
    private final AgentInstanceService agents;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AgentConversationService(AgentConversationRepository conversations, AgentRunRepository runs,
            AgentInstanceService agents, ProjectService projects, ProjectEventService events,
            ObjectMapper mapper, Clock clock) {
        this.conversations = conversations;
        this.runs = runs;
        this.agents = agents;
        this.projects = projects;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConversationPage list(UUID ownerId, UUID projectId, UUID agentId,
            String encodedCursor, Integer requestedLimit) {
        agents.get(ownerId, projectId, agentId);
        int limit = pageSize(requestedLimit);
        Cursor cursor = decodeCursor(encodedCursor);
        List<AgentConversation> rows = conversations.list(ownerId, projectId, agentId,
                cursor == null ? null : cursor.updatedAt(), cursor == null ? null : cursor.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<AgentConversation> items = more ? rows.subList(0, limit) : rows;
        UUID currentId = conversations.current(ownerId, projectId, agentId)
                .map(AgentConversation::id).orElse(null);
        return new ConversationPage(List.copyOf(items), more ? encodeCursor(items.getLast()) : null, currentId);
    }

    @Transactional(readOnly = true)
    public AgentConversation get(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId) {
        return conversations.find(ownerId, projectId, agentId, conversationId).orElseThrow(this::notFound);
    }

    /** 缺省表示 Agent 当前会话；从未建立会话时只读预检返回 null。 */
    @Transactional(readOnly = true)
    public AgentConversation resolve(UUID ownerId, UUID projectId, UUID agentId, UUID requestedId) {
        agents.get(ownerId, projectId, agentId);
        return requestedId == null ? conversations.current(ownerId, projectId, agentId).orElse(null)
                : get(ownerId, projectId, agentId, requestedId);
    }

    /** 在 Run 受理所持项目锁内调用；隐式首会话与 Run 同事务提交或回滚。 */
    @Transactional
    public AgentConversation resolveOrCreate(UUID ownerId, UUID projectId, UUID agentId, UUID requestedId) {
        AgentConversation existing = resolve(ownerId, projectId, agentId, requestedId);
        return existing == null ? createWithinProjectLock(ownerId, projectId, agentId) : existing;
    }

    @Transactional
    public AgentConversation create(UUID ownerId, UUID projectId, UUID agentId, String requestedKey) {
        String key = requestedKey == null ? "" : requestedKey.trim();
        if (key.isEmpty() || key.length() > 200) throw validation("Idempotency-Key 必须为 1 到 200 个字符。");
        projects.requireActiveProject(ownerId, projectId);
        agents.get(ownerId, projectId, agentId);
        String scope = "agent:" + agentId + ":create-conversation";
        String hash = sha256(projectId + ":" + agentId);
        Instant now = clock.instant();
        if (!runs.reserveIdempotency(ownerId, scope, key, hash, now.plus(KEY_RETENTION), now)) {
            var prior = runs.findIdempotency(ownerId, scope, key).orElseThrow(this::notFound);
            if (!hash.equals(prior.requestHash())) throw validation("幂等键不属于此会话请求。");
            if (prior.resourceId() == null) throw conflict("相同会话请求正在处理，请使用原键重试。");
            return get(ownerId, projectId, agentId, prior.resourceId());
        }
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            AgentConversation created = createWithinProjectLock(ownerId, projectId, agentId);
            if (!runs.completeIdempotency(ownerId, scope, key, hash, created.id(),
                    "{\"conversationId\":\"" + created.id() + "\"}", now)) {
                throw new IllegalStateException("Conversation idempotency completion failed");
            }
            return ProjectEventService.Change.changed(created, event(ownerId, created));
        }).value();
    }

    @Transactional
    public AgentConversation select(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId) {
        return events.recordChange(ownerId, projectId, () -> {
            AgentConversation selected = get(ownerId, projectId, agentId, conversationId);
            if (!conversations.select(ownerId, projectId, agentId, conversationId)) throw notFound();
            return ProjectEventService.Change.changed(selected, event(ownerId, selected));
        }).value();
    }

    /** 一条已受理指令分配一个单调会话序号，历史读版本在写入前再次比较。 */
    @Transactional
    public AgentConversation appendTurn(UUID ownerId, AgentConversation before, String instruction,
            Long expectedVersion, Instant now) {
        if (expectedVersion != null && expectedVersion != before.version()) {
            throw conflict("会话已有新消息，请重新检查上下文后发送。");
        }
        int end = instruction.offsetByCodePoints(0,
                Math.min(TITLE_LENGTH, instruction.codePointCount(0, instruction.length())));
        if (!conversations.appendTurn(before.projectId(), before.id(), before.version(),
                instruction.substring(0, end), now)) throw conflict("会话版本已变化，请重新读取。");
        return get(ownerId, before.projectId(), before.agentInstanceId(), before.id());
    }

    /** Run 的项目事件回调结束后、同事务内发布最终会话版本，避免嵌套消费旧序号。 */
    @Transactional
    public void publishChange(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId) {
        events.append(ownerId, projectId, event(ownerId, get(ownerId, projectId, agentId, conversationId)));
    }

    private AgentConversation createWithinProjectLock(UUID ownerId, UUID projectId, UUID agentId) {
        Instant now = clock.instant();
        AgentConversation created = new AgentConversation(UUID.randomUUID(), projectId, agentId,
                "新会话", 0, 0, now, now);
        conversations.create(created);
        if (!conversations.select(ownerId, projectId, agentId, created.id())) throw notFound();
        return created;
    }

    private ProjectEventService.EventDraft event(UUID ownerId, AgentConversation conversation) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("agentId", conversation.agentInstanceId().toString());
        payload.put("conversationId", conversation.id().toString());
        var current = conversations.current(ownerId, conversation.projectId(), conversation.agentInstanceId());
        if (current.isPresent()) payload.put("currentConversationId", current.get().id().toString());
        else payload.putNull("currentConversationId");
        return new ProjectEventService.EventDraft("agent.conversation.changed", 1,
                conversation.id(), conversation.version(), payload);
    }

    private int pageSize(Integer requested) {
        int limit = requested == null ? DEFAULT_PAGE_SIZE : requested;
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw validation("limit 必须在 1 到 100 之间。");
        return limit;
    }

    private Cursor decodeCursor(String value) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > 160) throw validation("cursor 无效或已损坏。");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 3);
            if (parts.length != 3) throw new IllegalArgumentException();
            return new Cursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                    UUID.fromString(parts[2]));
        } catch (RuntimeException invalid) { throw validation("cursor 无效或已损坏。"); }
    }

    private String encodeCursor(AgentConversation conversation) {
        String raw = conversation.updatedAt().getEpochSecond() + ":" + conversation.updatedAt().getNano()
                + ":" + conversation.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "会话不存在",
                "会话不存在或不属于当前项目与 Agent。", false);
    }
    private ApiProblemException validation(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "会话请求无效", detail, false);
    }
    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "CONVERSATION_VERSION_CONFLICT", "会话已变化", detail, false);
    }
    private record Cursor(Instant updatedAt, UUID id) {}
    public record ConversationPage(List<AgentConversation> items, String nextCursor, UUID currentConversationId) {}
}
