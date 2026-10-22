package dev.agenvas.mcp.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.MediaDraftRestoreService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.mcp.domain.McpOperation;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.VideoCompositionService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Delegated commands enter existing creative modules without a model turn or synthetic Run.
 * Project lock -> token authorization lock -> command ledger -> business state is the write order.
 */
@Service
public class McpToolService {
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_INLINE_BYTES = 24 * 1024;
    private static final int MAX_TOTAL_INLINE_BYTES = 160 * 1024;
    private final McpToolPolicy policy;
    private final McpTokenService tokens;
    private final McpOperationRepository operations;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ArtifactService artifacts;
    private final CanvasService canvas;
    private final CanvasItemQueryService canvasItems;
    private final MediaDraftService drafts;
    private final MediaDraftRestoreService draftWrites;
    private final DirectMediaTaskService media;
    private final TaskService tasks;
    private final VideoCompositionService compositions;
    private final MediaCapabilityService capabilities;
    private final AssetService assets;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean recoveryMode;

    public McpToolService(McpToolPolicy policy, McpTokenService tokens, McpOperationRepository operations,
            ProjectService projects, ProjectEventService events, ArtifactService artifacts,
            CanvasService canvas, CanvasItemQueryService canvasItems, MediaDraftService drafts,
            MediaDraftRestoreService draftWrites, DirectMediaTaskService media, TaskService tasks,
            MediaCapabilityService capabilities, AssetService assets, VideoCompositionService compositions, ObjectMapper mapper, Clock clock,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) {
        this.policy = policy; this.tokens = tokens; this.operations = operations; this.projects = projects;
        this.events = events; this.artifacts = artifacts; this.canvas = canvas; this.canvasItems = canvasItems;
        this.drafts = drafts; this.draftWrites = draftWrites; this.media = media; this.tasks = tasks;
        this.capabilities = capabilities; this.assets = assets; this.mapper = mapper; this.clock = clock;
        this.compositions = compositions;
        this.recoveryMode = recoveryMode;
    }

    /** Every call reauthorizes current persisted scopes and project ownership, including replays. */
    public McpToolResponse execute(McpPrincipal supplied, String name, JsonNode arguments) {
        McpPrincipal principal = tokens.requireValidForRead(supplied);
        McpScope required = policy.requiredScope(name);
        requireScope(principal, required);
        policy.validate(name, arguments);
        if (required == McpScope.READ) return read(principal, name, arguments);
        if (recoveryMode) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_MODE_READ_ONLY", ApiMessage.of("api.recovery-mode-write-guard.database-and-media-backups-are-being-reconciled-project-and-model"));
        UUID project = uuid(arguments, "projectId");
        return events.recordChange(principal.userId(), project, () -> {
            McpPrincipal locked = tokens.requireValidForWrite(principal);
            requireScope(locked, required);
            projects.requireActiveProject(locked.userId(), project);
            String command = arguments.path("commandId").asText();
            String hash = requestHash(name, arguments);
            UUID operationId = UUID.randomUUID();
            McpOperation operation = new McpOperation(operationId, locked.userId(), locked.tokenId(), project,
                    command, name, hash, mapper.createObjectNode(), clock.instant());
            if (!operations.reserve(operation)) {
                McpOperation previous = operations.find(locked.userId(), command).orElseThrow();
                if (!previous.requestHash().equals(hash) || !previous.projectId().equals(project)
                        || !previous.toolName().equals(name)) throw problem(HttpStatus.CONFLICT, "MCP_COMMAND_CONFLICT", ApiMessage.of("api.mcp.command-conflict"));
                return ProjectEventService.Change.unchanged(new McpToolResponse(previous.result().deepCopy()));
            }
            ObjectNode output = write(locked, project, operationId, name, arguments);
            output.put("operationId", operationId.toString());
            // Persist and return the same JSON representation (e.g. integral decimal coordinates).
            JsonNode snapshot = mapper.readTree(output.toString());
            operations.complete(operationId, snapshot);
            return ProjectEventService.Change.unchanged(new McpToolResponse(snapshot));
        }).value();
    }

    private McpToolResponse read(McpPrincipal principal, String name, JsonNode input) {
        UUID owner = principal.userId();
        if ("list_projects".equals(name)) {
            var page = projects.list(owner, input.path("includeArchived").asBoolean(), nullableText(input, "cursor"),
                    input.has("limit") ? input.path("limit").intValue() : null);
            ObjectNode output = mapper.createObjectNode();
            ArrayNode items = output.putArray("items");
            String search = input.path("name").asText("").toLowerCase(java.util.Locale.ROOT);
            for (Project project : page.items()) if (project.name().toLowerCase(java.util.Locale.ROOT).contains(search))
                items.addObject().put("projectId", project.id().toString()).put("name", project.name())
                        .put("status", project.status().name()).put("aspectRatio", project.aspectRatio().name());
            output.put("nextCursor", page.nextCursor());
            return new McpToolResponse(output);
        }
        UUID project = uuid(input, "projectId");
        Project authorized = projects.get(owner, project);
        return switch (name) {
            case "read_project_summary" -> {
                List<ArtifactService.ArtifactView> content = artifacts.listProject(owner, project);
                ObjectNode output = mapper.createObjectNode().put("projectId", project.toString())
                        .put("name", authorized.name()).put("status", authorized.status().name())
                        .put("aspectRatio", authorized.aspectRatio().name())
                        .put("nodeCount", canvas.list(owner, project).size()).put("artifactCount", content.size());
                ObjectNode counts = output.putObject("counts");
                for (Artifact.Kind kind : Artifact.Kind.values()) counts.put(kind.name(), content.stream()
                        .filter(a -> a.artifact().kind() == kind).count());
                yield new McpToolResponse(output);
            }
            case "read_canvas" -> new McpToolResponse(readCanvas(owner, project, input));
            case "read_artifacts" -> readArtifacts(owner, project, input);
            case "list_media_capabilities" -> new McpToolResponse(readCapabilities(input));
            case "read_task_status" -> {
                ObjectNode output = mapper.createObjectNode();
                ArrayNode items = output.putArray("items");
                for (JsonNode id : input.path("taskIds")) items.add(task(tasks.get(owner, project, UUID.fromString(id.asText()))));
                yield new McpToolResponse(output);
            }
            default -> throw McpToolPolicy.invalid();
        };
    }

    private ObjectNode readCanvas(UUID owner, UUID project, JsonNode input) {
        List<CanvasService.CanvasEntry> entries = canvas.list(owner, project);
        int offset = input.path("offset").asInt(0), limit = input.path("limit").asInt(20);
        ObjectNode output = page(offset, limit, entries.size());
        ArrayNode items = output.putArray("items");
        for (int index = offset; index < Math.min(entries.size(), offset + limit); index++) {
            var entry = entries.get(index);
            items.add(node(owner, project, entry.item(), entry.artifact(), input.path("includeDrafts").asBoolean()));
        }
        return output;
    }

    private McpToolResponse readArtifacts(UUID owner, UUID project, JsonNode input) {
        ObjectNode output = mapper.createObjectNode();
        ArrayNode items = output.putArray("items");
        List<McpToolResponse.Image> images = new ArrayList<>();
        // Authorize the complete batch before opening a single private preview stream.
        List<ArtifactVersion> versions = input.path("versionIds").valueStream()
                .map(id -> artifacts.requireVersionById(owner, project, UUID.fromString(id.asText()))).toList();
        long imageCount = versions.stream().filter(v -> artifacts.get(owner, project, v.artifactId())
                .artifact().kind() == Artifact.Kind.IMAGE).count();
        if (imageCount > 1) throw McpToolPolicy.invalid();
        int inline = 0;
        for (ArtifactVersion version : versions) {
            ArtifactService.ArtifactView view = artifacts.get(owner, project, version.artifactId());
            ObjectNode item = artifact(view, version);
            if (view.artifact().kind() == Artifact.Kind.TEXT) {
                String content = version.content().toString();
                int size = content.getBytes(StandardCharsets.UTF_8).length;
                if (size <= MAX_INLINE_BYTES && inline + size <= MAX_TOTAL_INLINE_BYTES) {
                    item.set("content", version.content().deepCopy()); inline += size;
                } else {
                    item.remove("content"); item.put("contentTruncated", true);
                    item.put("contentPreview", content.substring(0, content.offsetByCodePoints(0,
                            Math.min(2000, content.codePointCount(0, content.length())))));
                }
            } else {
                Asset metadata = assets.metadata(owner, project, UUID.fromString(version.content().path("assetId").asText()));
                item.set("media", mediaMetadata(metadata));
                if (metadata.mediaKind() == Asset.MediaKind.IMAGE) {
                    var preview = assets.content(owner, project, metadata.id(), true);
                    if (preview.size() <= 0 || preview.size() > MAX_IMAGE_BYTES || !preview.contentType().startsWith("image/"))
                        throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "MCP_IMAGE_PREVIEW_INVALID", ApiMessage.of("api.mcp.image-preview-invalid"));
                    try (var stream = assets.open(preview, 0, preview.size())) {
                        byte[] bytes = stream.readNBytes(Math.toIntExact(preview.size()) + 1);
                        if (bytes.length != preview.size()) throw new IOException("Incomplete controlled image preview");
                        item.put("imageIndex", images.size());
                        images.add(new McpToolResponse.Image(preview.contentType(), Base64.getEncoder().encodeToString(bytes)));
                    } catch (IOException failure) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "MCP_IMAGE_PREVIEW_INVALID", ApiMessage.of("api.mcp.image-preview-invalid")); }
                }
            }
            items.add(item);
        }
        return new McpToolResponse(output, images);
    }

    private ObjectNode write(McpPrincipal principal, UUID project, UUID operation, String name, JsonNode input) {
        UUID owner = principal.userId();
        return switch (name) {
            case "create_text", "create_media_node" -> {
                Artifact.Kind kind = "create_text".equals(name) ? Artifact.Kind.TEXT : Artifact.Kind.valueOf(input.path("kind").asText());
                var created = artifacts.createFromExternalTool(owner, project, kind, input.path("title").asText(),
                        kind == Artifact.Kind.TEXT ? textContent(input) : null);
                CanvasItem item;
                if (input.has("placement")) {
                    JsonNode p = input.path("placement"); UUID id = UUID.randomUUID();
                    canvas.apply(owner, project, List.of(new CanvasService.PlaceArtifact(id, created.artifact().id(),
                            p.path("x").decimalValue(), p.path("y").decimalValue(), p.path("width").decimalValue(),
                            p.path("height").decimalValue(), p.path("zIndex").asInt(0), null, false)));
                    item = canvasItems.requireArtifactItem(owner, project, id);
                } else item = canvas.placeGeneratedArtifactWithinChange(owner, project, created.artifact().id(), null);
                ObjectNode output = mapper.createObjectNode(); output.set("node", node(owner, project, item, created, true));
                if (created.resourceDefaultVersion() != null) {
                    output.set("artifact", artifact(created, created.resourceDefaultVersion()));
                    operations.linkVersion(operation, created.resourceDefaultVersion().id());
                }
                yield output;
            }
            case "revise_artifact" -> {
                var revised = artifacts.reviseFromExternalTool(owner, project, uuid(input, "artifactId"),
                        input.path("expectedVersion").longValue(), nullableText(input, "title"), textContent(input));
                operations.linkVersion(operation, revised.resourceDefaultVersion().id());
                ObjectNode output = mapper.createObjectNode(); output.set("artifact", artifact(revised, revised.resourceDefaultVersion())); yield output;
            }
            case "manage_canvas_node" -> {
                CanvasItem before = canvasItems.requireArtifactItem(owner, project, uuid(input, "canvasItemId"));
                requireUnlocked(before);
                long expected = input.path("expectedVersion").longValue();
                if (before.version() != expected) throw problem(HttpStatus.CONFLICT, "CANVAS_VERSION_CONFLICT", ApiMessage.of("api.canvas-service.the-canvas-card-has-been-modified-by-other-requests-please"));
                CanvasService.CanvasCommand command = switch (input.path("action").asText()) {
                    case "RENAME" -> new CanvasService.UpdateTitle(before.id(), expected, input.path("title").asText());
                    case "MOVE" -> new CanvasService.UpdateLayout(before.id(), expected, input.path("x").decimalValue(),
                            input.path("y").decimalValue(), before.width(), before.height(), before.zIndex(), before.groupId());
                    case "RESIZE" -> new CanvasService.UpdateLayout(before.id(), expected, before.x(), before.y(),
                            input.path("width").decimalValue(), input.path("height").decimalValue(), before.zIndex(), before.groupId());
                    default -> throw McpToolPolicy.invalid();
                };
                canvas.apply(owner, project, List.of(command));
                CanvasItem updated = canvasItems.requireArtifactItem(owner, project, before.id());
                ObjectNode output = mapper.createObjectNode(); output.set("node", node(owner, project, updated,
                        artifacts.get(owner, project, updated.subjectId()), true)); yield output;
            }
            case "update_media_draft" -> {
                CanvasItem item = canvasItems.requireArtifactItem(owner, project, uuid(input, "canvasItemId"));
                requireMediaMutable(owner, project, item);
                MediaDraft before = drafts.get(owner, project, item.id()); JsonNode patch = input.path("patch");
                List<MediaDraftService.SaveMediaInput> inputs = patch.has("mediaInputs")
                        ? patch.path("mediaInputs").valueStream().map(v -> new MediaDraftService.SaveMediaInput(
                                uuid(v, "versionId"), MediaDraft.InputRole.valueOf(v.path("role").asText()), v.path("color").asText().toUpperCase(java.util.Locale.ROOT))).toList()
                        : before.mediaInputs().stream().map(v -> new MediaDraftService.SaveMediaInput(v.versionId(), v.role(), v.color())).toList();
                List<MediaDraft.PromptMention> mentions = patch.has("mentions") ? patch.path("mentions").valueStream()
                        .map(v -> new MediaDraft.PromptMention(uuid(v, "versionId"), MediaDraft.InputRole.valueOf(v.path("role").asText()))).toList() : before.mentions();
                var mode = patch.has("videoInputMode") ? patch.path("videoInputMode").isNull() ? null
                        : MediaDraft.VideoInputMode.valueOf(patch.path("videoInputMode").asText()) : before.videoInputMode();
                long expected = input.path("expectedDraftVersion").longValue();
                MediaDraft updated;
                String prompt = patch.has("prompt") ? patch.path("prompt").asText() : before.prompt();
                JsonNode parameters = patch.has("parameters") ? patch.path("parameters") : before.parameters();
                Integer duration = patch.has("durationSeconds") ? patch.path("durationSeconds").isNull() ? null : patch.path("durationSeconds").intValue() : before.durationSeconds();
                UUID capability = patch.has("capabilityId") ? nullableUuid(patch, "capabilityId") : before.capabilityId();
                UUID style = patch.has("styleId") ? nullableUuid(patch, "styleId") : before.styleId();
                if (patch.has("mediaInputs")) updated = draftWrites.replaceInputs(owner, project, item.id(), expected,
                        prompt, parameters, duration, capability, mode, inputs, mentions, style);
                else updated = draftWrites.save(owner, project, item.id(), expected, prompt, parameters,
                        duration, capability, mode, inputs, mentions, style);
                ObjectNode output = mapper.createObjectNode().put("canvasItemId", item.id().toString());
                output.set("draft", draft(updated, true)); yield output;
            }
            case "run_media" -> {
                CanvasItem item = canvasItems.requireArtifactItem(owner, project, uuid(input, "canvasItemId"));
                requireUnlocked(item);
                List<Task> accepted = media.runExternal(owner, project, item.subjectId(), item.id(),
                        input.path("expectedDraftVersion").longValue(), principal.tokenId(), operation);
                ObjectNode output = mapper.createObjectNode(); ArrayNode acceptedTasks = output.putArray("tasks");
                for (int index = 0; index < accepted.size(); index++) {
                    operations.linkTask(operation, accepted.get(index).id(), index); acceptedTasks.add(task(accepted.get(index)));
                }
                yield output;
            }
            case "compose_video" -> {
                Task accepted = compositions.runExternal(owner, project, input.path("composition"), principal.tokenId(), operation);
                operations.linkTask(operation, accepted.id(), 0);
                ObjectNode output = mapper.createObjectNode(); output.putArray("tasks").add(task(accepted));
                yield output;
            }
            default -> throw McpToolPolicy.invalid();
        };
    }

    private ObjectNode artifact(ArtifactService.ArtifactView view, ArtifactVersion version) {
        ObjectNode result = mapper.createObjectNode().put("artifactId", version.artifactId().toString())
                .put("versionId", version.id().toString()).put("kind", view.artifact().kind().name())
                .put("title", view.artifact().title()).put("versionNo", version.versionNo())
                .put("createdByKind", version.createdByKind().name()).put("contentTruncated", false);
        if (version.id().equals(view.artifact().resourceDefaultVersionId())) result.put("expectedVersion", view.artifact().version());
        if (view.artifact().kind() == Artifact.Kind.TEXT) result.set("content", version.content().deepCopy());
        else {
            // Only immutable creative text and our local render plan are exposed, never provider settings/URLs.
            String prompt = version.content().path("prompt").asText("");
            result.put("generationPrompt", prompt.substring(0, Math.min(prompt.length(), 20000)));
            if (prompt.length() > 20000) result.put("contentTruncated", true);
            JsonNode composition = version.content().path("parameters").path("videoComposition");
            if (composition.isObject()) result.set("composition", composition.deepCopy());
        }
        return result;
    }
    private ObjectNode node(UUID owner, UUID project, CanvasItem item, ArtifactService.ArtifactView view, boolean fullDraft) {
        ObjectNode output = mapper.createObjectNode().put("canvasItemId", item.id().toString())
                .put("artifactId", item.subjectId().toString()).put("kind", view.artifact().kind().name())
                .put("title", item.title()).put("expectedVersion", item.version()).put("artifactVersion", view.artifact().version())
                .put("versionId", view.artifact().kind() == Artifact.Kind.TEXT
                        ? nullableId(view.artifact().resourceDefaultVersionId()) : nullableId(item.selectedVersionId()))
                .put("locked", item.locked()).put("x", item.x()).put("y", item.y()).put("width", item.width())
                .put("height", item.height()).put("zIndex", item.zIndex());
        if (view.artifact().kind() != Artifact.Kind.TEXT) output.set("mediaDraft", draft(drafts.get(owner, project, item.id()), fullDraft));
        return output;
    }
    private ObjectNode draft(MediaDraft draft, boolean full) {
        ObjectNode result = mapper.createObjectNode().put("version", draft.version())
                .put("capabilityId", nullableId(draft.capabilityId())).put("styleId", nullableId(draft.styleId()))
                .put("videoInputMode", draft.videoInputMode() == null ? null : draft.videoInputMode().name());
        if (draft.durationSeconds() == null) result.putNull("durationSeconds"); else result.put("durationSeconds", draft.durationSeconds());
        if (full) {
            result.put("prompt", draft.prompt()); result.set("parameters", draft.parameters().deepCopy());
            ArrayNode inputs = result.putArray("mediaInputs");
            for (var input : draft.mediaInputs()) inputs.addObject().put("versionId", input.versionId().toString())
                    .put("artifactId", input.artifactId().toString()).put("role", input.role().name())
                    .put("order", input.order()).put("color", input.color());
            result.set("mentions", mapper.valueToTree(draft.mentions()));
        } else result.put("promptCharacters", draft.prompt().length()).put("mediaInputCount", draft.mediaInputs().size())
                .put("mentionCount", draft.mentions().size());
        return result;
    }
    private ObjectNode task(Task task) {
        ObjectNode output = mapper.createObjectNode().put("taskId", task.id().toString()).put("kind", task.kind().name())
                .put("status", task.status().name()).put("cancelRequested", task.cancelRequested()).put("attemptNo", task.attemptNo())
                .put("errorCode", task.errorCode()).put("updatedAt", task.updatedAt().toString())
                .put("completedAt", task.completedAt() == null ? null : task.completedAt().toString())
                .put("source", task.initiator().name());
        for (String field : List.of("canvasItemId", "artifactId")) output.put(field, task.input().path(field).asText(null));
        output.put("operationId", task.input().path("mcpOperationId").asText(null));
        // Text workers retain a private response checkpoint alongside a separate public result.
        // Only a succeeded text Task can disclose that result, never checkpoint fields.
        JsonNode publicResult = task.output();
        if (task.kind() == Task.Kind.TEXT_GENERATION) publicResult = task.status() == Task.Status.SUCCEEDED
                && publicResult != null ? publicResult.path("result") : null;
        output.put("artifactVersionId", publicResult == null ? null : publicResult.path("artifactVersionId").asText(null));
        if (task.input().path("mediaInput").isObject()) {
            JsonNode pinned = task.input().path("mediaInput");
            ObjectNode summary = output.putObject("inputSummary").put("draftVersion", task.input().path("draftVersion").asLong())
                    .put("generationIndex", task.input().path("generationIndex").asInt())
                    .put("generationCount", task.input().path("generationCount").asInt(1));
            for (String field : List.of("capabilityId", "capabilityVersion", "mode", "durationSeconds"))
                if (pinned.has(field)) summary.set(field, pinned.path(field).deepCopy());
            summary.put("promptCharacters", pinned.path("prompt").asText().length());
            ArrayNode references = summary.putArray("inputVersionIds");
            for (String group : List.of("images", "audios", "videos"))
                for (JsonNode reference : pinned.path(group)) references.add(reference.path("versionId").asText());
        }
        ArrayNode extra = output.putArray("additionalResults");
        if (task.output() != null) for (JsonNode result : task.output().path("additionalResults")) {
            ObjectNode safe = extra.addObject();
            for (String field : List.of("artifactId", "artifactVersionId", "canvasItemId", "selected", "ordinal"))
                if (result.has(field)) safe.set(field, result.path(field).deepCopy());
        }
        return output;
    }
    private ObjectNode readCapabilities(JsonNode input) {
        String kind = input.path("kind").asText("");
        var candidates = capabilities.publishedCandidates().stream().filter(c -> kind.isEmpty() || c.kind().name().equals(kind + "_GENERATION"))
                .sorted(Comparator.comparing(c -> c.binding().capabilityId())).toList();
        int offset = input.path("offset").asInt(0), limit = input.path("limit").asInt(20);
        ObjectNode output = page(offset, limit, candidates.size()); ArrayNode items = output.putArray("items");
        for (int index = offset; index < Math.min(candidates.size(), offset + limit); index++) {
            var candidate = candidates.get(index);
            items.addObject().put("capabilityId", candidate.binding().capabilityId().toString())
                    .put("name", candidate.capabilityName()).put("kind", candidate.kind().name().replace("_GENERATION", ""))
                    .put("version", candidate.binding().capabilityVersion()).put("minimumSeconds", candidate.minimumSeconds())
                    .put("maximumSeconds", candidate.maximumSeconds()).set("settings", safeCapabilitySettings(candidate));
        }
        return output;
    }
    /** External callers need editable input semantics, never graph mappings or fixed bindings. */
    private ObjectNode safeCapabilitySettings(MediaCapabilityService.Candidate candidate) {
        ObjectNode result = mapper.createObjectNode();
        JsonNode settings = candidate.settings();
        for (String field : List.of("defaultParameters", "defaultDurationSeconds", "minimumSeconds", "maximumSeconds",
                "maxReferenceImages", "maxReferenceAudios", "maxReferenceVideos", "pricing", "pricingByResolution",
                "nativeInputs", "nativeReusableSlots", "klingInputs", "model", "operation", "videoResolution", "videoResolutions"))
            if (settings.has(field)) result.set(field, settings.path(field).deepCopy());
        var policy = capabilities.inputPolicy(candidate.binding());
        result.set("supportedVideoInputModes", mapper.valueToTree(policy.supportedVideoInputModes().stream().sorted().toList()));
        result.set("supportedImageAspectRatios", mapper.valueToTree(policy.supportedImageAspectRatios().stream().sorted().toList()));
        result.set("supportedImageResolutions", mapper.valueToTree(policy.supportedImageResolutions().stream().sorted().toList()));
        result.set("supportedImageQualities", mapper.valueToTree(policy.supportedImageQualities().stream().sorted().toList()));
        result.put("maxReferenceImages", policy.maxReferenceImages()).put("maxReferenceAudios", policy.maxReferenceAudios())
                .put("maxReferenceVideos", policy.maxReferenceVideos()).put("supportsTransparentBackground", policy.supportsTransparentBackground())
                .put("supportsEndFrame", policy.supportsEndFrame()).put("defaultVideoInputMode", policy.defaultVideoInputMode());
        ArrayNode inputs = result.putArray("inputs");
        for (var field : capabilities.declaredDraftInputs(candidate.binding().capabilityId())) {
            JsonNode raw = mapper.valueToTree(field); ObjectNode safe = inputs.addObject();
            for (String key : List.of("key", "label", "description", "type", "required", "defaultValue", "minimum", "maximum",
                    "maxLength", "options", "advanced", "source", "enabledWhen"))
                if (raw.has(key)) safe.set(key, raw.path(key).deepCopy());
        }
        if (!inputs.isEmpty() || result.has("nativeInputs") || result.has("klingInputs"))
            result.put("parameterNamespace", dev.agenvas.provider.domain.RunningHubDefinition.VALUES_PROPERTY);
        return result;
    }

    private ObjectNode mediaMetadata(Asset asset) {
        ObjectNode result = mapper.createObjectNode().put("kind", asset.mediaKind().name()).put("contentType", asset.contentType()).put("byteSize", asset.byteSize());
        if (asset.width() != null) result.put("width", asset.width());
        if (asset.height() != null) result.put("height", asset.height());
        if (asset.durationMs() != null) result.put("durationMs", asset.durationMs());
        return result;
    }
    private ObjectNode page(int offset, int limit, int total) {
        if (offset > total) throw McpToolPolicy.invalid();
        ObjectNode result = mapper.createObjectNode().put("offset", offset).put("total", total);
        if ((long) offset + limit < total) result.put("nextOffset", offset + limit); else result.putNull("nextOffset");
        return result;
    }
    private ObjectNode textContent(JsonNode input) { return mapper.createObjectNode().put("format", input.path("format").asText("MARKDOWN")).put("text", input.path("text").asText()); }
    private void requireMediaMutable(UUID owner, UUID project, CanvasItem item) {
        requireUnlocked(item);
        media.requireUnoccupied(owner, project, item.subjectId(), item.id());
    }
    private void requireUnlocked(CanvasItem item) {
        if (item.locked()) throw problem(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED", ApiMessage.of("api.creative-artifact-tool-service.locked-cards-cannot-be-arranged-by-the-agent"));
    }
    private void requireScope(McpPrincipal principal, McpScope required) {
        if (!principal.scopes().contains(McpScope.READ) || !principal.scopes().contains(required))
            throw problem(HttpStatus.FORBIDDEN, "MCP_SCOPE_DENIED", ApiMessage.of("api.mcp.scope-denied"));
    }
    private UUID uuid(JsonNode input, String field) { return UUID.fromString(input.path(field).asText()); }
    private UUID nullableUuid(JsonNode input, String field) { return input.path(field).isNull() ? null : uuid(input, field); }
    private String nullableId(UUID id) { return id == null ? null : id.toString(); }
    private String nullableText(JsonNode input, String field) { return input.hasNonNull(field) ? input.path(field).asText() : null; }
    private ApiProblemException problem(HttpStatus status, String code, ApiMessage message) {
        return new ApiProblemException(status, code, message, message, false);
    }
    /** JSON objects are sorted recursively; equal numeric values and field order hash identically. */
    String requestHash(String name, JsonNode arguments) {
        return Sha256.hex(name + "\n" + canonical(arguments).toString());
    }
    private JsonNode canonical(JsonNode input) {
        if (input.isObject()) {
            ObjectNode object = mapper.createObjectNode(); input.propertyNames().stream().sorted()
                    .forEach(key -> object.set(key, canonical(input.path(key)))); return object;
        }
        if (input.isArray()) { ArrayNode array = mapper.createArrayNode(); input.forEach(v -> array.add(canonical(v))); return array; }
        if (input.isNumber()) return mapper.getNodeFactory().numberNode(input.decimalValue().stripTrailingZeros());
        return input.deepCopy();
    }
}
