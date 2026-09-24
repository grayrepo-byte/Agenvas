package dev.agenvas.canvas.application;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Applies atomic canvas command batches while keeping presentation separate from content. */
@Service
public class CanvasService {

    private static final int MAX_COMMANDS = 100;
    private static final BigDecimal MIN_COORDINATE = new BigDecimal("-1000000");
    private static final BigDecimal MAX_COORDINATE = new BigDecimal("1000000");
    private static final BigDecimal MIN_WIDTH = new BigDecimal("120");
    private static final BigDecimal MAX_WIDTH = new BigDecimal("2000");
    private static final BigDecimal MIN_HEIGHT = new BigDecimal("80");
    private static final BigDecimal MAX_HEIGHT = new BigDecimal("2000");
    private static final BigDecimal OUTPUT_WIDTH = new BigDecimal("300");
    private static final BigDecimal OUTPUT_HEIGHT = new BigDecimal("300");
    private static final BigDecimal OUTPUT_GAP = new BigDecimal("24");

    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final AgentInstanceService agents;
    private final CanvasItemRepository canvasItems;
    private final ProjectEventService events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CanvasService(
            ProjectService projects,
            ArtifactService artifacts,
            AgentInstanceService agents,
            CanvasItemRepository canvasItems,
            ProjectEventService events,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.agents = agents;
        this.canvasItems = canvasItems;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Loads persisted layout and projects each Artifact card from its current server version. */
    @Transactional(readOnly = true)
    public List<CanvasEntry> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return canvasItems.list(ownerId, projectId).stream()
                .map(item -> toEntry(ownerId, item))
                .toList();
    }

    /**
     * Places a newly completed output in the Agent's output group inside the caller's task
     * transaction. Existing cards, including locked cards, are never moved or modified.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasItem placeGeneratedArtifactWithinChange(UUID ownerId, UUID projectId,
            UUID agentId, UUID artifactId) {
        projects.requireActiveProject(ownerId, projectId);
        artifacts.get(ownerId, projectId, artifactId);
        AgentInstance agent = agents.get(ownerId, projectId, agentId);
        List<CanvasItem> existing = new ArrayList<>(canvasItems.list(ownerId, projectId));
        CanvasItem agentCard = existing.stream()
                .filter(item -> item.subjectType() == CanvasItem.SubjectType.AGENT
                        && item.subjectId().equals(agentId))
                .findFirst().orElse(null);
        BigDecimal baseX = agentCard == null ? new BigDecimal("80")
                : agentCard.x().add(agentCard.width()).add(new BigDecimal("64"));
        BigDecimal baseY = agentCard == null ? new BigDecimal("80") : agentCard.y();
        if (baseX.add(OUTPUT_WIDTH).compareTo(MAX_COORDINATE) > 0) {
            baseX = new BigDecimal("80");
        }
        if (baseY.add(OUTPUT_HEIGHT).compareTo(MAX_COORDINATE) > 0) {
            baseY = new BigDecimal("80");
        }
        BigDecimal x = baseX;
        BigDecimal y = baseY;
        boolean found = false;
        for (int row = 0; row < 100 && !found; row++) {
            for (int column = 0; column < 3; column++) {
                BigDecimal candidateX = baseX.add(new BigDecimal(324L * column));
                BigDecimal candidateY = baseY.add(new BigDecimal(324L * row));
                if (candidateX.add(OUTPUT_WIDTH).compareTo(MAX_COORDINATE) > 0
                        || candidateY.add(OUTPUT_HEIGHT).compareTo(MAX_COORDINATE) > 0) {
                    continue;
                }
                if (existing.stream().noneMatch(item -> overlaps(candidateX, candidateY, item))) {
                    x = candidateX;
                    y = candidateY;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            throw validation("Agent 输出区域没有可用的画布位置。");
        }
        int zIndex = Math.min(1000, existing.stream().mapToInt(CanvasItem::zIndex)
                .max().orElse(-1) + 1);
        CanvasItem placed = placement(UUID.randomUUID(), projectId,
                CanvasItem.SubjectType.ARTIFACT, artifactId,
                x, y, OUTPUT_WIDTH, OUTPUT_HEIGHT, zIndex, agent.outputGroupId(), false);
        if (!canvasItems.create(placed)) {
            throw new IllegalStateException("Generated CanvasItem id unexpectedly collided");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("itemIds").add(placed.id().toString());
        events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                "canvas.items.changed", 1, projectId, 0, payload));
        return placed;
    }

    /**
     * Places a bounded batch in the trusted Agent output group. A second tool call with a
     * different tool_call_id reuses existing cards instead of duplicating the presentation.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OutputPlacements placeArtifactsInAgentOutputWithinChange(UUID ownerId,
            UUID projectId, UUID agentId, List<UUID> artifactIds) {
        if (artifactIds == null || artifactIds.isEmpty() || artifactIds.size() > 6
                || artifactIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(artifactIds).size() != artifactIds.size()) {
            throw validation("输出分组放置须包含 1 到 6 个互异 Artifact。");
        }
        AgentInstance agent = agents.get(ownerId, projectId, agentId);
        List<CanvasItem> created = new ArrayList<>();
        List<CanvasItem> alreadyPresent = new ArrayList<>();
        for (UUID artifactId : artifactIds) {
            artifacts.get(ownerId, projectId, artifactId);
            CanvasItem current = canvasItems.list(ownerId, projectId).stream()
                    .filter(item -> item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                            && item.subjectId().equals(artifactId)
                            && agent.outputGroupId().equals(item.groupId()))
                    .findFirst().orElse(null);
            if (current != null) {
                alreadyPresent.add(current);
            } else {
                created.add(placeGeneratedArtifactWithinChange(ownerId, projectId,
                        agentId, artifactId));
            }
        }
        return new OutputPlacements(List.copyOf(created), List.copyOf(alreadyPresent));
    }

    /** Server-owned layout identities returned by a bounded output placement command. */
    public record OutputPlacements(List<CanvasItem> created,
            List<CanvasItem> alreadyPresent) {}

    /** Tests candidate geometry with whitespace so generated cards do not cover existing work. */
    private boolean overlaps(BigDecimal x, BigDecimal y, CanvasItem existing) {
        return x.compareTo(existing.x().add(existing.width()).add(OUTPUT_GAP)) < 0
                && x.add(OUTPUT_WIDTH).add(OUTPUT_GAP).compareTo(existing.x()) > 0
                && y.compareTo(existing.y().add(existing.height()).add(OUTPUT_GAP)) < 0
                && y.add(OUTPUT_HEIGHT).add(OUTPUT_GAP).compareTo(existing.y()) > 0;
    }

    /** Applies all commands in one transaction and returns the authoritative resulting layout. */
    @Transactional
    public List<CanvasEntry> apply(
            UUID ownerId, UUID projectId, List<? extends CanvasCommand> commands) {
        return events.recordChange(ownerId, projectId, () -> {
                    List<CanvasItem> before = canvasItems.list(ownerId, projectId);
                    List<CanvasEntry> result = applyLocked(ownerId, projectId, commands);
                    List<CanvasItem> after = result.stream().map(CanvasEntry::item).toList();
                    if (before.equals(after)) {
                        return ProjectEventService.Change.unchanged(result);
                    }
                    ObjectNode payload = objectMapper.createObjectNode();
                    ArrayNode itemIds = payload.putArray("itemIds");
                    for (CanvasCommand command : commands) {
                        itemIds.add(command.itemId().toString());
                    }
                    return ProjectEventService.Change.changed(
                            result,
                            new ProjectEventService.EventDraft(
                                    "canvas.items.changed", 1, projectId, 0, payload));
                })
                .value();
    }

    private List<CanvasEntry> applyLocked(
            UUID ownerId, UUID projectId, List<? extends CanvasCommand> commands) {
        projects.requireActiveProject(ownerId, projectId);
        if (commands == null || commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            throw validation("commands 必须包含 1 到 100 个命令。");
        }
        Set<UUID> itemIds = new HashSet<>();
        for (CanvasCommand command : commands) {
            if (!itemIds.add(command.itemId())) {
                throw validation("同一批命令不能重复修改同一 CanvasItem。");
            }
            switch (command) {
                case PlaceArtifact place -> placeArtifact(ownerId, projectId, place);
                case PlaceAgent place -> placeAgent(ownerId, projectId, place);
                case UpdateLayout update -> updateLayout(ownerId, projectId, update);
                case SetLocked lock -> setLocked(ownerId, projectId, lock);
                case Remove remove -> remove(ownerId, projectId, remove);
            }
        }
        return canvasItems.list(ownerId, projectId).stream()
                .map(item -> toEntry(ownerId, item))
                .toList();
    }

    private void placeArtifact(UUID ownerId, UUID projectId, PlaceArtifact command) {
        validateGeometry(
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex());
        artifacts.get(ownerId, projectId, command.artifactId());
        CanvasItem requested = placement(
                command.itemId(),
                projectId,
                CanvasItem.SubjectType.ARTIFACT,
                command.artifactId(),
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex(),
                command.groupId(),
                command.locked());
        createOrReplay(ownerId, requested);
    }

    private void placeAgent(UUID ownerId, UUID projectId, PlaceAgent command) {
        validateGeometry(
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex());
        agents.get(ownerId, projectId, command.agentId());
        CanvasItem requested = placement(
                command.itemId(),
                projectId,
                CanvasItem.SubjectType.AGENT,
                command.agentId(),
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex(),
                command.groupId(),
                command.locked());
        createOrReplay(ownerId, requested);
    }

    private CanvasItem placement(
            UUID itemId,
            UUID projectId,
            CanvasItem.SubjectType subjectType,
            UUID subjectId,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId,
            boolean locked) {
        Instant now = clock.instant();
        return new CanvasItem(
                itemId,
                projectId,
                subjectType,
                subjectId,
                x,
                y,
                width,
                height,
                zIndex,
                groupId,
                locked,
                0,
                now,
                now);
    }

    private void createOrReplay(UUID ownerId, CanvasItem requested) {
        if (canvasItems.create(requested)) {
            return;
        }
        CanvasItem existing = canvasItems.findForUpdate(
                        ownerId, requested.projectId(), requested.id())
                .orElseThrow(this::conflict);
        if (!samePlacement(existing, requested)) {
            throw conflict();
        }
    }

    private void updateLayout(UUID ownerId, UUID projectId, UpdateLayout command) {
        validateGeometry(
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex());
        CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, command.itemId())
                .orElseThrow(this::notFound);
        if (sameLayout(current, command)
                && (current.version() == command.expectedVersion()
                        || current.version() == command.expectedVersion() + 1)) {
            return;
        }
        if (current.locked()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "CANVAS_ITEM_LOCKED",
                    "卡片已锁定",
                    "请先解锁卡片，再修改位置或大小。",
                    false);
        }
        if (current.version() != command.expectedVersion()) {
            throw conflict();
        }
        CanvasItem updated = new CanvasItem(
                current.id(),
                current.projectId(),
                current.subjectType(),
                current.subjectId(),
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex(),
                command.groupId(),
                current.locked(),
                current.version(),
                current.createdAt(),
                current.updatedAt());
        if (!canvasItems.update(ownerId, updated, command.expectedVersion(), clock.instant())) {
            throw conflict();
        }
    }

    private void setLocked(UUID ownerId, UUID projectId, SetLocked command) {
        CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, command.itemId())
                .orElseThrow(this::notFound);
        if (current.locked() == command.locked()
                && (current.version() == command.expectedVersion()
                        || current.version() == command.expectedVersion() + 1)) {
            return;
        }
        if (current.version() != command.expectedVersion()) {
            throw conflict();
        }
        CanvasItem updated = new CanvasItem(
                current.id(),
                current.projectId(),
                current.subjectType(),
                current.subjectId(),
                current.x(),
                current.y(),
                current.width(),
                current.height(),
                current.zIndex(),
                current.groupId(),
                command.locked(),
                current.version(),
                current.createdAt(),
                current.updatedAt());
        if (!canvasItems.update(ownerId, updated, command.expectedVersion(), clock.instant())) {
            throw conflict();
        }
    }

    private void remove(UUID ownerId, UUID projectId, Remove command) {
        CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, command.itemId())
                .orElse(null);
        if (current == null) {
            return;
        }
        if (current.version() != command.expectedVersion()
                || !canvasItems.delete(
                        ownerId,
                        projectId,
                        command.itemId(),
                        command.expectedVersion())) {
            throw conflict();
        }
    }

    private CanvasEntry toEntry(UUID ownerId, CanvasItem item) {
        ArtifactService.ArtifactView artifact = item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                ? artifacts.get(ownerId, item.projectId(), item.subjectId())
                : null;
        AgentInstance agent = item.subjectType() == CanvasItem.SubjectType.AGENT
                ? agents.get(ownerId, item.projectId(), item.subjectId())
                : null;
        return new CanvasEntry(item, artifact, agent);
    }

    private boolean samePlacement(CanvasItem left, CanvasItem right) {
        return left.projectId().equals(right.projectId())
                && left.subjectType() == right.subjectType()
                && left.subjectId().equals(right.subjectId())
                && compare(left.x(), right.x())
                && compare(left.y(), right.y())
                && compare(left.width(), right.width())
                && compare(left.height(), right.height())
                && left.zIndex() == right.zIndex()
                && java.util.Objects.equals(left.groupId(), right.groupId())
                && left.locked() == right.locked();
    }

    private boolean sameLayout(CanvasItem item, UpdateLayout command) {
        return compare(item.x(), command.x())
                && compare(item.y(), command.y())
                && compare(item.width(), command.width())
                && compare(item.height(), command.height())
                && item.zIndex() == command.zIndex()
                && java.util.Objects.equals(item.groupId(), command.groupId());
    }

    private boolean compare(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    private void validateGeometry(
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex) {
        if (x == null
                || y == null
                || width == null
                || height == null
                || outside(x, MIN_COORDINATE, MAX_COORDINATE)
                || outside(y, MIN_COORDINATE, MAX_COORDINATE)
                || outside(width, MIN_WIDTH, MAX_WIDTH)
                || outside(height, MIN_HEIGHT, MAX_HEIGHT)
                || zIndex < -1000
                || zIndex > 1000) {
            throw validation("画布坐标、尺寸或层级超出允许范围。");
        }
    }

    private boolean outside(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0;
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "画布卡片不存在",
                "画布卡片不存在或当前用户无权访问。",
                false);
    }

    private ApiProblemException conflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "CANVAS_VERSION_CONFLICT",
                "画布布局已更新",
                "画布卡片已被其他请求修改，请刷新后重试。",
                false);
    }

    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "画布命令无效",
                detail,
                false);
    }

    /** One supported atomic canvas mutation. */
    public sealed interface CanvasCommand
            permits PlaceArtifact, PlaceAgent, UpdateLayout, SetLocked, Remove {
        UUID itemId();
    }

    /** Adds an Artifact presentation using a client-generated id for safe retry. */
    public record PlaceArtifact(
            UUID itemId,
            UUID artifactId,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId,
            boolean locked)
            implements CanvasCommand {}

    /** Adds an Agent presentation using a client-generated id for safe retry. */
    public record PlaceAgent(
            UUID itemId,
            UUID agentId,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId,
            boolean locked)
            implements CanvasCommand {}

    /** Replaces one card's complete layout using optimistic concurrency. */
    public record UpdateLayout(
            UUID itemId,
            long expectedVersion,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId)
            implements CanvasCommand {}

    /** Toggles the layout lock without altering content or geometry. */
    public record SetLocked(UUID itemId, long expectedVersion, boolean locked)
            implements CanvasCommand {}

    /** Removes only the presentation card. */
    public record Remove(UUID itemId, long expectedVersion) implements CanvasCommand {}

    /** Canvas projection with optional subject data required by the current card type. */
    public record CanvasEntry(
            CanvasItem item,
            ArtifactService.ArtifactView artifact,
            AgentInstance agent) {}
}
