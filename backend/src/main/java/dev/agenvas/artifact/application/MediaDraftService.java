package dev.agenvas.artifact.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.domain.Task;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persists generation input separately from immutable media results. */
@Service
public class MediaDraftService {
    private static final int MAX_PROMPT_LENGTH = 20_000;
    private static final int MIN_VIDEO_SECONDS = 1;
    private static final int MAX_VIDEO_SECONDS = 30;
    private static final int MAX_MEDIA_INPUTS = 14;
    // Each marker maps positionally to one exact-version structured prompt mention.
    private static final char MENTION_MARKER = '\uFFFC';
    private static final String COLOR_PATTERN = "^#[0-9A-F]{6}$";
    private static final List<String> INPUT_COLORS = List.of(
            "#7C3AED", "#0EA5E9", "#F97316", "#10B981",
            "#EC4899", "#EAB308", "#6366F1", "#14B8A6",
            "#DC2626", "#0891B2", "#9333EA", "#65A30D",
            "#C2410C", "#4F46E5");

    private final ProjectService projects;
    private final ArtifactService artifactService;
    private final ArtifactRepository artifacts;
    private final CanvasItemQueryService canvasItems;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MediaCapabilityService capabilities;
    private final MediaStyleService styles;

    public MediaDraftService(ProjectService projects, ArtifactService artifactService,
            ArtifactRepository artifacts, CanvasItemQueryService canvasItems,
            ProjectEventService events, ObjectMapper mapper, Clock clock,
            @Lazy MediaCapabilityService capabilities, MediaStyleService styles) {
        this.projects = projects;
        this.artifactService = artifactService;
        this.artifacts = artifacts;
        this.canvasItems = canvasItems;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.capabilities = capabilities;
        this.styles = styles;
    }

    @Transactional(readOnly = true)
    public MediaDraft get(UUID ownerId, UUID projectId, UUID canvasItemId) {
        requireMediaCanvas(ownerId, projectId, canvasItemId);
        return artifacts.findMediaDraft(projectId, canvasItemId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND", ApiMessage.of("api.media-draft-service.draft-does-not-exist"), ApiMessage.of("api.media-draft-service.this-media-product-does-not-have-a-working-draft"), false));
    }

    @Transactional
    public MediaDraft save(UUID ownerId, UUID projectId, UUID canvasItemId,
            long expectedVersion, String prompt, JsonNode parameters,
            Integer durationSeconds, UUID capabilityId,
            MediaDraft.VideoInputMode requestedMode, List<SaveMediaInput> requestedInputs,
            List<MediaDraft.PromptMention> requestedMentions, UUID styleId) {
        projects.requireActiveProject(ownerId, projectId);
        Artifact.Kind kind = requireMediaCanvas(ownerId, projectId, canvasItemId).kind();
        if (expectedVersion < 0 || prompt == null || prompt.length() > MAX_PROMPT_LENGTH) {
            throw invalid(ApiMessage.of("api.media-draft-service.the-draft-version-or-prompt-word-is-invalid"));
        }
        JsonNode normalizedParameters = parameters == null
                ? mapper.createObjectNode() : parameters;
        if (!normalizedParameters.isObject()) {
            throw invalid(ApiMessage.of("api.media-draft-service.media-parameters-must-be-objects"));
        }
        Task.Kind taskKind = Task.Kind.valueOf(kind.name() + "_GENERATION");
        var binding = capabilityId == null ? null : capabilities.forDraft(capabilityId, taskKind);
        var definition = binding == null ? null : capabilities.runningHubDefinition(binding);
        var comfy = binding == null ? null : capabilities.comfyWorkflowDefinition(binding);
        boolean dynamic = definition != null;
        JsonNode standardParameters = comfy == null ? normalizedParameters : dev.agenvas.provider.domain.ComfyUiWorkflowDefinition.standardParameters(normalizedParameters);
        if (dynamic) definition.values(mapper, normalizedParameters, prompt, durationSeconds, false);
        else if (kind == Artifact.Kind.IMAGE) ImageGenerationParameters.parse(standardParameters);
        else if (kind == Artifact.Kind.VIDEO) VideoGenerationParameters.parse(standardParameters);
        else dev.agenvas.artifact.domain.AudioGenerationParameters.parse(standardParameters);
        List<SaveMediaInput> inputCommands = requestedInputs == null
                ? List.of() : List.copyOf(requestedInputs);
        List<MediaDraft.PromptMention> mentions = requestedMentions == null
                ? List.of() : List.copyOf(requestedMentions);
        if (prompt.chars().filter(character -> character == MENTION_MARKER).count()
                != mentions.size()) {
            throw invalid(ApiMessage.of("api.media-draft-service.image-tags-in-prompt-words-are-inconsistent-with-structured-quotes"));
        }
        MediaDraft persisted = artifacts.findMediaDraft(projectId, canvasItemId)
                .orElseThrow(() -> new IllegalStateException("Media draft missing"));
        styles.validateSelection(styleId, kind);
        if (inputCommands.size() > MAX_MEDIA_INPUTS) {
            throw invalid(ApiMessage.of("api.media-draft-service.a-single-card-can-save-up-to-14-media-inputs"));
        }
        MediaDraft.VideoInputMode mode = kind != Artifact.Kind.VIDEO ? null
                : requestedMode == null
                        ? inputCommands.isEmpty() ? MediaDraft.VideoInputMode.TEXT
                                : MediaDraft.VideoInputMode.GENERAL_REFERENCE
                        : requestedMode;
        if (kind != Artifact.Kind.VIDEO && durationSeconds != null) {
            throw invalid(ApiMessage.of("api.media-draft-service.image-drafts-cannot-specify-video-duration"));
        }
        if (kind == Artifact.Kind.VIDEO && durationSeconds != null &&
                (durationSeconds < MIN_VIDEO_SECONDS || durationSeconds > (dynamic ? MediaAdapterRegistry.RUNNINGHUB_MAX_VIDEO_SECONDS : MAX_VIDEO_SECONDS))) {
            throw invalid(dynamic ? ApiMessage.of("api.media-draft-service.video-duration-must-be-an-integer-from-1-60-seconds") : ApiMessage.of("api.media-draft-service.video-length-must-be-an-integer-between-1-30-seconds"));
        }
        if (!dynamic && mode == MediaDraft.VideoInputMode.TEXT && !inputCommands.isEmpty()) {
            throw invalid(ApiMessage.of("api.media-draft-service.text-only-video-mode-cannot-save-image-input"));
        }
        Set<UUID> seen = new HashSet<>();
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        for (int order = 0; order < inputCommands.size(); order++) {
            SaveMediaInput command = inputCommands.get(order);
            if (command == null || command.versionId() == null || command.role() == null
                    || command.color() == null || !command.color().matches(COLOR_PATTERN)
                    || !seen.add(command.versionId())) {
                throw invalid(ApiMessage.of("api.media-draft-service.image-input-must-be-complete-have-valid-colors-and-be"));
            }
            ArtifactRepository.VersionTarget target = artifacts
                    .findVersionTarget(projectId, command.versionId())
                    .orElseThrow(() -> invalid(ApiMessage.of("api.media-draft-service.the-input-image-version-does-not-exist-in-this-project")));
            boolean alreadyReferenced = persisted.mediaInputs().stream()
                    .anyMatch(input -> input.versionId().equals(command.versionId()));
            if (target.kind() != (mediaKind(command.role()))
                    || artifactService.get(ownerId, projectId, target.artifactId())
                            .artifact().archivedAt() != null && !alreadyReferenced) {
                throw invalid(ApiMessage.of("api.media-draft-service.image-input-must-be-an-exact-version-of-the-unarchived"));
            }
            if (dynamic) {
                if (!Set.of(MediaDraft.InputRole.REFERENCE, MediaDraft.InputRole.AUDIO_REFERENCE, MediaDraft.InputRole.VIDEO_REFERENCE).contains(command.role())) throw invalid(ApiMessage.of("api.media-draft-service.dynamic-assets-can-only-use-image-audio-or-video-reference"));
            } else validateRole(kind, mode, command.role(), order, inputCommands.size());
            inputs.add(new MediaDraft.MediaInput(command.versionId(), target.artifactId(),
                    command.role(), order, command.color(), List.of(
                            new MediaDraft.InputSource(UUID.randomUUID(),
                                    MediaDraft.SourceType.MANUAL, null))));
        }
        if (dynamic) validateSlots(definition, normalizedParameters, inputs, false);
        if (comfy != null) {
            ObjectNode values = mapper.createObjectNode();
            values.set(RunningHubDefinition.VALUES_PROPERTY, comfy.values(mapper, normalizedParameters, prompt, durationSeconds,
                    inputs.stream().map(MediaDraft.MediaInput::versionId).toList(), false));
            validateSlots(comfy.inputs(), values, inputs, false);
        }
        for (MediaDraft.PromptMention mention : mentions) {
            if (mention == null || mention.versionId() == null || mention.role() == null
                    || inputs.stream().noneMatch(input -> input.versionId().equals(
                                    mention.versionId())
                            && input.role() == mention.role())) {
                throw invalid(ApiMessage.of("api.media-draft-service.image-tags-can-only-reference-the-exact-version-and-role"));
            }
        }
        return events.recordChange(ownerId, projectId, () -> {
            MediaDraft before = artifacts.findMediaDraft(projectId, canvasItemId)
                    .orElseThrow(() -> new IllegalStateException("Media draft missing"));
            List<MediaDraft.MediaInput> effectiveInputs = mergeInputs(
                    before.mediaInputs(), inputs);
            MediaDraft update = new MediaDraft(projectId, canvasItemId, prompt,
                    normalizedParameters.deepCopy(), durationSeconds, capabilityId, styleId,
                    mode, effectiveInputs, mentions,
                    before.displayMode(),
                    expectedVersion + 1, before.createdAt(), clock.instant());
            if (!artifacts.updateMediaDraft(update, expectedVersion)) {
                throw draftConflict(ApiMessage.of("api.media-draft-service.the-draft-has-been-modified-by-other-operations-please-retain"));
            }
            artifacts.replaceMediaInputs(projectId, canvasItemId, effectiveInputs,
                    update.updatedAt());
            ObjectNode payload = mapper.createObjectNode();
            payload.put("canvasItemId", canvasItemId.toString());
            payload.put("draftVersion", update.version());
            return ProjectEventService.Change.changed(update,
                    new ProjectEventService.EventDraft("media.draft.changed", 1,
                            canvasItemId, update.version(), payload));
        }).value();
    }

    /** Replaces the complete working draft from one immutable result's frozen input. */
    @Transactional
    public MediaDraft restoreVersionInputs(UUID ownerId, UUID projectId, UUID canvasItemId,
            UUID versionId, long expectedDraftVersion) {
        Artifact artifact = requireMediaCanvas(ownerId, projectId, canvasItemId);
        var version = artifactService.requireVersion(ownerId, projectId, artifact.id(), versionId);
        JsonNode frozen = version.frozenInput();
        if (frozen == null || !frozen.isObject()) {
            throw invalid(ApiMessage.of("api.media-draft-service.there-is-no-resumable-frozen-build-input-for-this-version"));
        }
        List<SaveMediaInput> inputs = new ArrayList<>();
        JsonNode frozenImages = frozen.path("images");
        if (!frozenImages.isArray()) throw invalid(ApiMessage.of("api.media-draft-service.the-frozen-image-input-is-invalid-for-this-version"));
        int order = 0;
        for (JsonNode image : frozenImages) {
            try {
                inputs.add(new SaveMediaInput(UUID.fromString(image.path("versionId").asText()),
                        MediaDraft.InputRole.valueOf(image.path("role").asText()),
                        INPUT_COLORS.get(order % INPUT_COLORS.size())));
            } catch (IllegalArgumentException exception) {
                throw invalid(ApiMessage.of("api.media-draft-service.the-frozen-image-input-is-invalid-for-this-version"));
            }
            order++;
        }
        for (JsonNode audio : frozen.path("audios")) {
            try {
                inputs.add(new SaveMediaInput(UUID.fromString(audio.path("versionId").asText()),
                        MediaDraft.InputRole.AUDIO_REFERENCE, INPUT_COLORS.get(order++ % INPUT_COLORS.size())));
            } catch (IllegalArgumentException exception) { throw invalid(ApiMessage.of("api.media-draft-service.freeze-audio-input-has-no-effect")); }
        }
        for (JsonNode video : frozen.path("videos")) {
            try { inputs.add(new SaveMediaInput(UUID.fromString(video.path("versionId").asText()),
                    MediaDraft.InputRole.VIDEO_REFERENCE, INPUT_COLORS.get(order++ % INPUT_COLORS.size()))); }
            catch (IllegalArgumentException exception) { throw invalid(ApiMessage.of("api.media-draft-service.freeze-video-input-is-invalid")); }
        }
        List<MediaDraft.PromptMention> mentions = new ArrayList<>();
        JsonNode frozenMentions = frozen.path("mentions");
        if (frozenMentions.isArray()) {
            for (JsonNode mention : frozenMentions) {
                try {
                    mentions.add(new MediaDraft.PromptMention(
                            UUID.fromString(mention.path("versionId").asText()),
                            MediaDraft.InputRole.valueOf(mention.path("role").asText())));
                } catch (IllegalArgumentException exception) {
                    throw invalid(ApiMessage.of("api.media-draft-service.this-version-of-the-frozen-image-tag-is-invalid"));
                }
            }
        }
        MediaDraft.VideoInputMode mode = artifact.kind() != Artifact.Kind.VIDEO ? null
                : parseMode(frozen.path("mode").asText());
        UUID capabilityId;
        try {
            capabilityId = UUID.fromString(frozen.path("capabilityId").asText());
        } catch (IllegalArgumentException exception) {
            throw invalid(ApiMessage.of("api.media-draft-service.the-freeze-ability-input-is-invalid-for-this-version"));
        }
        Integer durationSeconds = artifact.kind() == Artifact.Kind.VIDEO
                && frozen.path("durationSeconds").canConvertToInt()
                ? frozen.path("durationSeconds").intValue() : null;
        return save(ownerId, projectId, canvasItemId, expectedDraftVersion,
                frozen.path("prompt").asText(""), frozen.path("parameters").deepCopy(),
                durationSeconds, capabilityId, mode, inputs, mentions, frozenStyleId(frozen));
    }

    private UUID frozenStyleId(JsonNode frozen) {
        JsonNode style = frozen.path("style");
        return style.hasNonNull("id") ? UUID.fromString(style.path("id").asText()) : null;
    }

    private MediaDraft.VideoInputMode parseMode(String value) {
        try {
            return MediaDraft.VideoInputMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(ApiMessage.of("api.media-draft-service.freeze-video-mode-does-not-work-in-this-version"));
        }
    }

    /** The enclosing project event transaction records why the visible card face changed. */
    public void setDisplayModeWithinChange(UUID projectId, UUID canvasItemId,
            MediaDraft.DisplayMode mode) {
        artifacts.setMediaDraftDisplayMode(projectId, canvasItemId, mode, clock.instant());
    }

    /** Called from the Canvas placement transaction after the item row is inserted. */
    public void initializeWithinChange(UUID projectId, UUID canvasItemId, boolean hasResult) {
        artifacts.createMediaDraft(projectId, canvasItemId, "",
                hasResult ? MediaDraft.DisplayMode.RESULT : MediaDraft.DisplayMode.DRAFT,
                clock.instant());
    }

    /** Copies one persisted branch into a new card; every copied input becomes manual-only. */
    public MediaDraft duplicateWithinChange(UUID ownerId, UUID projectId,
            UUID sourceCanvasItemId, UUID targetCanvasItemId,
            long expectedSourceDraftVersion) {
        MediaDraft source = get(ownerId, projectId, sourceCanvasItemId);
        if (source.version() != expectedSourceDraftVersion) {
            throw draftConflict(ApiMessage.of("api.media-draft-service.the-source-draft-has-changed-before-copying-please-save-and"));
        }
        Instant now = clock.instant();
        artifacts.createMediaDraft(projectId, targetCanvasItemId, "",
                source.displayMode(), now);
        List<MediaDraft.MediaInput> inputs = source.mediaInputs().stream()
                .map(input -> new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                        input.role(), input.order(), input.color(), List.of(
                                new MediaDraft.InputSource(UUID.randomUUID(),
                                        MediaDraft.SourceType.MANUAL, null))))
                .toList();
        MediaDraft duplicate = new MediaDraft(projectId, targetCanvasItemId,
                source.prompt(), source.parameters().deepCopy(), source.durationSeconds(),
                source.capabilityId(), source.styleId(), source.videoInputMode(), inputs,
                List.copyOf(source.mentions()), source.displayMode(), 1, now, now);
        if (!artifacts.updateMediaDraft(duplicate, 0)) {
            throw new IllegalStateException("New duplicate media draft update failed");
        }
        artifacts.replaceMediaInputs(projectId, targetCanvasItemId, inputs, now);
        return duplicate;
    }

    /** A batch result copies the accepted draft, never the user's newer working draft. */
    public void initializeFrozenWithinChange(UUID projectId, UUID canvasItemId, Artifact.Kind kind, JsonNode frozen) {
        initializeWithinChange(projectId, canvasItemId, false);
        Instant now = clock.instant();
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        for (String group : List.of("images", "audios", "videos")) for (JsonNode input : frozen.path(group)) {
            int order = inputs.size();
            inputs.add(new MediaDraft.MediaInput(UUID.fromString(input.path("versionId").asText()),
                    UUID.fromString(input.path("artifactId").asText()), MediaDraft.InputRole.valueOf(input.path("role").asText()),
                    order, INPUT_COLORS.get(order % INPUT_COLORS.size()), List.of(new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null))));
        }
        List<MediaDraft.PromptMention> mentions = new ArrayList<>();
        for (JsonNode mention : frozen.path("mentions")) mentions.add(new MediaDraft.PromptMention(UUID.fromString(mention.path("versionId").asText()), MediaDraft.InputRole.valueOf(mention.path("role").asText())));
        MediaDraft draft = new MediaDraft(projectId, canvasItemId, frozen.path("prompt").asText(""),
                frozen.path("parameters").deepCopy(), kind == Artifact.Kind.VIDEO && frozen.path("durationSeconds").asInt() > 0 ? frozen.path("durationSeconds").asInt() : null,
                UUID.fromString(frozen.path("capabilityId").asText()), frozenStyleId(frozen), kind == Artifact.Kind.VIDEO ? parseMode(frozen.path("mode").asText()) : null,
                List.copyOf(inputs), List.copyOf(mentions), MediaDraft.DisplayMode.DRAFT, 1, now, now);
        if (!artifacts.updateMediaDraft(draft, 0)) throw new IllegalStateException("New batch draft update failed");
        artifacts.replaceMediaInputs(projectId, canvasItemId, inputs, now);
    }

    /** Adds one connection source without duplicating an already selected exact version. */
    public MediaDraft addConnectionInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID imageVersionId, UUID connectionId) {
        return addConnectionInputWithinChange(ownerId, projectId, canvasItemId,
                expectedVersion, imageVersionId, connectionId, false);
    }

    /** Proposal inputs become connection-only so deleting the line also removes the reference. */
    public MediaDraft addConnectionInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID imageVersionId, UUID connectionId,
            boolean replaceManualSource) {
        return addConnectionInputWithinChange(ownerId, projectId, canvasItemId, expectedVersion,
                imageVersionId, connectionId, replaceManualSource, null);
    }

    /** The named slot is validated and assigned in the same write as its exact-version source. */
    public MediaDraft addConnectionInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID imageVersionId, UUID connectionId,
            boolean replaceManualSource, String slotKey) {
        Artifact.Kind kind = requireMediaCanvas(ownerId, projectId, canvasItemId).kind();
        ArtifactRepository.VersionTarget target = artifacts
                .findVersionTarget(projectId, imageVersionId)
                .orElseThrow(() -> invalid(ApiMessage.of("api.media-draft-service.a-wired-version-of-the-image-does-not-exist-for")));
        if (target.kind() != Artifact.Kind.IMAGE && target.kind() != Artifact.Kind.AUDIO && target.kind() != Artifact.Kind.VIDEO
                || artifactService.get(ownerId, projectId, target.artifactId())
                        .artifact().archivedAt() != null) {
            throw invalid(ApiMessage.of("api.media-draft-service.linked-sources-must-be-exact-versions-of-unarchived-images"));
        }
        MediaDraft before = get(ownerId, projectId, canvasItemId);
        if (before.version() != expectedVersion) {
            throw draftConflict(ApiMessage.of("api.media-draft-service.the-target-draft-changed-before-the-connection-was-established"));
        }
        ObjectNode parameters = connectionParameters(kind, before, target.kind(), imageVersionId, slotKey);
        boolean workflow = parameters != null;
        List<MediaDraft.MediaInput> inputs = new ArrayList<>(before.mediaInputs());
        int existingIndex = java.util.stream.IntStream.range(0, inputs.size())
                .filter(index -> inputs.get(index).versionId().equals(imageVersionId))
                .findFirst().orElse(-1);
        if (existingIndex >= 0) {
            MediaDraft.MediaInput existing = inputs.get(existingIndex);
            List<MediaDraft.InputSource> sources = new ArrayList<>(existing.sources().stream()
                    .filter(source -> !replaceManualSource || source.type() != MediaDraft.SourceType.MANUAL)
                    .toList());
            if (sources.stream().noneMatch(source -> connectionId.equals(source.connectionId()))) {
                sources.add(new MediaDraft.InputSource(UUID.randomUUID(),
                        MediaDraft.SourceType.CONNECTION, connectionId));
            }
            inputs.set(existingIndex, new MediaDraft.MediaInput(existing.versionId(),
                    existing.artifactId(), existing.role(), existing.order(), existing.color(),
                    List.copyOf(sources)));
        } else {
            if (inputs.size() >= MAX_MEDIA_INPUTS) {
                throw invalid(ApiMessage.of("api.media-draft-service.image-input-has-reached-the-current-card-limit"));
            }
            if (!workflow && target.kind() == Artifact.Kind.AUDIO && kind != Artifact.Kind.AUDIO && (kind != Artifact.Kind.VIDEO
                    || before.videoInputMode() == MediaDraft.VideoInputMode.START_END))
                throw invalid(ApiMessage.of("api.media-draft-service.audio-can-only-be-connected-to-video-omni-reference-mode"));
            if (!workflow && target.kind() == Artifact.Kind.VIDEO && (kind != Artifact.Kind.VIDEO
                    || before.videoInputMode() == MediaDraft.VideoInputMode.START_END))
                throw invalid(ApiMessage.of("api.media-relay.video-general-mode-required"));
            MediaDraft.InputRole role = target.kind() == Artifact.Kind.VIDEO ? MediaDraft.InputRole.VIDEO_REFERENCE : target.kind() == Artifact.Kind.AUDIO
                    ? MediaDraft.InputRole.AUDIO_REFERENCE : workflow
                            ? MediaDraft.InputRole.REFERENCE : nextConnectionRole(kind, before, inputs);
            String color = INPUT_COLORS.stream()
                    .filter(candidate -> inputs.stream().noneMatch(input ->
                            input.color().equals(candidate)))
                    .findFirst().orElse(INPUT_COLORS.get(inputs.size() % INPUT_COLORS.size()));
            inputs.add(new MediaDraft.MediaInput(imageVersionId, target.artifactId(), role,
                    inputs.size(), color, List.of(new MediaDraft.InputSource(UUID.randomUUID(),
                            MediaDraft.SourceType.CONNECTION, connectionId))));
        }
        return replaceInputsWithinChange(before, inputs, before.mentions(),
                parameters == null ? before.parameters() : parameters);
    }

    private ObjectNode connectionParameters(Artifact.Kind kind, MediaDraft draft,
            Artifact.Kind sourceKind, UUID versionId, String slotKey) {
        var assignment = workflowSlotParameters(kind, draft.parameters(), draft.prompt(), draft.durationSeconds(),
                draft.capabilityId(), draft.mediaInputs().stream().map(MediaDraft.MediaInput::versionId).toList(),
                sourceKind, versionId, slotKey, false);
        return assignment == null ? null : assignment.parameters();
    }

    public record WorkflowSlotAssignment(ObjectNode parameters, UUID removedVersionId) {}

    /** Library transfer completion supplies its new project-local identity before saving the draft. */
    public WorkflowSlotAssignment assignWorkflowSlotParameters(UUID ownerId, UUID projectId, UUID canvasItemId,
            JsonNode parameters, String prompt, Integer durationSeconds, UUID capabilityId,
            List<SaveMediaInput> inputs, Artifact.Kind sourceKind, UUID versionId, String slotKey) {
        Artifact.Kind kind = requireMediaCanvas(ownerId, projectId, canvasItemId).kind();
        return workflowSlotParameters(kind, parameters == null ? mapper.createObjectNode() : parameters,
                prompt, durationSeconds, capabilityId,
                inputs == null ? List.of() : inputs.stream().map(SaveMediaInput::versionId).toList(),
                sourceKind, versionId, slotKey, true);
    }

    private WorkflowSlotAssignment workflowSlotParameters(Artifact.Kind kind, JsonNode draftParameters,
            String prompt, Integer durationSeconds, UUID selectedCapabilityId, List<UUID> positionalVersions,
            Artifact.Kind sourceKind, UUID versionId, String slotKey, boolean replaceSelectedSlot) {
        UUID capabilityId = selectedCapabilityId == null
                ? capabilities.defaultCapabilityId(Task.Kind.valueOf(kind.name() + "_GENERATION"))
                : selectedCapabilityId;
        var binding = capabilityId == null ? null : capabilities.forDraft(capabilityId,
                Task.Kind.valueOf(kind.name() + "_GENERATION"));
        var definition = binding == null ? null : capabilities.runningHubDefinition(binding);
        var comfy = binding == null ? null : capabilities.comfyWorkflowDefinition(binding);
        if (definition == null && comfy == null) {
            if (slotKey != null) throw invalid(ApiMessage.of("api.media-draft-service.workflow-slot-unavailable"));
            return null;
        }
        List<RunningHubDefinition.Field> fields = definition == null ? comfy.inputs() : definition.fields();
        if (!draftParameters.isObject()) throw invalid(ApiMessage.of("api.media-draft-service.media-parameters-must-be-objects"));
        ObjectNode parameters = (ObjectNode) draftParameters.deepCopy();
        ObjectNode values = comfy == null ? parameters.has(RunningHubDefinition.VALUES_PROPERTY)
                ? (ObjectNode) parameters.path(RunningHubDefinition.VALUES_PROPERTY).deepCopy() : mapper.createObjectNode()
                : comfy.values(mapper, parameters, prompt, durationSeconds, positionalVersions, false);
        ObjectNode effective = RunningHubDefinition.inputValues(mapper, fields, values,
                prompt, durationSeconds, false);
        List<RunningHubDefinition.Field> candidates = fields.stream().filter(field -> field.media()
                && field.type().name().equals(sourceKind.name()) && activeSlot(fields, effective, field)
                && (slotKey == null || field.key().equals(slotKey))
                && (replaceSelectedSlot && slotKey != null || !values.hasNonNull(field.key()) || values.path(field.key()).asText().isBlank()
                        || values.path(field.key()).asText().equals(versionId.toString()))).toList();
        if (candidates.isEmpty()) throw invalid(ApiMessage.of("api.media-draft-service.workflow-slot-unavailable"));
        if (slotKey == null && candidates.stream().anyMatch(field ->
                versionId.toString().equals(values.path(field.key()).asText()))) {
            // An already assigned manual/proposal version only gains another source; its slots stay intact.
            parameters.set(RunningHubDefinition.VALUES_PROPERTY, values);
            return new WorkflowSlotAssignment(parameters, null);
        }
        // A canvas drag follows published slot order; occupied fields are never overwritten.
        String key = candidates.getFirst().key();
        String previous = values.path(key).asText("");
        values.put(key, versionId.toString());
        parameters.set(RunningHubDefinition.VALUES_PROPERTY, values);
        UUID removedVersion = previous.isBlank() || fields.stream().anyMatch(field -> field.media()
                && previous.equals(values.path(field.key()).asText())) ? null : UUID.fromString(previous);
        return new WorkflowSlotAssignment(parameters, removedVersion);
    }

    /** Conditional slots follow the same effective scalar defaults that the editor displays. */
    private boolean activeSlot(List<RunningHubDefinition.Field> fields, JsonNode effective,
            RunningHubDefinition.Field slot) {
        if (slot.enabledWhen() == null) return true;
        String key = slot.enabledWhen().field();
        JsonNode value = effective.get(key);
        if (value == null) value = fields.stream().filter(field -> field.key().equals(key))
                .findFirst().map(RunningHubDefinition.Field::defaultValue).orElse(null);
        return RunningHubDefinition.scalarEquals(value, slot.enabledWhen().value());
    }

    /** Removes only one connection reason; manual or other connection sources keep the input. */
    public MediaDraft removeConnectionInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID connectionId) {
        MediaDraft before = get(ownerId, projectId, canvasItemId);
        if (before.version() != expectedVersion) {
            throw draftConflict(ApiMessage.of("api.media-draft-service.the-target-draft-changed-before-disconnection"));
        }
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        Set<UUID> removedVersions = new HashSet<>();
        boolean found = false;
        for (MediaDraft.MediaInput input : before.mediaInputs()) {
            List<MediaDraft.InputSource> sources = input.sources().stream()
                    .filter(source -> !connectionId.equals(source.connectionId()))
                    .toList();
            if (sources.size() != input.sources().size()) found = true;
            if (sources.isEmpty()) {
                removedVersions.add(input.versionId());
            } else {
                inputs.add(new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                        input.role(), inputs.size(), input.color(), sources));
            }
        }
        if (!found) throw invalid(ApiMessage.of("api.media-draft-service.the-connection-does-not-have-a-corresponding-media-input-source"));
        List<MediaDraft.PromptMention> mentions = before.mentions().stream()
                .filter(mention -> !removedVersions.contains(mention.versionId()))
                .toList();
        return replaceInputsWithinChange(before, inputs, mentions);
    }

    /** Removes one exact-version input and every structured mention bound to it. */
    public MediaDraft removeMediaInputWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId, long expectedVersion, UUID imageVersionId) {
        MediaDraft before = get(ownerId, projectId, canvasItemId);
        if (before.version() != expectedVersion) {
            throw draftConflict(ApiMessage.of("api.media-draft-service.the-target-draft-changed-before-the-image-was-removed"));
        }
        if (before.mediaInputs().stream().noneMatch(input ->
                input.versionId().equals(imageVersionId))) {
            throw invalid(ApiMessage.of("api.canvas-connection-service.the-image-entry-does-not-exist-in-the-media-draft"));
        }
        List<MediaDraft.MediaInput> inputs = new ArrayList<>();
        for (MediaDraft.MediaInput input : before.mediaInputs()) {
            if (input.versionId().equals(imageVersionId)) continue;
            inputs.add(new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                    input.role(), inputs.size(), input.color(), input.sources()));
        }
        List<MediaDraft.PromptMention> mentions = before.mentions().stream()
                .filter(mention -> !mention.versionId().equals(imageVersionId))
                .toList();
        return replaceInputsWithinChange(before, inputs, mentions);
    }

    private MediaDraft replaceInputsWithinChange(MediaDraft before,
            List<MediaDraft.MediaInput> inputs, List<MediaDraft.PromptMention> mentions) {
        ObjectNode parameters = (ObjectNode) before.parameters().deepCopy();
        Set<String> removed = new HashSet<>();
        before.mediaInputs().stream().filter(old -> inputs.stream().noneMatch(input ->
                input.versionId().equals(old.versionId()))).forEach(old -> removed.add(old.versionId().toString()));
        if (parameters.path(RunningHubDefinition.VALUES_PROPERTY) instanceof ObjectNode values) {
            Set<String> mediaSlots = before.capabilityId() == null ? Set.of()
                    : capabilities.declaredDraftInputs(before.capabilityId()).stream()
                            .filter(RunningHubDefinition.Field::media).map(RunningHubDefinition.Field::key)
                            .collect(java.util.stream.Collectors.toSet());
            List<String> keys = values.propertyNames().stream().filter(key ->
                    mediaSlots.contains(key) && values.path(key).isTextual()
                            && removed.contains(values.path(key).asText())).toList();
            keys.forEach(values::remove);
        }
        return replaceInputsWithinChange(before, inputs, mentions, parameters);
    }

    private MediaDraft replaceInputsWithinChange(MediaDraft before,
            List<MediaDraft.MediaInput> inputs, List<MediaDraft.PromptMention> mentions, JsonNode parameters) {
        Instant now = clock.instant();
        String prompt = pruneRemovedMentions(before.prompt(), before.mentions(), mentions);
        MediaDraft.VideoInputMode mode = before.videoInputMode();
        if (mode != null && inputs.isEmpty()) mode = MediaDraft.VideoInputMode.TEXT;
        else if (mode == MediaDraft.VideoInputMode.TEXT && !inputs.isEmpty()) {
            mode = MediaDraft.VideoInputMode.GENERAL_REFERENCE;
        }
        MediaDraft update = new MediaDraft(before.projectId(), before.canvasItemId(),
                prompt, parameters, before.durationSeconds(),
                before.capabilityId(), before.styleId(), mode, List.copyOf(inputs),
                List.copyOf(mentions), before.displayMode(), before.version() + 1,
                before.createdAt(), now);
        if (!artifacts.updateMediaDraft(update, before.version())) {
            throw draftConflict(ApiMessage.of("api.media-draft-service.the-media-input-has-been-modified-by-another-operation"));
        }
        artifacts.replaceMediaInputs(update.projectId(), update.canvasItemId(), inputs, now);
        return update;
    }

    /** Keeps submitted prompt edits while pruning only the structured mentions whose input was removed. */
    public String pruneRemovedMentions(String prompt,
            List<MediaDraft.PromptMention> beforeMentions,
            List<MediaDraft.PromptMention> remainingMentions) {
        StringBuilder result = new StringBuilder(prompt.length());
        int mentionIndex = 0;
        List<MediaDraft.PromptMention> unmatched = new ArrayList<>(remainingMentions);
        for (int index = 0; index < prompt.length(); index++) {
            char character = prompt.charAt(index);
            if (character != MENTION_MARKER) {
                result.append(character);
                continue;
            }
            MediaDraft.PromptMention mention = mentionIndex < beforeMentions.size()
                    ? beforeMentions.get(mentionIndex) : null;
            mentionIndex++;
            if (mention != null && unmatched.remove(mention)) result.append(MENTION_MARKER);
        }
        return result.toString();
    }

    private MediaDraft.InputRole nextConnectionRole(Artifact.Kind kind, MediaDraft draft,
            List<MediaDraft.MediaInput> inputs) {
        if (kind == Artifact.Kind.IMAGE || kind == Artifact.Kind.AUDIO
                || draft.videoInputMode() == MediaDraft.VideoInputMode.GENERAL_REFERENCE) {
            return MediaDraft.InputRole.REFERENCE;
        }
        if (draft.videoInputMode() == MediaDraft.VideoInputMode.START_END) {
            if (inputs.stream().noneMatch(input -> input.role()
                    == MediaDraft.InputRole.START_FRAME)) {
                return MediaDraft.InputRole.START_FRAME;
            }
            if (inputs.stream().noneMatch(input -> input.role()
                    == MediaDraft.InputRole.END_FRAME)) {
                return MediaDraft.InputRole.END_FRAME;
            }
            throw invalid(ApiMessage.of("api.media-draft-service.the-first-and-last-frame-mode-already-contains-the-first"));
        }
        if (draft.videoInputMode() == MediaDraft.VideoInputMode.TEXT && inputs.isEmpty()) {
            return MediaDraft.InputRole.REFERENCE;
        }
        throw invalid(ApiMessage.of("api.media-draft-service.the-picture-input-connection-cannot-be-established-in-the-current"));
    }

    private Artifact requireMediaCanvas(UUID ownerId, UUID projectId, UUID canvasItemId) {
        CanvasItem item = canvasItems.requireArtifactItem(ownerId, projectId, canvasItemId);
        Artifact artifact = artifactService.get(ownerId, projectId, item.subjectId()).artifact();
        Artifact.Kind kind = artifact.kind();
        if (kind == Artifact.Kind.TEXT) {
            throw invalid(ApiMessage.of("api.media-draft-service.only-image-and-video-products-have-media-drafts"));
        }
        return artifact;
    }

    public static Artifact.Kind mediaKind(MediaDraft.InputRole role) {
        return switch (role) { case AUDIO_REFERENCE -> Artifact.Kind.AUDIO; case VIDEO_REFERENCE -> Artifact.Kind.VIDEO; default -> Artifact.Kind.IMAGE; };
    }

    /** Named slots and the deduplicated exact-version rows must agree on identity and media kind. */
    public static void validateSlots(RunningHubDefinition definition,
            JsonNode parameters, List<MediaDraft.MediaInput> inputs, boolean executing) {
        validateSlots(definition.fields(), parameters, inputs, executing);
    }

    public static void validateSlots(List<RunningHubDefinition.Field> fields,
            JsonNode parameters, List<MediaDraft.MediaInput> inputs, boolean executing) {
        JsonNode values = parameters.path(RunningHubDefinition.VALUES_PROPERTY);
        Set<UUID> used = new HashSet<>();
        for (var field : fields) if (field.media() && values.hasNonNull(field.key())) {
            UUID id = UUID.fromString(values.path(field.key()).asText());
            if (inputs.stream().noneMatch(input -> input.versionId().equals(id)
                    && mediaKind(input.role()).name().equals(field.type().name())))
                throw RunningHubDefinition.invalid(ApiMessage.of("api.media-draft-service.requires-a-matching-exact-asset-reference-in-this-project", field.label()));
            used.add(id);
        }
        if (executing && inputs.stream().anyMatch(input -> !used.contains(input.versionId())))
            throw RunningHubDefinition.invalid(ApiMessage.of("api.media-draft-service.there-is-material-that-has-not-yet-been-assigned-to"));
    }

    private void validateRole(Artifact.Kind kind, MediaDraft.VideoInputMode mode,
            MediaDraft.InputRole role, int order, int inputCount) {
        if (kind == Artifact.Kind.AUDIO) {
            if (role != MediaDraft.InputRole.REFERENCE && role != MediaDraft.InputRole.AUDIO_REFERENCE)
                throw invalid(ApiMessage.of("api.media-draft-service.audio-generation-only-accepts-image-or-audio-reference-characters"));
            return;
        }
        if (kind == Artifact.Kind.IMAGE) {
            if (role != MediaDraft.InputRole.REFERENCE) {
                throw invalid(ApiMessage.of("api.media-draft-service.image-generation-input-can-only-use-the-reference-role"));
            }
            return;
        }
        if (mode == MediaDraft.VideoInputMode.GENERAL_REFERENCE) {
            if (role != MediaDraft.InputRole.REFERENCE && role != MediaDraft.InputRole.AUDIO_REFERENCE
                    && role != MediaDraft.InputRole.VIDEO_REFERENCE) {
                throw invalid(ApiMessage.of("api.media-draft-service.universal-reference-mode-can-only-use-picture-or-audio-reference"));
            }
            return;
        }
        if (mode == MediaDraft.VideoInputMode.START_END) {
            boolean valid = order == 0 && role == MediaDraft.InputRole.START_FRAME
                    || order == 1 && role == MediaDraft.InputRole.END_FRAME
                    && inputCount == 2;
            if (!valid || inputCount > 2) {
                throw invalid(ApiMessage.of("api.media-draft-service.the-first-and-last-frame-modes-must-be-saved-in"));
            }
            return;
        }
        throw invalid(ApiMessage.of("api.media-draft-service.text-only-video-mode-cannot-save-image-input"));
    }

    /** Ordinary saves edit manual choices without silently dropping connection-backed inputs. */
    private List<MediaDraft.MediaInput> mergeInputs(List<MediaDraft.MediaInput> before,
            List<MediaDraft.MediaInput> requested) {
        List<MediaDraft.MediaInput> result = new ArrayList<>();
        for (MediaDraft.MediaInput requestedInput : requested) {
            MediaDraft.MediaInput existing = before.stream()
                    .filter(input -> input.versionId().equals(requestedInput.versionId()))
                    .findFirst().orElse(null);
            // Autosave edits the ordered projection, not its source set. In particular,
            // a connection-only input must not become manual and survive disconnection.
            List<MediaDraft.InputSource> sources = existing == null
                    ? requestedInput.sources() : existing.sources();
            result.add(new MediaDraft.MediaInput(requestedInput.versionId(),
                    requestedInput.artifactId(), requestedInput.role(), result.size(),
                    requestedInput.color(), List.copyOf(sources)));
        }
        for (MediaDraft.MediaInput existing : before) {
            if (requested.stream().anyMatch(input -> input.versionId().equals(
                    existing.versionId()))) continue;
            List<MediaDraft.InputSource> connectionSources = existing.sources().stream()
                    .filter(source -> source.type() == MediaDraft.SourceType.CONNECTION)
                    .toList();
            if (!connectionSources.isEmpty()) {
                result.add(new MediaDraft.MediaInput(existing.versionId(),
                        existing.artifactId(), existing.role(), result.size(), existing.color(),
                        connectionSources));
            }
        }
        return List.copyOf(result);
    }

    public record SaveMediaInput(UUID versionId, MediaDraft.InputRole role, String color) {}

    private static ApiProblemException draftConflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                ApiMessage.of("api.canvas-connection-service.draft-version-conflict"), detail, true);
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.media-draft-service.media-draft-is-invalid"), detail, false);
    }
}
