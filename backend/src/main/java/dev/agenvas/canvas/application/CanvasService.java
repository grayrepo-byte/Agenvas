package dev.agenvas.canvas.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.canvas.domain.MediaUploadPurpose;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 原子执行画布展示命令；卡片标题和布局与产物正文版本分开保存。 */
@Service
public class CanvasService {

    /** 一次画布提交允许的最大命令数。 */
    private static final int MAX_COMMANDS = 100;
    /** 卡片展示标题允许的最大字符数。 */
    private static final int MAX_TITLE_LENGTH = 160;
    private static final String DERIVATION_TITLE_SEPARATOR = " · ";
    /** 卡片坐标的最小边界。 */
    private static final BigDecimal MIN_COORDINATE = new BigDecimal("-1000000");
    /** 卡片坐标的最大边界。 */
    private static final BigDecimal MAX_COORDINATE = new BigDecimal("1000000");
    /** 卡片最小宽度。 */
    private static final BigDecimal MIN_WIDTH = new BigDecimal("120");
    /** 卡片最大宽度。 */
    private static final BigDecimal MAX_WIDTH = new BigDecimal("2000");
    /** 卡片最小高度。 */
    private static final BigDecimal MIN_HEIGHT = new BigDecimal("80");
    /** 卡片最大高度。 */
    private static final BigDecimal MAX_HEIGHT = new BigDecimal("2000");
    /** 自动放置生成产物时使用的卡片宽度。 */
    private static final BigDecimal OUTPUT_WIDTH = new BigDecimal("300");
    /** 自动放置生成产物时使用的卡片高度。 */
    private static final BigDecimal OUTPUT_HEIGHT = new BigDecimal("300");
    /** 自动放置卡片之间保留的最小空隙。 */
    private static final BigDecimal OUTPUT_GAP = new BigDecimal("24");
    private static final int MAX_OUTPUT_PLACEMENT_ATTEMPTS = 100;
    private static final int MAX_Z_INDEX = 1000;

    /** 验证项目读取与活动状态。 */
    private final ProjectService projects;
    /** 将画布产物卡片投影到当前服务端版本。 */
    private final ArtifactService artifacts;
    /** Creates and reads media working state owned by newly placed cards. */
    private final MediaDraftService mediaDrafts;
    /** 将 Agent 卡片投影到当前配置。 */
    private final AgentInstanceService agents;
    /** 保存卡片级标题与布局，不持有产物正文。 */
    private final CanvasItemRepository canvasItems;
    /** Cleans card-owned topology and downstream reference sources before deletion. */
    private final CanvasConnectionService connections;
    /** 原子提交批量画布变化及项目事件。 */
    private final ProjectEventService events;
    /** 构造变化事件的受限负载。 */
    private final ObjectMapper objectMapper;
    /** 为新布局项提供统一时间。 */
    private final Clock clock;

    /** 组装空间投影、布局并发校验和项目事件写入能力。
     * @param projects 校验项目归属和活动状态
     * @param artifacts 校验画布产物引用
     * @param agents 校验画布 Agent 卡片引用
     * @param items 读取和批量修改画布布局
     * @param events 将布局变更与项目事件一并提交
     * @param objectMapper 生成事件负载
     * @param clock 为布局更新时间提供统一时钟
     */
    public CanvasService(
            ProjectService projects,
            ArtifactService artifacts,
            MediaDraftService mediaDrafts,
            AgentInstanceService agents,
            CanvasItemRepository canvasItems,
            CanvasConnectionService connections,
            ProjectEventService events,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.mediaDrafts = mediaDrafts;
        this.agents = agents;
        this.canvasItems = canvasItems;
        this.connections = connections;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 读取持久化展示状态，并按卡片类型附加当前产物版本或 Agent 配置。 */
    @Transactional(readOnly = true)
    public List<CanvasEntry> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return canvasItems.list(ownerId, projectId).stream()
                .map(item -> toEntry(ownerId, item))
                .toList();
    }

    /** Creates an independent media work branch from one fully persisted source card. */
    @Transactional
    public DuplicateResult duplicate(UUID ownerId, UUID projectId, UUID sourceItemId,
            UUID targetItemId, long expectedSourceVersion, long expectedSourceDraftVersion,
            BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height, int zIndex) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            validateGeometry(x, y, width, height, zIndex);
            CanvasItem source = canvasItems.findForUpdate(ownerId, projectId, sourceItemId)
                    .orElseThrow(this::notFound);
            if (source.version() != expectedSourceVersion) throw conflict();
            if (source.subjectType() != CanvasItem.SubjectType.ARTIFACT) {
                throw validation(ApiMessage.of("api.canvas-service.only-picture-and-video-cards-can-copy-working-branches"));
            }
            ArtifactService.ArtifactView artifact = artifacts.get(ownerId, projectId,
                    source.subjectId());
            if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
                throw validation(ApiMessage.of("api.canvas-service.only-picture-and-video-cards-can-copy-working-branches"));
            }
            CanvasItem target = placement(targetItemId, projectId,
                    CanvasItem.SubjectType.ARTIFACT, source.subjectId(),
                    source.selectedVersionId(), source.title(), x, y, width, height,
                    zIndex, source.groupId(), false);
            if (!canvasItems.create(target)) throw conflict();
            rememberInitialMediaVersion(target);
            var draft = mediaDrafts.duplicateWithinChange(ownerId, projectId,
                    sourceItemId, targetItemId, expectedSourceDraftVersion);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("sourceCanvasItemId", sourceItemId.toString());
            payload.put("targetCanvasItemId", targetItemId.toString());
            DuplicateResult result = new DuplicateResult(toEntry(ownerId, target), draft);
            return ProjectEventService.Change.changed(result,
                    new ProjectEventService.EventDraft("canvas.item.duplicated", 1,
                            targetItemId, target.version(), payload));
        }).value();
    }

    public record DuplicateResult(CanvasEntry item,
            dev.agenvas.artifact.domain.MediaDraft draft) {}

    /** Lists this node's immutable results, excluding results owned by other work branches. */
    @Transactional(readOnly = true)
    public List<ArtifactVersion> listMediaVersions(UUID ownerId, UUID projectId, UUID itemId) {
        projects.get(ownerId, projectId);
        CanvasItem item = requireMediaItem(ownerId, projectId, itemId, false);
        Set<UUID> ids = new HashSet<>(canvasItems.mediaVersionIds(ownerId, projectId, itemId));
        return artifacts.listVersions(ownerId, projectId, item.subjectId()).stream()
                .filter(version -> ids.contains(version.id())).toList();
    }

    /** Switches only this card's result; drafts, pinned references and library defaults stay independent. */
    @Transactional
    public CanvasEntry selectMediaVersion(UUID ownerId, UUID projectId, UUID itemId,
            UUID versionId, long expectedVersion) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            CanvasItem item = requireMediaItem(ownerId, projectId, itemId, true);
            if (item.version() != expectedVersion) throw conflict();
            if (!canvasItems.mediaVersionIds(ownerId, projectId, itemId).contains(versionId)) {
                throw validation(ApiMessage.of("api.canvas-service.only-the-media-version-of-the-current-node-can-be"));
            }
            artifacts.requireVersion(ownerId, projectId, item.subjectId(), versionId);
            if (!canvasItems.selectVersion(ownerId, projectId, itemId, expectedVersion,
                    versionId, clock.instant())) throw conflict();
            mediaDrafts.setDisplayModeWithinChange(projectId, itemId,
                    dev.agenvas.artifact.domain.MediaDraft.DisplayMode.RESULT);
            CanvasItem selected = canvasItems.find(ownerId, projectId, itemId).orElseThrow(this::notFound);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("canvasItemId", itemId.toString());
            payload.put("selectedVersionId", versionId.toString());
            return ProjectEventService.Change.changed(toEntry(ownerId, selected),
                    new ProjectEventService.EventDraft("canvas.item.selected_version.changed", 1,
                            itemId, selected.version(), payload));
        }).value();
    }

    @Transactional(readOnly = true)
    public long mediaSelectionEpoch(UUID ownerId, UUID projectId, UUID itemId) {
        requireMediaItem(ownerId, projectId, itemId, false);
        return canvasItems.mediaSelectionEpoch(ownerId, projectId, itemId);
    }

    /** Missing task destinations are a normal late-result condition, not a transaction failure. */
    @Transactional(readOnly = true)
    public boolean hasArtifactItem(UUID ownerId, UUID projectId, UUID itemId, UUID artifactId) {
        return canvasItems.find(ownerId, projectId, itemId)
                .filter(item -> item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                        && item.subjectId().equals(artifactId)).isPresent();
    }

    /** Late results still belong to node history; removing the node leaves Artifact audit history. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordTaskMediaVersionWithinChange(UUID ownerId, UUID projectId, UUID itemId,
            UUID artifactId, UUID versionId) {
        CanvasItem item = canvasItems.findForUpdate(ownerId, projectId, itemId).orElse(null);
        if (item == null) return;
        if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT
                || !item.subjectId().equals(artifactId)) throw validation(ApiMessage.of("api.canvas-service.media-mission-objectives-do-not-match"));
        ArtifactVersion version = artifacts.requireVersion(ownerId, projectId, artifactId, versionId);
        canvasItems.addMediaVersion(projectId, itemId, versionId, version.createdAt());
    }

    private CanvasItem requireMediaItem(UUID ownerId, UUID projectId, UUID itemId, boolean lock) {
        CanvasItem item = (lock ? canvasItems.findForUpdate(ownerId, projectId, itemId)
                : canvasItems.find(ownerId, projectId, itemId)).orElseThrow(this::notFound);
        if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT) {
            throw validation(ApiMessage.of("api.canvas-service.only-image-and-video-nodes-have-media-versions"));
        }
        Artifact.Kind kind = artifacts.get(ownerId, projectId, item.subjectId()).artifact().kind();
        if (kind == Artifact.Kind.TEXT) {
            throw validation(ApiMessage.of("api.canvas-service.only-image-and-video-nodes-have-media-versions"));
        }
        return item;
    }

    private void rememberInitialMediaVersion(CanvasItem item) {
        if (item.selectedVersionId() != null) canvasItems.addMediaVersion(item.projectId(),
                item.id(), item.selectedVersionId(), item.createdAt());
    }

    /**
     * Creates one task-owned output branch beside a source card while the caller holds the
     * project-event transaction. The source draft is copied, but its tasks and connections are not.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasItem forkMediaOutputWithinChange(UUID ownerId, UUID projectId,
            UUID sourceItemId, UUID targetItemId, long expectedSourceDraftVersion,
            int outputIndex) {
        return createMediaOutputWithinChange(ownerId, projectId, sourceItemId, targetItemId,
                expectedSourceDraftVersion, outputIndex, MediaOutputDraft.COPY_SOURCE, null);
    }

    /** Places a result of the same remote task; no provider submission or source-draft mutation occurs. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasItem placeTaskMediaOutputWithinChange(UUID ownerId, UUID projectId, UUID sourceItemId,
            UUID artifactId, int outputIndex, JsonNode frozenInput) {
        projects.get(ownerId, projectId);
        if (outputIndex < 1 || outputIndex >= dev.agenvas.provider.domain.RunningHubDefinition.MAX_OUTPUTS) throw validation(ApiMessage.of("api.canvas-service.the-result-sequence-number-is-invalid"));
        CanvasItem source = canvasItems.findForUpdate(ownerId, projectId, sourceItemId).orElse(null);
        if (source == null) return null;
        var artifact = artifacts.get(ownerId, projectId, artifactId);
        List<CanvasItem> existing = canvasItems.list(ownerId, projectId);
        BigDecimal x = source.x().add(source.width()).add(OUTPUT_GAP);
        BigDecimal y = source.y();
        for (int attempt = 0; attempt < MAX_OUTPUT_PLACEMENT_ATTEMPTS && overlapsAny(x, y, existing); attempt++) y = y.add(source.height()).add(OUTPUT_GAP);
        int zIndex = Math.min(MAX_Z_INDEX, existing.stream().mapToInt(CanvasItem::zIndex).max().orElse(-1) + 1);
        validateGeometry(x, y, source.width(), source.height(), zIndex);
        if (overlapsAny(x, y, existing)) throw validation(ApiMessage.of("api.canvas-service.there-are-no-available-locations-near-the-additional-results"));
        CanvasItem item = placement(UUID.randomUUID(), projectId, CanvasItem.SubjectType.ARTIFACT,
                artifactId, null, derivationTitle(source.title(), Integer.toString(outputIndex + 1)), x, y, source.width(), source.height(), zIndex, source.groupId(), false);
        if (!canvasItems.create(item)) throw conflict();
        if (source.subjectId().equals(artifactId)) mediaDrafts.initializeFrozenWithinChange(projectId, item.id(), artifact.artifact().kind(), frozenInput);
        else mediaDrafts.initializeWithinChange(projectId, item.id(), false);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("itemIds").add(item.id().toString());
        events.append(ownerId, projectId, new ProjectEventService.EventDraft("canvas.items.changed", 1, projectId, 0, payload));
        return item;
    }

    private enum MediaOutputDraft { COPY_SOURCE, EMPTY }

    private CanvasItem createMediaOutputWithinChange(UUID ownerId, UUID projectId,
            UUID sourceItemId, UUID targetItemId, long expectedSourceDraftVersion,
            int outputIndex, MediaOutputDraft draftInitialization, String resultLabel) {
        if (outputIndex < 0 || outputIndex >= 4) {
            throw validation(ApiMessage.of("api.canvas-service.the-media-output-sequence-number-must-be-between-0-and"));
        }
        projects.requireActiveProject(ownerId, projectId);
        CanvasItem source = canvasItems.findForUpdate(ownerId, projectId, sourceItemId)
                .orElseThrow(this::notFound);
        if (source.subjectType() != CanvasItem.SubjectType.ARTIFACT) {
            throw validation(ApiMessage.of("api.canvas-service.only-media-cards-can-create-output-branches"));
        }
        ArtifactService.ArtifactView artifact = artifacts.get(ownerId, projectId,
                source.subjectId());
        if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
            throw validation(ApiMessage.of("api.canvas-service.only-images-and-videos-support-new-node-output"));
        }
        if (draftInitialization == MediaOutputDraft.EMPTY
                && mediaDrafts.get(ownerId, projectId, sourceItemId).version()
                        != expectedSourceDraftVersion) throw conflict();
        List<CanvasItem> existing = canvasItems.list(ownerId, projectId);
        BigDecimal x = source.x().add(source.width()).add(OUTPUT_GAP);
        BigDecimal y = source.y().add(
                source.height().add(OUTPUT_GAP).multiply(BigDecimal.valueOf(outputIndex)));
        for (int attempt = 0; attempt < MAX_OUTPUT_PLACEMENT_ATTEMPTS && overlapsAny(x, y, existing); attempt++) {
            y = y.add(source.height()).add(OUTPUT_GAP);
        }
        if (overlapsAny(x, y, existing)) throw validation(ApiMessage.of("api.canvas-service.there-is-no-available-location-near-the-new-image-node"));
        validateGeometry(x, y, source.width(), source.height(),
                Math.min(MAX_Z_INDEX, existing.stream().mapToInt(CanvasItem::zIndex).max().orElse(-1) + 1));
        int zIndex = Math.min(MAX_Z_INDEX, existing.stream().mapToInt(CanvasItem::zIndex)
                .max().orElse(-1) + 1);
        CanvasItem target = placement(targetItemId, projectId,
                CanvasItem.SubjectType.ARTIFACT, source.subjectId(), source.selectedVersionId(),
                draftInitialization == MediaOutputDraft.EMPTY
                        ? derivationTitle(source.title(), resultLabel) : source.title(),
                x, y, source.width(), source.height(), zIndex,
                source.groupId(), false);
        if (!canvasItems.create(target)) throw conflict();
        if (draftInitialization == MediaOutputDraft.EMPTY) {
            // Operation inputs live in the immutable Task, not in the result node's next draft.
            mediaDrafts.initializeWithinChange(projectId, targetItemId, false);
        } else {
            mediaDrafts.duplicateWithinChange(ownerId, projectId, sourceItemId, targetItemId,
                    expectedSourceDraftVersion);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceCanvasItemId", sourceItemId.toString());
        payload.put("targetCanvasItemId", targetItemId.toString());
        events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                "canvas.item.duplicated", 1, targetItemId, target.version(), payload));
        return target;
    }

    /** Creates a derivation with a fresh draft; immutable Task provenance retains operation inputs. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasItem forkMediaDerivationWithinChange(UUID ownerId, UUID projectId,
            UUID sourceItemId, UUID targetItemId, long expectedSourceDraftVersion,
            int outputIndex, String resultLabel) {
        CanvasItem target = createMediaOutputWithinChange(ownerId, projectId, sourceItemId,
                targetItemId, expectedSourceDraftVersion, outputIndex, MediaOutputDraft.EMPTY,
                resultLabel);
        if (target.selectedVersionId() != null) {
            connections.createMediaDerivationWithinChange(ownerId, projectId, sourceItemId,
                    target.id(), target.selectedVersionId());
        }
        return target;
    }

    /**
     * 在调用方任务事务内将新产物放入 Agent 输出分组；已有卡片包括锁定卡片均不移动、不修改。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasItem placeGeneratedArtifactWithinChange(UUID ownerId, UUID projectId,
            UUID agentId, UUID artifactId) {
        projects.requireActiveProject(ownerId, projectId);
        ArtifactService.ArtifactView artifact = artifacts.get(ownerId, projectId, artifactId);
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
            throw validation(ApiMessage.of("api.canvas-service.there-is-no-canvas-location-available-in-the-agent-output"));
        }
        int zIndex = Math.min(MAX_Z_INDEX, existing.stream().mapToInt(CanvasItem::zIndex)
                .max().orElse(-1) + 1);
        CanvasItem placed = placement(UUID.randomUUID(), projectId,
                CanvasItem.SubjectType.ARTIFACT, artifactId,
                mediaSelection(artifact), artifact.artifact().title(),
                x, y, OUTPUT_WIDTH, OUTPUT_HEIGHT, zIndex, agent.outputGroupId(), false);
        if (!canvasItems.create(placed)) {
            throw new IllegalStateException("Generated CanvasItem id unexpectedly collided");
        }
        initializeMediaDraft(projectId, placed, artifact);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("itemIds").add(placed.id().toString());
        events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                "canvas.items.changed", 1, projectId, 0, payload));
        return placed;
    }

    /** Reuses a visible exact-version source; otherwise places a separate card without changing existing selections. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasItem ensureMediaReferenceWithinChange(UUID ownerId, UUID projectId,
            UUID agentId, UUID artifactId, UUID versionId) {
        projects.requireActiveProject(ownerId, projectId);
        ArtifactVersion version = artifacts.requireVersion(ownerId, projectId, artifactId, versionId);
        CanvasItem existing = canvasItems.list(ownerId, projectId).stream()
                .filter(item -> item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                        && item.subjectId().equals(artifactId)
                        && versionId.equals(item.selectedVersionId()))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        CanvasItem placed = placeGeneratedArtifactWithinChange(ownerId, projectId, agentId, artifactId);
        if (versionId.equals(placed.selectedVersionId())) return placed;
        canvasItems.addMediaVersion(projectId, placed.id(), versionId, version.createdAt());
        return selectMediaVersion(ownerId, projectId, placed.id(), versionId, placed.version()).item();
    }

    /** 在 Agent 输出分组内放置 1 至 6 个互异产物；已存在的卡片复用，避免重复展示。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OutputPlacements placeArtifactsInAgentOutputWithinChange(UUID ownerId,
            UUID projectId, UUID agentId, List<UUID> artifactIds) {
        if (artifactIds == null || artifactIds.isEmpty() || artifactIds.size() > 6
                || artifactIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(artifactIds).size() != artifactIds.size()) {
            throw validation(ApiMessage.of("api.canvas-service.output-group-placement-must-contain-1-to-6-distinct-artifacts"));
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

    /**
     * 有界输出放置操作的结果。
     *
     * @param created 本次新建的画布卡片
     * @param alreadyPresent 已位于该 Agent 输出分组而被复用的卡片
     */
    public record OutputPlacements(List<CanvasItem> created,
            List<CanvasItem> alreadyPresent) {}

    /** 将输出卡片和间距一并计入碰撞检测，避免遮挡已有工作。 */
    private boolean overlaps(BigDecimal x, BigDecimal y, CanvasItem existing) {
        return x.compareTo(existing.x().add(existing.width()).add(OUTPUT_GAP)) < 0
                && x.add(OUTPUT_WIDTH).add(OUTPUT_GAP).compareTo(existing.x()) > 0
                && y.compareTo(existing.y().add(existing.height()).add(OUTPUT_GAP)) < 0
                && y.add(OUTPUT_HEIGHT).add(OUTPUT_GAP).compareTo(existing.y()) > 0;
    }

    private boolean overlapsAny(BigDecimal x, BigDecimal y, List<CanvasItem> existing) {
        return existing.stream().anyMatch(item -> overlaps(x, y, item));
    }

    /**
     * 在单一事务中应用命令批次；全部成功才返回数据库展示状态并追加事件，任何一项失败则整批回滚。
     * 相同命令重放不新增事件序号。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 画布所属项目
     * @param commands 要原子应用的 1 至 100 条命令，同批不能重复目标卡片
     * @return 应用后的权威画布展示状态
     */
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

    /** Fills an empty media node; uploads from a completed node derive a new result node. */
    @Transactional
    public CanvasEntry uploadVersion(UUID ownerId, UUID projectId, UUID itemId,
            UUID targetItemId, long expectedVersion, JsonNode content,
            MediaUploadPurpose purpose, UUID sourceVersionId) {
        MediaUploadPurpose uploadPurpose = purpose == null ? MediaUploadPurpose.UPLOAD : purpose;
        boolean markup = uploadPurpose == MediaUploadPurpose.BRUSH_MARKUP;
        if (markup && (sourceVersionId == null || targetItemId.equals(itemId))) {
            throw validation(ApiMessage.of("api.canvas-service.brush-annotations-must-be-fixed-to-the-source-image-version"));
        }
        if (!markup && sourceVersionId != null) {
            throw validation(ApiMessage.of("api.canvas-service.ordinary-uploads-do-not-accept-source-versions"));
        }
        ObjectNode frozenInput = markup ? objectMapper.createObjectNode() : null;
        if (markup) {
            frozenInput.put("schemaVersion", 1);
            frozenInput.put("operation", uploadPurpose.name());
            frozenInput.put("sourceCanvasItemId", itemId.toString());
            frozenInput.put("parentVersionId", sourceVersionId.toString());
        }
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, itemId)
                    .orElseThrow(this::notFound);
            if (current.subjectType() != CanvasItem.SubjectType.ARTIFACT) throw notFound();
            CanvasItem replay = canvasItems.find(ownerId, projectId, targetItemId).orElse(null);
            if (replay != null && replay.selectedVersionId() != null) {
                if (replay.subjectType() != CanvasItem.SubjectType.ARTIFACT
                        || !replay.subjectId().equals(current.subjectId())) throw conflict();
                ArtifactVersion replayVersion = artifacts.requireVersion(ownerId, projectId,
                        replay.subjectId(), replay.selectedVersionId());
                if (!replayVersion.content().equals(content)
                        || !java.util.Objects.equals(replayVersion.baseVersionId(), sourceVersionId)
                        || !java.util.Objects.equals(replayVersion.frozenInput(), frozenInput)
                        || replayVersion.createdByKind() != ArtifactVersion.CreatedByKind.USER) {
                    throw conflict();
                }
                return ProjectEventService.Change.unchanged(toEntry(ownerId, replay));
            }
            boolean fillsCurrent = targetItemId.equals(itemId);
            if (replay != null && !fillsCurrent) throw conflict();
            if (current.version() != expectedVersion) throw conflict();
            if (markup && !sourceVersionId.equals(current.selectedVersionId())) throw conflict();
            ArtifactService.ArtifactView artifact = artifacts.get(ownerId, projectId,
                    current.subjectId());
            if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
                throw validation(ApiMessage.of("api.canvas-service.only-image-and-video-cards-can-be-uploaded-with-additional"));
            }
            if (markup && artifact.artifact().kind() != Artifact.Kind.IMAGE) {
                throw validation(ApiMessage.of("api.canvas-service.brush-annotation-only-supports-images"));
            }
            CanvasItem target;
            if (fillsCurrent) {
                if (current.selectedVersionId() != null) throw conflict();
                target = current;
            } else {
                var sourceDraft = mediaDrafts.get(ownerId, projectId, itemId);
                target = forkMediaDerivationWithinChange(ownerId, projectId, itemId,
                        targetItemId, sourceDraft.version(), 0, uploadPurpose.resultLabel());
            }
            ArtifactVersion revision = artifacts.appendUserMediaVersionWithinChange(ownerId,
                    projectId, current.subjectId(), content, sourceVersionId, frozenInput);
            canvasItems.addMediaVersion(projectId, target.id(), revision.id(), revision.createdAt());
            if (!canvasItems.selectVersion(ownerId, projectId, target.id(), target.version(),
                    revision.id(), clock.instant())) {
                throw conflict();
            }
            mediaDrafts.setDisplayModeWithinChange(projectId, target.id(),
                    dev.agenvas.artifact.domain.MediaDraft.DisplayMode.RESULT);
            CanvasItem selected = canvasItems.find(ownerId, projectId, target.id())
                    .orElseThrow(this::notFound);
            ObjectNode selectionPayload = objectMapper.createObjectNode();
            selectionPayload.put("canvasItemId", target.id().toString());
            selectionPayload.put("sourceCanvasItemId", itemId.toString());
            selectionPayload.put("artifactId", current.subjectId().toString());
            selectionPayload.put("selectedVersionId", revision.id().toString());
            events.append(ownerId, projectId,
                    new ProjectEventService.EventDraft("canvas.item.selected_version.changed", 1,
                            target.id(), selected.version(), selectionPayload));
            return ProjectEventService.Change.unchanged(toEntry(ownerId, selected));
        }).value();
    }

    /**
     * Selects a generated result only when the node's selection and working draft still match.
     * Layout and title edits deliberately do not invalidate this content comparison.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean selectTaskResultWithinChange(UUID ownerId, UUID projectId, UUID itemId,
            UUID artifactId, UUID expectedParentVersionId, UUID resultVersionId,
            Long expectedSelectionEpoch, Long expectedDraftVersion) {
        CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, itemId).orElse(null);
        if (current == null
                || current.subjectType() != CanvasItem.SubjectType.ARTIFACT
                || !current.subjectId().equals(artifactId)
                || !java.util.Objects.equals(current.selectedVersionId(),
                        expectedParentVersionId)
                || (expectedSelectionEpoch != null && canvasItems.mediaSelectionEpoch(
                        ownerId, projectId, itemId) != expectedSelectionEpoch)
                || (expectedDraftVersion != null && mediaDrafts.get(ownerId, projectId,
                        itemId).version() != expectedDraftVersion)) {
            return false;
        }
        if (!canvasItems.selectVersion(ownerId, projectId, itemId, current.version(),
                resultVersionId, clock.instant())) {
            throw new IllegalStateException("Locked CanvasItem result selection failed");
        }
        mediaDrafts.setDisplayModeWithinChange(projectId, itemId,
                dev.agenvas.artifact.domain.MediaDraft.DisplayMode.RESULT);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canvasItemId", itemId.toString());
        payload.put("selectedVersionId", resultVersionId.toString());
        events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                "canvas.item.selected_version.changed", 1, itemId, current.version() + 1,
                payload));
        return true;
    }

    /** 调用方已锁定项目事件序号；逐条验证目标资源和版本后返回完整布局。 */
    private List<CanvasEntry> applyLocked(
            UUID ownerId, UUID projectId, List<? extends CanvasCommand> commands) {
        projects.requireActiveProject(ownerId, projectId);
        if (commands == null || commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            throw validation(ApiMessage.of("api.canvas-service.commands-must-contain-between-1-and-100-commands"));
        }
        Set<UUID> itemIds = new HashSet<>();
        for (CanvasCommand command : commands) {
            if (!itemIds.add(command.itemId())) {
                throw validation(ApiMessage.of("api.canvas-service.the-same-batch-of-commands-cannot-modify-the-same-canvasitem"));
            }
            switch (command) {
                case PlaceArtifact place -> placeArtifact(ownerId, projectId, place);
                case PlaceAgent place -> placeAgent(ownerId, projectId, place);
                case UpdateTitle update -> updateTitle(ownerId, projectId, update);
                case UpdateLayout update -> updateLayout(ownerId, projectId, update);
                case SetLocked lock -> setLocked(ownerId, projectId, lock);
                case Remove remove -> remove(ownerId, projectId, remove);
            }
        }
        return canvasItems.list(ownerId, projectId).stream()
                .map(item -> toEntry(ownerId, item))
                .toList();
    }

    /** 核验产物权限和坐标后创建卡片；客户端 ID 只允许完全相同的布局重放。 */
    private void placeArtifact(UUID ownerId, UUID projectId, PlaceArtifact command) {
        validateGeometry(
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex());
        ArtifactService.ArtifactView artifact = artifacts.get(
                ownerId, projectId, command.artifactId());
        CanvasItem requested = placement(
                command.itemId(),
                projectId,
                CanvasItem.SubjectType.ARTIFACT,
                command.artifactId(),
                mediaSelection(artifact),
                artifact.artifact().title(),
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex(),
                command.groupId(),
                command.locked());
        if (createOrReplay(ownerId, requested)) {
            initializeMediaDraft(projectId, requested, artifact);
        }
    }

    /** 核验 Agent 权限和坐标后创建卡片；客户端 ID 冲突时比较完整布局字段。 */
    private void placeAgent(UUID ownerId, UUID projectId, PlaceAgent command) {
        validateGeometry(
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex());
        AgentInstance agent = agents.get(ownerId, projectId, command.agentId());
        CanvasItem requested = placement(
                command.itemId(),
                projectId,
                CanvasItem.SubjectType.AGENT,
                command.agentId(),
                null,
                agent.name(),
                command.x(),
                command.y(),
                command.width(),
                command.height(),
                command.zIndex(),
                command.groupId(),
                command.locked());
        createOrReplay(ownerId, requested);
    }

    /** 为经校验的放置命令构造初始版本为零的纯布局行。 */
    private CanvasItem placement(
            UUID itemId,
            UUID projectId,
            CanvasItem.SubjectType subjectType,
            UUID subjectId,
            UUID selectedVersionId,
            String title,
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
                selectedVersionId,
                title,
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

    /** 首次创建布局项；主键重放只有全部展示字段相同才视为成功。 */
    private boolean createOrReplay(UUID ownerId, CanvasItem requested) {
        if (canvasItems.create(requested)) {
            return true;
        }
        CanvasItem existing = canvasItems.findForUpdate(
                        ownerId, requested.projectId(), requested.id())
                .orElseThrow(this::conflict);
        if (!samePlacement(existing, requested)) {
            throw conflict();
        }
        return false;
    }

    /** 只修改单张画布卡片的展示标题，不改变其业务对象或内容版本。 */
    private void updateTitle(UUID ownerId, UUID projectId, UpdateTitle command) {
        CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, command.itemId())
                .orElseThrow(this::notFound);
        String title = validateTitle(command.title());
        if (current.title().equals(title)
                && (current.version() == command.expectedVersion()
                        || current.version() == command.expectedVersion() + 1)) {
            return;
        }
        if (current.version() != command.expectedVersion()) {
            throw conflict();
        }
        CanvasItem updated = current.withTitle(title);
        if (!canvasItems.update(ownerId, updated, command.expectedVersion(), clock.instant())) {
            throw conflict();
        }
    }

    /** 完整替换布局字段；锁定卡片不可移动，更新必须匹配预期版本。 */
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
                    ApiMessage.of("api.creative-artifact-tool-service.card-locked"),
                    ApiMessage.of("api.canvas-service.please-unlock-the-card-first-before-modifying-the-position-or"),
                    false);
        }
        if (current.version() != command.expectedVersion()) {
            throw conflict();
        }
        CanvasItem updated = current.withLayout(
                command.x(), command.y(), command.width(), command.height(),
                command.zIndex(), command.groupId());
        if (!canvasItems.update(ownerId, updated, command.expectedVersion(), clock.instant())) {
            throw conflict();
        }
    }

    /** 只改变布局锁状态，不触碰内容；相同目标值可安全重放。 */
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
        CanvasItem updated = current.withLocked(command.locked());
        if (!canvasItems.update(ownerId, updated, command.expectedVersion(), clock.instant())) {
            throw conflict();
        }
    }

    /** 删除画布展示项，不删除被展示的产物或 Agent；不存在时作为幂等重放处理。 */
    private void remove(UUID ownerId, UUID projectId, Remove command) {
        CanvasItem current = canvasItems.findForUpdate(ownerId, projectId, command.itemId())
                .orElse(null);
        if (current == null) {
            return;
        }
        if (current.version() != command.expectedVersion()) {
            throw conflict();
        }
        connections.removeItemConnectionsWithinChange(ownerId, projectId, command.itemId());
        if (!canvasItems.delete(
                        ownerId,
                        projectId,
                        command.itemId(),
                        command.expectedVersion())) {
            throw conflict();
        }
    }

    /** 根据 subjectType 读取对应业务对象，画布行本身不作为内容来源。 */
    private CanvasEntry toEntry(UUID ownerId, CanvasItem item) {
        ArtifactService.ArtifactView artifact = item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                ? artifacts.get(ownerId, item.projectId(), item.subjectId())
                : null;
        ArtifactVersion selectedVersion = artifact == null ? null
                : selectedVersion(ownerId, item, artifact);
        AgentInstance agent = item.subjectType() == CanvasItem.SubjectType.AGENT
                ? agents.get(ownerId, item.projectId(), item.subjectId())
                : null;
        return new CanvasEntry(item, artifact, selectedVersion, agent);
    }

    private ArtifactVersion selectedVersion(UUID ownerId, CanvasItem item,
            ArtifactService.ArtifactView artifact) {
        if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
            return null;
        }
        if (item.selectedVersionId() == null) return null;
        return artifacts.requireVersion(ownerId, item.projectId(), item.subjectId(),
                item.selectedVersionId());
    }

    private UUID mediaSelection(ArtifactService.ArtifactView artifact) {
        return artifact.artifact().kind() != Artifact.Kind.TEXT
                ? artifact.artifact().resourceDefaultVersionId() : null;
    }

    private void initializeMediaDraft(UUID projectId, CanvasItem item,
            ArtifactService.ArtifactView artifact) {
        if (artifact.artifact().kind() != Artifact.Kind.TEXT) {
            mediaDrafts.initializeWithinChange(projectId, item.id(), item.selectedVersionId() != null);
            rememberInitialMediaVersion(item);
        }
    }

    /** 精确比较首次放置请求的所有持久化展示字段，用于客户端 ID 重放判定。 */
    private boolean samePlacement(CanvasItem left, CanvasItem right) {
        return left.projectId().equals(right.projectId())
                && left.subjectType() == right.subjectType()
                && left.subjectId().equals(right.subjectId())
                && java.util.Objects.equals(left.selectedVersionId(), right.selectedVersionId())
                && left.title().equals(right.title())
                && compare(left.x(), right.x())
                && compare(left.y(), right.y())
                && compare(left.width(), right.width())
                && compare(left.height(), right.height())
                && left.zIndex() == right.zIndex()
                && java.util.Objects.equals(left.groupId(), right.groupId())
                && left.locked() == right.locked();
    }

    /** 比较更新请求中的布局字段，BigDecimal 按数值相等而非 scale 比较。 */
    private boolean sameLayout(CanvasItem item, UpdateLayout command) {
        return compare(item.x(), command.x())
                && compare(item.y(), command.y())
                && compare(item.width(), command.width())
                && compare(item.height(), command.height())
                && item.zIndex() == command.zIndex()
                && java.util.Objects.equals(item.groupId(), command.groupId());
    }

    /** 忽略十进制 scale 比较坐标或尺寸数值。 */
    private boolean compare(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    /** 拒绝 null、越界坐标/尺寸及超出 [-1000,1000] 的层级。 */
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
                || zIndex < -MAX_Z_INDEX
                || zIndex > MAX_Z_INDEX) {
            throw validation(ApiMessage.of("api.canvas-service.canvas-coordinates-dimensions-or-levels-are-outside-the-allowed-range"));
        }
    }

    /** 判断十进制值是否落在闭区间之外。 */
    private boolean outside(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0;
    }

    /** Preserve the operation suffix within the title limit, without splitting a source emoji. */
    private String derivationTitle(String sourceTitle, String resultLabel) {
        String suffix = DERIVATION_TITLE_SEPARATOR + validateTitle(resultLabel);
        int sourceEnd = Math.min(sourceTitle.length(), MAX_TITLE_LENGTH - suffix.length());
        if (sourceEnd < 1) throw validation(ApiMessage.of("api.canvas-service.derive-operation-name-is-too-long"));
        if (sourceEnd < sourceTitle.length()
                && Character.isHighSurrogate(sourceTitle.charAt(sourceEnd - 1))
                && Character.isLowSurrogate(sourceTitle.charAt(sourceEnd))) sourceEnd--;
        return sourceTitle.substring(0, sourceEnd).stripTrailing() + suffix;
    }

    /** 规范化卡片标题并保持数据库、合约和应用层的同一长度约束。 */
    private String validateTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TITLE_LENGTH) {
            throw validation(ApiMessage.of("api.canvas-service.card-title-must-contain-1-to-160-characters"));
        }
        return normalized;
    }

    /** 将不存在和无权访问的画布项映射为相同 404。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.creative-artifact-tool-service.canvas-card-does-not-exist"),
                ApiMessage.of("api.canvas-service.the-canvas-card-does-not-exist-or-the-current-user"),
                false);
    }

    /** 映射乐观版本不符、主键异参重放和锁定冲突。 */
    private ApiProblemException conflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "CANVAS_VERSION_CONFLICT",
                ApiMessage.of("api.canvas-service.canvas-card-updated"),
                ApiMessage.of("api.canvas-service.the-canvas-card-has-been-modified-by-other-requests-please"),
                false);
    }

    /** 将坐标、尺寸、批量大小和命令结构错误映射为 HTTP 400。 */
    private ApiProblemException validation(ApiMessage detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.canvas-controller.canvas-command-is-invalid"),
                detail,
                false);
    }

    /** 画布支持的封闭命令集合；所有命令只修改空间展示状态。 */
    public sealed interface CanvasCommand
            permits PlaceArtifact, PlaceAgent, UpdateTitle, UpdateLayout, SetLocked, Remove {
        /** 返回命令明确定位的画布项，供批量操作检测重复目标。
         * @return 目标画布项 UUID
         */
        UUID itemId();
    }

    /**
     * 新增产物卡片；客户端重放必须复用同一 itemId 和完整布局。
     *
     * @param itemId 客户端生成且用于安全重试的画布项 ID
     * @param artifactId 要展示的项目产物
     * @param x 左上角横坐标
     * @param y 左上角纵坐标
     * @param width 卡片宽度
     * @param height 卡片高度
     * @param zIndex 显示层级
     * @param groupId 可选画布分组
     * @param locked 是否创建后锁定布局
     */
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

    /**
     * 新增 Agent 卡片；目标 Agent 必须属于当前项目。
     *
     * @param itemId 客户端生成且用于安全重试的画布项 ID
     * @param agentId 要展示的 Agent 卡片
     * @param x 左上角横坐标
     * @param y 左上角纵坐标
     * @param width 卡片宽度
     * @param height 卡片高度
     * @param zIndex 显示层级
     * @param groupId 可选画布分组
     * @param locked 是否创建后锁定布局
     */
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

    /**
     * 修改单张卡片的展示标题；同一业务对象的其他卡片不受影响。
     *
     * @param itemId 目标画布项
     * @param expectedVersion 客户端读取到的画布项版本
     * @param title 去除首尾空白后的新标题
     */
    public record UpdateTitle(UUID itemId, long expectedVersion, String title)
            implements CanvasCommand {}

    /**
     * 整体替换一张卡片的布局字段。
     *
     * @param itemId 目标画布项
     * @param expectedVersion 客户端读取到的布局版本
     * @param x 新横坐标
     * @param y 新纵坐标
     * @param width 新宽度
     * @param height 新高度
     * @param zIndex 新层级
     * @param groupId 新分组；null 表示移出分组
     */
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

    /**
     * 只切换布局锁，不改变卡片位置或产物内容。
     *
     * @param itemId 目标画布项
     * @param expectedVersion 客户端读取到的布局版本
     * @param locked 新锁定状态
     */
    public record SetLocked(UUID itemId, long expectedVersion, boolean locked)
            implements CanvasCommand {}

    /**
     * 删除展示卡片而保留产物和 Agent。
     *
     * @param itemId 要删除的画布项
     * @param expectedVersion 客户端读取到的布局版本
     */
    public record Remove(UUID itemId, long expectedVersion) implements CanvasCommand {}

    /**
     * 画布布局行及当前卡片类型所需的业务对象投影。
     *
     * @param item 持久化的空间展示状态
     * @param artifact subjectType 为 ARTIFACT 时的当前产物和版本
     * @param agent subjectType 为 AGENT 时的当前 Agent 配置
     */
    public record CanvasEntry(
            CanvasItem item,
            ArtifactService.ArtifactView artifact,
            ArtifactVersion selectedVersion,
            AgentInstance agent) {}
}
