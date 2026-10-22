package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.MediaDraftRestoreService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Canvas commands use the live owned canvas, normal application services and optimistic locks. */
@Service
@Transactional
public class CanvasAgentToolService {
    private enum Action { RENAME, MOVE, RESIZE, REMOVE, DUPLICATE }
    private final CanvasService canvas;
    private final ArtifactService artifacts;
    private final MediaDraftService drafts;
    private final MediaDraftRestoreService draftWrites;
    private final DirectMediaTaskService mediaTasks;
    private final ObjectMapper mapper;

    public CanvasAgentToolService(CanvasService canvas, ArtifactService artifacts, MediaDraftService drafts,
            MediaDraftRestoreService draftWrites, DirectMediaTaskService mediaTasks, ObjectMapper mapper) {
        this.canvas = canvas;
        this.artifacts = artifacts;
        this.drafts = drafts;
        this.draftWrites = draftWrites;
        this.mediaTasks = mediaTasks;
        this.mapper = mapper;
    }

    /** Directory pages disclose metadata and persisted drafts only after the model asks for them. */
    @Transactional(readOnly = true)
    public JsonNode readCanvas(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, Set.of("offset", "limit", "includeDrafts"));
        int offset = input.has("offset") ? integer(input, "offset") : 0;
        int limit = input.has("limit") ? integer(input, "limit") : 20;
        List<CanvasService.CanvasEntry> directory = canvas.list(context.ownerId(), context.projectId());
        if (input.has("includeDrafts") && !input.path("includeDrafts").isBoolean()) throw invalid();
        boolean includeDrafts = input.path("includeDrafts").asBoolean(false);
        if (includeDrafts && limit > 6) throw invalid();
        if (limit < 1 || limit > 40 || offset > directory.size()) throw invalid();
        ObjectNode result = result(operationId, "已读取画布节点目录");
        result.put("offset", offset).put("total", directory.size());
        int end = Math.min(directory.size(), offset + limit);
        if (end < directory.size()) result.put("nextOffset", end); else result.putNull("nextOffset");
        var data = result.putArray("data");
        for (int index = offset; index < end; index++) {
            var entry = directory.get(index);
            ObjectNode item = describe(context, entry).put("kind", entry.artifact().artifact().kind().name())
                    .put("artifactVersion", entry.artifact().artifact().version())
                    .put("locked", entry.item().locked()).put("zIndex", entry.item().zIndex());
            JsonNode draft = item.path("mediaDraft");
            if (!includeDrafts && draft.isObject()) {
                ObjectNode metadata = mapper.createObjectNode().put("version", draft.path("version").asLong())
                        .put("promptCharacters", draft.path("prompt").asText().length())
                        .put("mediaInputCount", draft.path("mediaInputs").size()).put("mentionCount", draft.path("mentions").size());
                for (String field : List.of("capabilityId", "videoInputMode", "durationSeconds"))
                    if (!draft.path(field).isMissingNode()) metadata.set(field, draft.path(field).deepCopy());
                item.set("mediaDraft", metadata);
            }
            data.add(item);
        }
        return result;
    }

    public JsonNode manageNode(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, Set.of("action", "itemId", "expectedVersion", "title", "x", "y", "width", "height", "expectedDraftVersion"));
        Action action;
        try { action = Action.valueOf(input.path("action").asText()); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        CanvasService.CanvasEntry entry = requireItem(context, run, uuid(input, "itemId"));
        CanvasItem item = entry.item();
        long expected = version(input, "expectedVersion");
        if (item.version() != expected) throw problem(HttpStatus.CONFLICT, "CANVAS_VERSION_CONFLICT", ApiMessage.of("api.canvas-service.the-canvas-card-has-been-modified-by-other-requests-please"));
        requireUnlocked(item);
        ObjectNode result = result(operationId, "已更新画布节点");
        CanvasService.CanvasEntry updated;
        switch (action) {
            case RENAME -> {
                String title = text(input, "title", 160);
                updated = canvas.apply(context.ownerId(), context.projectId(), List.of(new CanvasService.UpdateTitle(item.id(), expected, title)))
                        .stream().filter(candidate -> candidate.item().id().equals(item.id())).findFirst().orElseThrow();
            }
            case MOVE, RESIZE -> {
                BigDecimal x = action == Action.MOVE ? decimal(input, "x") : item.x();
                BigDecimal y = action == Action.MOVE ? decimal(input, "y") : item.y();
                BigDecimal width = action == Action.RESIZE ? decimal(input, "width") : item.width();
                BigDecimal height = action == Action.RESIZE ? decimal(input, "height") : item.height();
                updated = canvas.apply(context.ownerId(), context.projectId(), List.of(new CanvasService.UpdateLayout(item.id(), expected,
                        x, y, width, height, item.zIndex(), item.groupId())))
                        .stream().filter(candidate -> candidate.item().id().equals(item.id())).findFirst().orElseThrow();
            }
            case REMOVE -> {
                requireIdle(context, entry);
                canvas.apply(context.ownerId(), context.projectId(), List.of(new CanvasService.Remove(item.id(), expected)));
                result.putArray("updatedIds").add(item.id().toString());
                result.put("userVisibleSummary", "已从画布移除节点，原产物和历史仍保留");
                return result;
            }
            case DUPLICATE -> {
                UUID targetId = UUID.randomUUID();
                BigDecimal x = input.has("x") ? decimal(input, "x") : item.x().add(new BigDecimal("48"));
                BigDecimal y = input.has("y") ? decimal(input, "y") : item.y().add(new BigDecimal("48"));
                if (entry.artifact().artifact().kind() == Artifact.Kind.TEXT) {
                    var source = entry.artifact().resourceDefaultVersion();
                    artifacts.requireCanvasVersion(context.ownerId(), context.projectId(), source.id());
                    var copy = artifacts.createFromAgent(context.ownerId(), context.projectId(), run.id(), Artifact.Kind.TEXT,
                            item.title(), source.content());
                    updated = canvas.apply(context.ownerId(), context.projectId(), List.of(new CanvasService.PlaceArtifact(targetId,
                            copy.artifact().id(), x, y, item.width(), item.height(), item.zIndex(), item.groupId(), false)))
                            .stream().filter(candidate -> candidate.item().id().equals(targetId)).findFirst().orElseThrow();
                } else {
                    if (item.selectedVersionId() != null) artifacts.requireCanvasVersion(context.ownerId(),
                            context.projectId(), item.selectedVersionId());
                    updated = canvas.duplicate(context.ownerId(), context.projectId(), item.id(), targetId, expected,
                            version(input, "expectedDraftVersion"), x, y, item.width(), item.height(), item.zIndex()).item();
                }
                result.putArray("createdIds").add(updated.item().id().toString());
                result.put("userVisibleSummary", "已复制画布节点");
            }
            default -> throw invalid();
        }
        result.putArray("updatedIds").add(updated.item().id().toString());
        result.set("data", describe(context, updated));
        return result;
    }

    /** Omitted fields retain the existing draft. Saving never submits a media task. */
    public JsonNode updateMediaDraft(TrustedToolContext context, AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parse(arguments, Set.of("itemId", "expectedVersion", "prompt", "parameters", "durationSeconds", "capabilityId", "videoInputMode", "mediaInputs", "mentions"));
        CanvasService.CanvasEntry entry = requireItem(context, run, uuid(input, "itemId"));
        requireUnlocked(entry.item());
        requireIdle(context, entry);
        if (entry.artifact().artifact().kind() == Artifact.Kind.TEXT) throw invalid();
        MediaDraft before = drafts.get(context.ownerId(), context.projectId(), entry.item().id());
        List<MediaDraftService.SaveMediaInput> mediaInputs = before.mediaInputs().stream()
                .map(media -> new MediaDraftService.SaveMediaInput(media.versionId(), media.role(), media.color())).toList();
        if (input.has("mediaInputs")) {
            JsonNode supplied = input.path("mediaInputs");
            if (!supplied.isArray() || supplied.size() > 14) throw invalid();
            var parsed = new java.util.ArrayList<MediaDraftService.SaveMediaInput>();
            for (JsonNode value : supplied) {
                if (!value.isObject() || value.size() != 2) throw invalid();
                UUID versionId = uuid(value, "versionId");
                artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), run.contextSnapshot(), versionId);
                MediaDraft.InputRole role;
                try { role = MediaDraft.InputRole.valueOf(value.path("role").asText()); }
                catch (IllegalArgumentException malformed) { throw invalid(); }
                parsed.add(new MediaDraftService.SaveMediaInput(versionId, role, "#7C3AED"));
            }
            mediaInputs = List.copyOf(parsed);
        }
        MediaDraft.VideoInputMode mode = before.videoInputMode();
        if (input.has("videoInputMode")) {
            try { mode = MediaDraft.VideoInputMode.valueOf(input.path("videoInputMode").asText()); }
            catch (IllegalArgumentException malformed) { throw invalid(); }
        }
        JsonNode parameters = input.has("parameters") ? input.path("parameters") : before.parameters();
        if (!parameters.isObject()) throw invalid();
        Integer duration = input.has("durationSeconds") ? Integer.valueOf(integer(input, "durationSeconds")) : before.durationSeconds();
        List<MediaDraft.PromptMention> mentions = input.has("prompt") ? List.of() : before.mentions();
        if (input.has("mentions")) {
            JsonNode supplied = input.path("mentions");
            if (!supplied.isArray() || supplied.size() > 1000) throw invalid();
            var parsed = new java.util.ArrayList<MediaDraft.PromptMention>();
            for (JsonNode value : supplied) {
                if (!value.isObject() || value.size() != 2) throw invalid();
                UUID versionId = uuid(value, "versionId");
                artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), run.contextSnapshot(), versionId);
                MediaDraft.InputRole role;
                try { role = MediaDraft.InputRole.valueOf(value.path("role").asText()); }
                catch (IllegalArgumentException malformed) { throw invalid(); }
                parsed.add(new MediaDraft.PromptMention(versionId, role));
            }
            mentions = List.copyOf(parsed);
        }
        String prompt = input.has("prompt") ? text(input, "prompt", 20_000) : before.prompt();
        if (input.has("mediaInputs") && !input.has("mentions")) {
            var retained = mediaInputs;
            mentions = mentions.stream().filter(mention -> retained.stream().anyMatch(media ->
                    media.versionId().equals(mention.versionId()) && media.role() == mention.role())).toList();
            if (!input.has("prompt")) prompt = drafts.pruneRemovedMentions(before.prompt(), before.mentions(), mentions);
        }
        UUID capabilityId = input.has("capabilityId") ? uuid(input, "capabilityId") : before.capabilityId();
        MediaDraft saved = input.has("mediaInputs")
                ? draftWrites.replaceInputs(context.ownerId(), context.projectId(), entry.item().id(), version(input, "expectedVersion"),
                        prompt, parameters, duration, capabilityId, mode, mediaInputs, mentions, before.styleId())
                : draftWrites.save(context.ownerId(), context.projectId(), entry.item().id(), version(input, "expectedVersion"),
                        prompt, parameters, duration, capabilityId, mode, mediaInputs, mentions, before.styleId());
        ObjectNode result = result(operationId, "已保存媒体草稿，尚未生成");
        result.putArray("updatedIds").add(entry.item().id().toString());
        result.set("data", mapper.valueToTree(saved));
        return result;
    }

    private CanvasService.CanvasEntry requireItem(TrustedToolContext context, AgentRun run, UUID itemId) {
        CanvasService.CanvasEntry entry = canvas.list(context.ownerId(), context.projectId()).stream()
                .filter(candidate -> candidate.item().id().equals(itemId) && candidate.item().subjectType() == CanvasItem.SubjectType.ARTIFACT)
                .findFirst().orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ApiMessage.of("api.canvas-service.the-canvas-card-does-not-exist-or-the-current-user")));
        return entry;
    }

    private void requireUnlocked(CanvasItem item) {
        if (item.locked()) throw problem(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED", ApiMessage.of("api.creative-artifact-tool-service.locked-cards-cannot-be-arranged-by-the-agent"));
    }

    private void requireIdle(TrustedToolContext context, CanvasService.CanvasEntry entry) {
        if (entry.artifact().artifact().kind() == Artifact.Kind.TEXT) return;
        if (mediaTasks.list(context.ownerId(), context.projectId(), entry.item().subjectId(), entry.item().id()).stream()
                .anyMatch(task -> Set.of(Task.Status.READY, Task.Status.SUBMITTING, Task.Status.RUNNING, Task.Status.WAITING_PROVIDER).contains(task.status())))
            throw problem(HttpStatus.CONFLICT, "CANVAS_ITEM_BUSY", ApiMessage.of("api.canvas-agent-tool-service.node-is-running"));
    }

    private ObjectNode describe(TrustedToolContext context, CanvasService.CanvasEntry entry) {
        CanvasItem item = entry.item();
        var data = mapper.createObjectNode().put("itemId", item.id().toString()).put("itemVersion", item.version())
                .put("artifactId", item.subjectId().toString()).put("title", item.title())
                .put("x", item.x()).put("y", item.y()).put("width", item.width()).put("height", item.height());
        var version = entry.artifact().artifact().kind() == Artifact.Kind.TEXT ? entry.artifact().resourceDefaultVersion() : entry.selectedVersion();
        if (version != null) data.put("selectedVersionId", version.id().toString());
        if (entry.artifact().artifact().kind() != Artifact.Kind.TEXT)
            data.set("mediaDraft", mapper.valueToTree(drafts.get(context.ownerId(), context.projectId(), item.id())));
        return data;
    }

    private ObjectNode result(UUID operationId, String summary) {
        ObjectNode result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", operationId.toString()).put("userVisibleSummary", summary);
        result.putArray("createdIds"); result.putArray("updatedIds"); result.putObject("affectedVersions");
        result.putArray("taskIds"); result.putNull("errorCode"); return result;
    }
    private ObjectNode parse(String arguments, Set<String> fields) {
        JsonNode parsed;
        try { parsed = mapper.readTree(arguments); } catch (RuntimeException malformed) { throw invalid(); }
        if (!(parsed instanceof ObjectNode object) || object.propertyNames().stream().anyMatch(key -> !fields.contains(key))) throw invalid();
        return object;
    }
    private UUID uuid(JsonNode input, String field) {
        if (!input.path(field).isTextual()) throw invalid();
        try { return UUID.fromString(input.path(field).asText()); } catch (IllegalArgumentException malformed) { throw invalid(); }
    }
    private long version(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }
    private int integer(JsonNode input, String field) {
        long value = version(input, field); if (value > Integer.MAX_VALUE) throw invalid(); return (int) value;
    }
    private BigDecimal decimal(JsonNode input, String field) {
        if (!input.path(field).isNumber()) throw invalid(); return input.path(field).decimalValue();
    }
    private String text(JsonNode input, String field, int max) {
        if (!input.path(field).isTextual() || input.path(field).asText().length() > max) throw invalid(); return input.path(field).asText();
    }
    private ApiProblemException invalid() {
        return problem(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID", ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"));
    }
    private ApiProblemException problem(HttpStatus status, String code, ApiMessage message) {
        return new ApiProblemException(status, code, message, message, false);
    }
}
