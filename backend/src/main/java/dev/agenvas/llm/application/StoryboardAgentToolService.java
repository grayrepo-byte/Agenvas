package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.application.StoryboardService.CompositionInput;
import dev.agenvas.storyboard.application.StoryboardService.ProductionInput;
import dev.agenvas.storyboard.application.StoryboardService.ShotInput;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.storyboard.domain.Storyboard.Shot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded creative edits reuse board CAS and review rules; this tool cannot approve or submit media. */
@Service
public class StoryboardAgentToolService {
    private static final int MAX_ARGUMENT_BYTES = 1024 * 1024;
    private static final int MAX_SHOTS = 200;
    private static final int MAX_WRITE_SHOTS = 6;
    private static final int MAX_REFERENCES = 30;
    private static final int PREVIEW_CHARACTERS = 256;
    private static final Set<String> READ_FIELDS = Set.of("offset", "limit", "shotIds");
    private static final Set<String> WRITE_FIELDS = Set.of("expectedVersion", "shots");
    private static final Set<String> SHOT_FIELDS = Set.of("shotId", "code", "scene", "title", "description",
            "dialogue", "durationSeconds", "sourceVersionId", "referenceVersionIds");
    private static final Set<String> APPEND_REQUIRED_FIELDS = Set.of("code", "description", "dialogue", "durationSeconds");
    private final StoryboardService boards;
    private final ArtifactService artifacts;
    private final ObjectMapper mapper;

    public StoryboardAgentToolService(StoryboardService boards, ArtifactService artifacts, ObjectMapper mapper) {
        this.boards = boards;
        this.artifacts = artifacts;
        this.mapper = mapper;
    }

    /** Directory pages expose bounded previews; one explicitly requested shot returns its full creative fields. */
    @Transactional(readOnly = true)
    public JsonNode readStoryboard(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, READ_FIELDS);
        List<UUID> requestedIds = null;
        int offset = 0;
        int limit = 20;
        if (input.has("shotIds")) {
            if (input.has("offset") || input.has("limit")) throw invalid();
            requestedIds = ids(input.path("shotIds"), 1);
            if (requestedIds.isEmpty()) throw invalid();
        } else {
            offset = input.has("offset") ? integer(input, "offset") : 0;
            limit = input.has("limit") ? integer(input, "limit") : 20;
            if (limit < 1 || limit > 40) throw invalid();
        }
        Storyboard board = boards.get(context.ownerId(), context.projectId());
        ObjectNode result = result(operationId, "已读取分镜板");
        ObjectNode data = boardData(result, board);
        var shots = data.putArray("shots");
        if (requestedIds != null) {
            UUID requestedId = requestedIds.getFirst();
            Shot shot = board.shots().stream().filter(candidate -> candidate.id().equals(requestedId))
                    .findFirst().orElseThrow(this::invalid);
            data.put("offset", board.shots().indexOf(shot)).putNull("nextOffset");
            shots.add(describe(shot, true));
        } else {
            if (offset > board.shots().size()) throw invalid();
            int end = Math.min(board.shots().size(), offset + limit);
            data.put("offset", offset);
            if (end < board.shots().size()) data.put("nextOffset", end); else data.putNull("nextOffset");
            for (int index = offset; index < end; index++) shots.add(describe(board.shots().get(index), false));
        }
        return result;
    }

    /** Omitted fields, other shots, pinned results and review provenance survive a targeted patch. */
    @Transactional
    public JsonNode writeShots(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, WRITE_FIELDS);
        long expectedVersion = version(input, "expectedVersion");
        JsonNode requestedShots = input.path("shots");
        if (!requestedShots.isArray() || requestedShots.isEmpty() || requestedShots.size() > MAX_WRITE_SHOTS) throw invalid();
        // Validate all shapes before reading references or attempting a board write.
        for (JsonNode requestedShot : requestedShots) validatePatch(requestedShot);
        Storyboard prior = boards.get(context.ownerId(), context.projectId());
        if (prior.version() != expectedVersion) throw conflict();
        Map<UUID, Shot> oldShots = new HashMap<>();
        List<ShotInput> merged = new ArrayList<>();
        prior.shots().forEach(shot -> {
            oldShots.put(shot.id(), shot);
            merged.add(retain(shot));
        });
        Set<UUID> patchedIds = new HashSet<>();
        List<UUID> createdIds = new ArrayList<>();
        List<UUID> updatedIds = new ArrayList<>();
        List<ShotInput> changes = new ArrayList<>();
        for (JsonNode patch : requestedShots) {
            Shot old = null;
            UUID id;
            if (patch.has("shotId")) {
                id = uuid(patch.path("shotId"));
                old = oldShots.get(id);
                if (old == null || !patchedIds.add(id)) throw invalid();
                updatedIds.add(id);
            } else {
                id = UUID.randomUUID();
                createdIds.add(id);
            }
            ShotInput next = merge(id, old, patch);
            changes.add(next);
            if (old == null) merged.add(next);
            else merged.set(prior.shots().indexOf(old), next);
        }
        if (merged.size() > MAX_SHOTS) throw invalid();
        Set<String> codes = new HashSet<>();
        if (merged.stream().anyMatch(shot -> !codes.add(shot.code()))) throw invalid();
        // Existing detached references may be retained. Only newly introduced references require current Run scope.
        for (ShotInput change : changes) authorizeChangedReferences(context, run, oldShots.get(change.id()), change);
        var composition = prior.composition();
        CompositionInput retainedComposition = composition == null ? null
                : new CompositionInput(composition.canvasItemId(), composition.selectedVersionId(), false);
        Storyboard saved;
        try {
            saved = boards.save(context.ownerId(), context.projectId(),
                    new StoryboardService.Save(expectedVersion, List.copyOf(merged), retainedComposition));
        } catch (ApiProblemException problem) {
            if ("STORYBOARD_INVALID".equals(problem.code())) throw invalid();
            throw problem;
        }
        ObjectNode result = result(operationId, "已保存分镜创作要求，媒体生成仍需原有审批");
        createdIds.forEach(id -> result.withArray("createdIds").add(id.toString()));
        updatedIds.forEach(id -> result.withArray("updatedIds").add(id.toString()));
        ObjectNode data = boardData(result, saved);
        var shots = data.putArray("shots");
        Set<UUID> changedIds = new HashSet<>(createdIds);
        changedIds.addAll(updatedIds);
        for (Shot shot : saved.shots()) if (changedIds.contains(shot.id())) shots.add(summary(shot));
        return result;
    }

    private void validatePatch(JsonNode patch) {
        if (!(patch instanceof ObjectNode object)) throw invalid();
        requireFields(object, SHOT_FIELDS);
        boolean existing = patch.has("shotId");
        if (existing) {
            uuid(patch.path("shotId"));
            if (patch.size() < 2) throw invalid();
        } else if (APPEND_REQUIRED_FIELDS.stream().anyMatch(field -> !patch.has(field))) throw invalid();
        if (patch.has("code")) {
            String code = text(patch, "code", 80);
            if (code.isBlank() || !code.equals(code.trim())) throw invalid();
        }
        for (String field : List.of("scene", "title")) if (patch.has(field)) text(patch, field, 200);
        for (String field : List.of("description", "dialogue")) if (patch.has(field)) text(patch, field, 20_000);
        if (patch.has("durationSeconds")) duration(patch.path("durationSeconds"));
        if (patch.has("sourceVersionId") && !patch.path("sourceVersionId").isNull()) uuid(patch.path("sourceVersionId"));
        if (patch.has("referenceVersionIds")) ids(patch.path("referenceVersionIds"), MAX_REFERENCES);
    }

    private ShotInput merge(UUID id, Shot old, JsonNode patch) {
        return new ShotInput(id,
                patch.has("code") ? text(patch, "code", 80) : old.code(),
                patch.has("scene") ? text(patch, "scene", 200) : old == null ? "" : old.scene(),
                patch.has("title") ? text(patch, "title", 200) : old == null ? "" : old.title(),
                patch.has("description") ? text(patch, "description", 20_000) : old.description(),
                patch.has("dialogue") ? text(patch, "dialogue", 20_000) : old.dialogue(),
                patch.has("durationSeconds") ? duration(patch.path("durationSeconds")) : old.durationSeconds(),
                patch.has("sourceVersionId") ? patch.path("sourceVersionId").isNull() ? null : uuid(patch.path("sourceVersionId"))
                        : old == null ? null : old.sourceVersionId(),
                patch.has("referenceVersionIds") ? ids(patch.path("referenceVersionIds"), MAX_REFERENCES)
                        : old == null ? List.of() : old.referenceVersionIds(),
                old == null ? List.of() : retainProductions(old));
    }

    private ShotInput retain(Shot shot) {
        return new ShotInput(shot.id(), shot.code(), shot.scene(), shot.title(), shot.description(), shot.dialogue(),
                shot.durationSeconds(), shot.sourceVersionId(), shot.referenceVersionIds(), retainProductions(shot));
    }

    private List<ProductionInput> retainProductions(Shot shot) {
        return shot.productions().stream().map(production -> new ProductionInput(production.role(),
                production.canvasItemId(), production.selectedVersionId(), false)).toList();
    }

    private void authorizeChangedReferences(TrustedToolContext context, AgentRun run, Shot old, ShotInput next) {
        if (next.sourceVersionId() != null && (old == null || !Objects.equals(old.sourceVersionId(), next.sourceVersionId())))
            artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), run.contextSnapshot(), context.runId(), next.sourceVersionId());
        for (UUID referenceId : next.referenceVersionIds()) {
            if (old == null || !old.referenceVersionIds().contains(referenceId))
                artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), run.contextSnapshot(), context.runId(), referenceId);
        }
    }

    private ObjectNode boardData(ObjectNode result, Storyboard board) {
        return result.putObject("data").put("version", board.version()).put("total", board.shots().size())
                .put("compositionNeedsReview", board.composition() != null && board.composition().needsReview());
    }

    private ObjectNode describe(Shot shot, boolean full) {
        ObjectNode data = summary(shot).put("scene", shot.scene()).put("title", shot.title())
                .put("durationSeconds", shot.durationSeconds());
        if (shot.sourceVersionId() == null) data.putNull("sourceVersionId");
        else data.put("sourceVersionId", shot.sourceVersionId().toString());
        var references = data.putArray("referenceVersionIds");
        shot.referenceVersionIds().forEach(id -> references.add(id.toString()));
        if (full) data.put("description", shot.description()).put("dialogue", shot.dialogue());
        else {
            preview(data, "description", shot.description());
            preview(data, "dialogue", shot.dialogue());
        }
        return data;
    }

    private ObjectNode summary(Shot shot) {
        ObjectNode data = mapper.createObjectNode().put("id", shot.id().toString()).put("code", shot.code())
                .put("revision", shot.revision()).put("needsReview", shot.productions().stream().anyMatch(value -> value.needsReview()));
        var productions = data.putArray("productions");
        for (var production : shot.productions()) {
            ObjectNode target = productions.addObject().put("role", production.role().name())
                    .put("canvasItemId", production.canvasItemId().toString())
                    .put("reviewedRevision", production.reviewedRevision()).put("needsReview", production.needsReview());
            if (production.selectedVersionId() == null) target.putNull("selectedVersionId");
            else target.put("selectedVersionId", production.selectedVersionId().toString());
        }
        return data;
    }

    private void preview(ObjectNode target, String field, String text) {
        int characters = text.codePointCount(0, text.length());
        int end = text.offsetByCodePoints(0, Math.min(PREVIEW_CHARACTERS, characters));
        target.put(field + "Preview", text.substring(0, end)).put(field + "Characters", characters)
                .put(field + "Truncated", characters > PREVIEW_CHARACTERS);
    }

    private ObjectNode result(UUID operationId, String summary) {
        ObjectNode result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", operationId.toString()).put("userVisibleSummary", summary);
        result.putArray("createdIds");
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        return result;
    }

    private ObjectNode parse(String arguments, Set<String> fields) {
        if (arguments == null || arguments.length() > MAX_ARGUMENT_BYTES
                || arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) throw invalid();
        JsonNode input;
        try {
            input = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(arguments);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
        if (!(input instanceof ObjectNode object)) throw invalid();
        requireFields(object, fields);
        return object;
    }

    private void requireFields(ObjectNode input, Set<String> fields) {
        if (input.propertyNames().stream().anyMatch(field -> !fields.contains(field))) throw invalid();
    }

    private List<UUID> ids(JsonNode values, int max) {
        if (!values.isArray() || values.size() > max) throw invalid();
        List<UUID> ids = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (JsonNode value : values) {
            UUID id = uuid(value);
            if (!unique.add(id)) throw invalid();
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private UUID uuid(JsonNode input) {
        if (!input.isTextual() || input.asText().length() != 36) throw invalid();
        try { return UUID.fromString(input.asText()); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private long version(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }

    private int integer(JsonNode input, String field) {
        long value = version(input, field);
        if (value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    private BigDecimal duration(JsonNode value) {
        if (!value.isNumber()) throw invalid();
        BigDecimal duration = value.decimalValue();
        if (duration.compareTo(new BigDecimal("0.1")) < 0 || duration.compareTo(BigDecimal.valueOf(3600)) > 0
                || duration.stripTrailingZeros().scale() > 3) throw invalid();
        return duration;
    }

    private String text(JsonNode input, String field, int max) {
        JsonNode value = input.path(field);
        if (!value.isTextual() || value.asText().length() > max) throw invalid();
        return value.asText();
    }

    private ApiProblemException invalid() {
        ApiMessage message = ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid");
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID", message, message, false);
    }

    private ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "STORYBOARD_VERSION_CONFLICT",
                ApiMessage.of("api.storyboard.conflict-title"), ApiMessage.of("api.storyboard.conflict-detail"), false);
    }
}
