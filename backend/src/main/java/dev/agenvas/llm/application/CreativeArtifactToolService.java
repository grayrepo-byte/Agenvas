package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Bounded creative commands shared by the durable tool executor and Artifact validation. */
@Service
public class CreativeArtifactToolService {

    private static final Set<String> CHARACTER_FIELDS = Set.of(
            "name", "description", "appearance", "referenceVersionIds");
    private static final Set<String> SCENE_FIELDS = Set.of(
            "name", "location", "timeOfDay", "lighting", "style", "referenceVersionIds");
    private static final Set<String> SHOT_FIELDS = Set.of(
            "title", "order", "durationMs", "description", "camera", "action",
            "characterVersionIds", "sceneVersionId");
    private static final Set<String> REVISE_FIELDS = Set.of(
            "artifactId", "expectedVersion", "title", "content");

    private final ArtifactService artifacts;
    private final CanvasService canvas;
    private final ObjectMapper mapper;

    public CreativeArtifactToolService(ArtifactService artifacts, CanvasService canvas,
            ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.canvas = canvas;
        this.mapper = mapper;
    }

    /** Creates a typed character description with only Run-visible image references. */
    public JsonNode createCharacter(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, CHARACTER_FIELDS);
        String name = requiredText(input, "name", 120);
        requiredText(input, "description", 4_000);
        requiredText(input, "appearance", 4_000);
        verifyReferences(context, run, input.path("referenceVersionIds"), 8, Artifact.Kind.IMAGE);
        return createOne(context, run, operationId, Artifact.Kind.CHARACTER, name, input,
                "已创建角色说明");
    }

    /** Creates a typed scene description with only Run-visible image references. */
    public JsonNode createScene(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, SCENE_FIELDS);
        String name = requiredText(input, "name", 120);
        requiredText(input, "location", 500);
        requiredText(input, "timeOfDay", 80);
        requiredText(input, "lighting", 1_000);
        requiredText(input, "style", 1_000);
        verifyReferences(context, run, input.path("referenceVersionIds"), 8, Artifact.Kind.IMAGE);
        return createOne(context, run, operationId, Artifact.Kind.SCENE, name, input,
                "已创建场景说明");
    }

    /** Creates one to six ordered shots; the caller transaction rolls back the whole batch. */
    public JsonNode createShots(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, Set.of("shots"));
        JsonNode shots = input.path("shots");
        if (!shots.isArray() || shots.isEmpty() || shots.size() > 6) {
            throw invalid("create_shots requires one to six shots");
        }
        List<ArtifactService.ArtifactView> created = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            if (!(shots.get(index) instanceof ObjectNode shot)) {
                throw invalid("Every shot must be an object");
            }
            allowOnly(shot, SHOT_FIELDS);
            String title = requiredText(shot, "title", 160);
            JsonNode order = shot.path("order");
            if (!order.isInt() || order.intValue() != index + 1) {
                throw invalid("Shot order must be contiguous and start at one");
            }
            JsonNode duration = shot.path("durationMs");
            if (!duration.isInt() || duration.intValue() < 100 || duration.intValue() > 30_000) {
                throw invalid("Shot duration is out of range");
            }
            requiredText(shot, "description", 4_000);
            requiredText(shot, "camera", 1_000);
            requiredText(shot, "action", 2_000);
            verifyReferences(context, run, shot.path("characterVersionIds"), 10,
                    Artifact.Kind.CHARACTER);
            UUID sceneVersionId = parseUuid(shot.path("sceneVersionId"));
            ArtifactVersion scene = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), sceneVersionId, run.contextSnapshot());
            if (!Artifact.Kind.SCENE.equals(artifacts.get(context.ownerId(), context.projectId(),
                    scene.artifactId()).artifact().kind())) {
                throw invalid("sceneVersionId must reference a scene");
            }
            ObjectNode content = shot.deepCopy();
            content.remove("title");
            created.add(artifacts.createFromAgent(context.ownerId(), context.projectId(),
                    context.runId(), Artifact.Kind.SHOT, title, content));
        }
        placeOutputs(context, run, created);
        return result(operationId, created, "已创建有序镜头");
    }

    /** Applies a complete, CAS-protected creative revision with server-checked Run scope. */
    public JsonNode reviseArtifact(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, REVISE_FIELDS);
        UUID artifactId = parseUuid(input.path("artifactId"));
        JsonNode version = input.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong()
                || version.longValue() < 0) {
            throw invalid("expectedVersion must be a nonnegative integer");
        }
        String title = input.has("title") ? requiredText(input, "title", 160) : null;
        JsonNode content = input.path("content");
        if (!content.isObject()) {
            throw invalid("A complete content object is required");
        }
        ArtifactService.ArtifactView revised = artifacts.reviseFromAgent(context.ownerId(),
                context.projectId(), context.runId(), run.contextSnapshot(), artifactId,
                version.longValue(), title, content);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds");
        result.putArray("updatedIds").add(artifactId.toString());
        result.putObject("affectedVersions").put(artifactId.toString(),
                revised.currentVersion().id().toString());
        result.putObject("artifactVersions").put(artifactId.toString(),
                revised.artifact().version());
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已创建产物的新内容版本");
        return result;
    }

    private JsonNode createOne(TrustedToolContext context, AgentRun run, UUID operationId,
            Artifact.Kind kind, String title, ObjectNode content, String summary) {
        ArtifactService.ArtifactView created = artifacts.createFromAgent(context.ownerId(),
                context.projectId(), context.runId(), kind, title, content);
        placeOutputs(context, run, List.of(created));
        return result(operationId, List.of(created), summary);
    }

    /** Places newly created outputs in the Agent's persisted output group, not a UI-only draft. */
    public void placeOutputs(TrustedToolContext context, AgentRun run,
            List<ArtifactService.ArtifactView> created) {
        JsonNode group = run.contextSnapshot().path("outputGroupId");
        UUID groupId = parseUuid(group);
        BigDecimal rightEdge = canvas.list(context.ownerId(), context.projectId()).stream()
                .map(entry -> entry.item().x().add(entry.item().width()))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal x = rightEdge.add(new BigDecimal("80"));
        List<CanvasService.PlaceArtifact> commands = new ArrayList<>();
        for (int index = 0; index < created.size(); index++) {
            ArtifactService.ArtifactView view = created.get(index);
            commands.add(new CanvasService.PlaceArtifact(UUID.randomUUID(), view.artifact().id(),
                    x, BigDecimal.valueOf(index * 240L), new BigDecimal("320"),
                    new BigDecimal("200"), index, groupId, false));
        }
        canvas.apply(context.ownerId(), context.projectId(), commands);
    }

    /** Places only current Run-visible versions in the server-owned Agent output group. */
    public JsonNode placeArtifacts(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, Set.of("versionIds", "group"));
        if (!"AGENT_OUTPUT".equals(requiredText(input, "group", 20))) {
            throw invalid("Only the Agent output group is allowed");
        }
        JsonNode requested = input.path("versionIds");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > 6) {
            throw invalid("place_artifacts requires one to six version IDs");
        }
        List<UUID> versionIds = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (JsonNode supplied : requested) {
            UUID versionId = parseUuid(supplied);
            if (!unique.add(versionId)) {
                throw invalid("place_artifacts version IDs must be unique");
            }
            versionIds.add(versionId);
        }
        List<UUID> artifactIds = new ArrayList<>();
        Set<UUID> uniqueArtifacts = new HashSet<>();
        for (UUID versionId : versionIds) {
            ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), versionId, run.contextSnapshot());
            ArtifactService.ArtifactView view = artifacts.get(context.ownerId(),
                    context.projectId(), version.artifactId());
            if (view.artifact().archivedAt() != null
                    || !view.currentVersion().id().equals(versionId)) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "ARTIFACT_VERSION_CONFLICT", "产物版本已变化",
                        "输出卡片只可指向当前选用的产物版本。", false);
            }
            if (!uniqueArtifacts.add(version.artifactId())) {
                throw invalid("place_artifacts must not repeat an Artifact");
            }
            artifactIds.add(version.artifactId());
        }
        CanvasService.OutputPlacements placed = canvas.placeArtifactsInAgentOutputWithinChange(
                context.ownerId(), context.projectId(), run.agentInstanceId(), artifactIds);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        ArrayNode createdIds = result.putArray("createdIds");
        placed.created().forEach(item -> createdIds.add(item.id().toString()));
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已将允许的产物放入 Agent 输出分组");
        ArrayNode items = result.putArray("data");
        for (int index = 0; index < artifactIds.size(); index++) {
            UUID artifactId = artifactIds.get(index);
            CanvasItem item = placed.created().stream()
                    .filter(card -> card.subjectId().equals(artifactId))
                    .findFirst().orElseGet(() -> placed.alreadyPresent().stream()
                            .filter(card -> card.subjectId().equals(artifactId))
                            .findFirst().orElseThrow());
            ObjectNode mapping = items.addObject();
            mapping.put("artifactId", artifactId.toString());
            mapping.put("versionId", versionIds.get(index).toString());
            mapping.put("itemId", item.id().toString());
            mapping.put("itemVersion", item.version());
            mapping.put("created", placed.created().contains(item));
        }
        return result;
    }

    /** Arranges only Run-visible cards in the Agent output group with per-card layout CAS. */
    public JsonNode arrangeItems(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, Set.of("items", "layout"));
        String layout = requiredText(input, "layout", 20);
        if (!Set.of("HORIZONTAL", "VERTICAL", "GRID").contains(layout)) {
            throw invalid("arrange_items layout is unsupported");
        }
        JsonNode requested = input.path("items");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > 6) {
            throw invalid("arrange_items requires one to six items");
        }
        List<ArrangeRequest> commands = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (JsonNode supplied : requested) {
            if (!(supplied instanceof ObjectNode object)
                    || object.size() != 3
                    || !object.has("itemId") || !object.has("versionId")
                    || !object.has("expectedVersion")) {
                throw invalid("arrange_items requires itemId, versionId and expectedVersion");
            }
            UUID itemId = parseUuid(object.path("itemId"));
            UUID versionId = parseUuid(object.path("versionId"));
            JsonNode expected = object.path("expectedVersion");
            if (!expected.isIntegralNumber() || !expected.canConvertToLong()
                    || expected.longValue() < 0 || !unique.add(itemId)) {
                throw invalid("arrange_items has an invalid or duplicate item");
            }
            commands.add(new ArrangeRequest(itemId, versionId, expected.longValue()));
        }
        UUID outputGroupId = parseUuid(run.contextSnapshot().path("outputGroupId"));
        List<CanvasService.CanvasEntry> before = canvas.list(context.ownerId(),
                context.projectId());
        List<CanvasItem> selected = new ArrayList<>();
        for (ArrangeRequest request : commands) {
            CanvasItem item = before.stream().map(CanvasService.CanvasEntry::item)
                    .filter(candidate -> candidate.id().equals(request.itemId()))
                    .findFirst().orElseThrow(() -> new ApiProblemException(
                            HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                            "画布卡片不存在", "目标卡片不属于当前项目。", false));
            if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT
                    || !outputGroupId.equals(item.groupId())) {
                throw new ApiProblemException(HttpStatus.FORBIDDEN, "INPUT_SCOPE_DENIED",
                        "画布卡片超出授权范围", "只能排列本 Agent 输出分组的产物卡片。", false);
            }
            if (item.locked()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED",
                        "卡片已锁定", "已锁定的卡片不能由 Agent 排列。", false);
            }
            ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), request.versionId(),
                    run.contextSnapshot());
            if (!version.artifactId().equals(item.subjectId())) {
                throw invalid("arrange_items version does not match its card");
            }
            ArtifactService.ArtifactView view = artifacts.get(context.ownerId(),
                    context.projectId(), item.subjectId());
            if (view.artifact().archivedAt() != null
                    || !view.currentVersion().id().equals(request.versionId())) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "ARTIFACT_VERSION_CONFLICT", "产物版本已变化",
                        "旧内容版本不能决定当前卡片布局。", false);
            }
            selected.add(item);
        }
        BigDecimal rightEdge = before.stream().map(CanvasService.CanvasEntry::item)
                .filter(item -> !unique.contains(item.id()))
                .map(item -> item.x().add(item.width()))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal baseX = rightEdge.add(new BigDecimal("80"));
        BigDecimal widthStep = selected.stream().map(CanvasItem::width)
                .max(BigDecimal::compareTo).orElseThrow().add(new BigDecimal("32"));
        BigDecimal heightStep = selected.stream().map(CanvasItem::height)
                .max(BigDecimal::compareTo).orElseThrow().add(new BigDecimal("32"));
        List<CanvasService.UpdateLayout> updates = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            CanvasItem item = selected.get(index);
            int column = switch (layout) {
                case "HORIZONTAL" -> index;
                case "GRID" -> index % 3;
                default -> 0;
            };
            int row = switch (layout) {
                case "VERTICAL" -> index;
                case "GRID" -> index / 3;
                default -> 0;
            };
            updates.add(new CanvasService.UpdateLayout(item.id(),
                    commands.get(index).expectedVersion(),
                    baseX.add(widthStep.multiply(BigDecimal.valueOf(column))),
                    heightStep.multiply(BigDecimal.valueOf(row)),
                    item.width(), item.height(), item.zIndex(), item.groupId()));
        }
        List<CanvasService.CanvasEntry> after = canvas.apply(context.ownerId(),
                context.projectId(), updates);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds");
        ArrayNode updatedIds = result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已排列 Agent 输出卡片");
        ArrayNode data = result.putArray("data");
        for (CanvasItem old : selected) {
            CanvasItem current = after.stream().map(CanvasService.CanvasEntry::item)
                    .filter(item -> item.id().equals(old.id()))
                    .findFirst().orElseThrow();
            if (current.version() != old.version()) {
                updatedIds.add(current.id().toString());
            }
            ObjectNode item = data.addObject();
            item.put("itemId", current.id().toString());
            item.put("itemVersion", current.version());
            item.put("x", current.x());
            item.put("y", current.y());
        }
        return result;
    }

    /** Links a typed semantic reference by creating an immutable source-content version. */
    public JsonNode linkArtifacts(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, Set.of("sourceArtifactId", "expectedVersion",
                "targetVersionId", "relationship"));
        UUID sourceId = parseUuid(input.path("sourceArtifactId"));
        UUID targetVersionId = parseUuid(input.path("targetVersionId"));
        JsonNode expected = input.path("expectedVersion");
        if (!expected.isIntegralNumber() || !expected.canConvertToLong()
                || expected.longValue() < 0) {
            throw invalid("link_artifacts expectedVersion must be nonnegative");
        }
        String relationship = requiredText(input, "relationship", 40);
        ArtifactService.ArtifactView source = artifacts.get(context.ownerId(),
                context.projectId(), sourceId);
        artifacts.requireAgentVisibleVersion(context.ownerId(), context.projectId(),
                context.runId(), source.currentVersion().id(), run.contextSnapshot());
        ArtifactVersion target = artifacts.requireAgentVisibleVersion(context.ownerId(),
                context.projectId(), context.runId(), targetVersionId, run.contextSnapshot());
        Artifact.Kind targetKind = artifacts.get(context.ownerId(), context.projectId(),
                target.artifactId()).artifact().kind();
        String arrayField = switch (relationship) {
            case "CHARACTER_REFERENCE_IMAGE" -> {
                requireLinkKinds(source.artifact().kind(), Artifact.Kind.CHARACTER,
                        targetKind, Artifact.Kind.IMAGE);
                yield "referenceVersionIds";
            }
            case "SCENE_REFERENCE_IMAGE" -> {
                requireLinkKinds(source.artifact().kind(), Artifact.Kind.SCENE,
                        targetKind, Artifact.Kind.IMAGE);
                yield "referenceVersionIds";
            }
            case "SHOT_CHARACTER" -> {
                requireLinkKinds(source.artifact().kind(), Artifact.Kind.SHOT,
                        targetKind, Artifact.Kind.CHARACTER);
                yield "characterVersionIds";
            }
            case "SHOT_SCENE" -> {
                requireLinkKinds(source.artifact().kind(), Artifact.Kind.SHOT,
                        targetKind, Artifact.Kind.SCENE);
                yield null;
            }
            default -> throw invalid("link_artifacts relationship is unsupported");
        };
        if (!(source.currentVersion().content() instanceof ObjectNode sourceContent)) {
            throw new IllegalStateException("Current Artifact content is not an object");
        }
        ObjectNode nextContent = sourceContent.deepCopy();
        boolean alreadyLinked;
        if (arrayField == null) {
            alreadyLinked = targetVersionId.toString().equals(
                    nextContent.path("sceneVersionId").asText());
            nextContent.put("sceneVersionId", targetVersionId.toString());
        } else {
            JsonNode references = nextContent.path(arrayField);
            if (!(references instanceof ArrayNode array)) {
                throw new IllegalStateException("Current reference field is malformed");
            }
            alreadyLinked = false;
            for (JsonNode reference : array) {
                if (targetVersionId.toString().equals(reference.asText())) {
                    alreadyLinked = true;
                    break;
                }
            }
            if (!alreadyLinked) array.add(targetVersionId.toString());
        }
        if (source.artifact().archivedAt() != null
                || source.artifact().version() != expected.longValue()) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "ARTIFACT_VERSION_CONFLICT", "产物版本已变化",
                    "请读取当前产物版本后重新建立关系。", false);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds");
        ArrayNode updatedIds = result.putArray("updatedIds");
        ObjectNode affected = result.putObject("affectedVersions");
        ObjectNode artifactVersions = result.putObject("artifactVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        if (alreadyLinked) {
            artifactVersions.put(sourceId.toString(), source.artifact().version());
            result.put("userVisibleSummary", "语义关系已存在，未创建重复内容版本");
            return result;
        }
        ArtifactService.ArtifactView revised = artifacts.reviseFromAgent(context.ownerId(),
                context.projectId(), context.runId(), run.contextSnapshot(), sourceId,
                expected.longValue(), null, nextContent);
        updatedIds.add(sourceId.toString());
        affected.put(sourceId.toString(), revised.currentVersion().id().toString());
        artifactVersions.put(sourceId.toString(), revised.artifact().version());
        result.put("userVisibleSummary", "已建立语义关系并创建产物新版本");
        return result;
    }

    /** Rejects relation kinds outside the four content-schema-backed P0 edges. */
    private void requireLinkKinds(Artifact.Kind source, Artifact.Kind expectedSource,
            Artifact.Kind target, Artifact.Kind expectedTarget) {
        if (source != expectedSource || target != expectedTarget) {
            throw invalid("link_artifacts source or target kind does not match relationship");
        }
    }

    /** Immutable tool input checked before any canvas mutation. */
    private record ArrangeRequest(UUID itemId, UUID versionId, long expectedVersion) {}

    private ObjectNode result(UUID operationId, List<ArtifactService.ArtifactView> created,
            String summary) {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        ArrayNode ids = result.putArray("createdIds");
        result.putArray("updatedIds");
        ObjectNode versions = result.putObject("affectedVersions");
        ObjectNode artifactVersions = result.putObject("artifactVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", summary);
        for (ArtifactService.ArtifactView view : created) {
            ids.add(view.artifact().id().toString());
            versions.put(view.artifact().id().toString(), view.currentVersion().id().toString());
            artifactVersions.put(view.artifact().id().toString(), view.artifact().version());
        }
        return result;
    }

    private void verifyReferences(TrustedToolContext context, AgentRun run, JsonNode references,
            int maximum, Artifact.Kind kind) {
        if (!references.isArray() || references.size() > maximum) {
            throw invalid("Reference list is absent or too large");
        }
        Set<UUID> unique = new java.util.HashSet<>();
        for (JsonNode node : references) {
            UUID versionId = parseUuid(node);
            if (!unique.add(versionId)) {
                throw invalid("Reference versions must be unique");
            }
            ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), versionId, run.contextSnapshot());
            if (artifacts.get(context.ownerId(), context.projectId(),
                    version.artifactId()).artifact().kind() != kind) {
                throw invalid("Reference version has the wrong artifact kind");
            }
        }
    }

    private ObjectNode parseObject(String arguments) {
        JsonNode node;
        try {
            node = mapper.readTree(arguments);
        } catch (RuntimeException exception) {
            throw invalid("Tool arguments are not valid JSON");
        }
        if (!(node instanceof ObjectNode object)) {
            throw invalid("Tool arguments must be an object");
        }
        return object;
    }

    private void allowOnly(ObjectNode input, Set<String> allowed) {
        for (String field : input.propertyNames()) {
            if (!allowed.contains(field)) {
                throw invalid("Tool arguments contain an unknown field");
            }
        }
    }

    private String requiredText(JsonNode input, String field, int maximum) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximum) {
            throw invalid("Tool argument " + field + " is invalid");
        }
        return value.asText();
    }

    private UUID parseUuid(JsonNode value) {
        if (!value.isTextual()) {
            throw invalid("Reference version ID must be a UUID string");
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw invalid("Reference version ID must be a UUID string");
        }
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                "工具参数无效", detail, false);
    }
}
