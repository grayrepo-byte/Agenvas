package dev.agenvas.subtitle.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.subtitle.domain.*;
import dev.agenvas.subtitle.infrastructure.SubtitleRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns authorization and short editing transactions; recognition never runs inside these transactions. */
@Service
public class SubtitleService {
    private final SubtitleRepository drafts;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    public SubtitleService(SubtitleRepository drafts, ArtifactService artifacts, AssetService assets, ProjectService projects,
            ProjectEventService events, ObjectMapper mapper, Clock clock) {
        this.drafts = drafts; this.artifacts = artifacts; this.assets = assets; this.projects = projects;
        this.events = events; this.mapper = mapper; this.clock = clock;
    }
    public SubtitleDraft create(UUID owner, UUID project, JsonNode request) {
        fields(request, Set.of("sourceVersionId"));
        UUID source = uuid(request, "sourceVersionId");
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var prior = drafts.list(project, List.of(source));
            if (!prior.isEmpty()) return ProjectEventService.Change.unchanged(prior.getFirst());
            var media = source(owner, project, source);
            var now = clock.instant();
            var draft = new SubtitleDraft(UUID.randomUUID(), project, new SubtitleTrack(source, media.durationMs(), "auto", List.of()), 0, null, now);
            drafts.create(draft, now);
            return ProjectEventService.Change.changed(draft, event(draft));
        }).value();
    }
    public SubtitleDraft get(UUID owner, UUID project, UUID id) {
        projects.get(owner, project);
        return drafts.find(project, id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "SUBTITLE_NOT_FOUND", "not-found"));
    }
    public List<SubtitleDraft> list(UUID owner, UUID project, List<UUID> sources) {
        projects.get(owner, project);
        if (sources == null || sources.isEmpty() || sources.size() > 100) throw invalid();
        try { return drafts.list(project, sources); }
        catch (SubtitleRepository.BatchTooLargeException tooLarge) {
            throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "SUBTITLE_BATCH_TOO_LARGE", "budget");
        }
    }
    public SubtitleDraft save(UUID owner, UUID project, UUID id, JsonNode request) {
        fields(request, Set.of("expectedVersion", "cues"));
        return update(owner, project, id, expected(request), current -> current.track().withCues(cues(request.path("cues"))));
    }
    public SubtitleDraft importFile(UUID owner, UUID project, UUID id, JsonNode request) {
        fields(request, Set.of("expectedVersion", "format", "contents"));
        if (!request.path("contents").isTextual()) throw invalid();
        return update(owner, project, id, expected(request), current -> SubtitleFiles.decode(request.path("contents").asText(),
                format(request.path("format").asText()), current.track().sourceVersionId(), current.track().sourceDurationMs(), current.track().language()));
    }
    public String exportFile(UUID owner, UUID project, UUID id, SubtitleFiles.Format format) {
        try { return SubtitleFiles.encode(get(owner, project, id).track(), format); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private SubtitleDraft update(UUID owner, UUID project, UUID id, long expected, java.util.function.Function<SubtitleDraft, SubtitleTrack> change) {
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var current = get(owner, project, id);
            SubtitleTrack next;
            try { next = change.apply(current); } catch (IllegalArgumentException invalid) { throw invalid(); }
            if (current.version() != expected || !drafts.save(project, id, expected, next, clock.instant())) throw conflict();
            var saved = drafts.find(project, id).orElseThrow();
            return ProjectEventService.Change.changed(saved, event(saved));
        }).value();
    }
    /** Resolves only the explicitly selected version; current node/default selections are irrelevant. */
    public dev.agenvas.asset.domain.Asset source(UUID owner, UUID project, UUID source) {
        var version = artifacts.requireVersionById(owner, project, source);
        var artifact = artifacts.get(owner, project, version.artifactId()).artifact();
        if (artifact.archivedAt() != null || !Set.of(Artifact.Kind.AUDIO, Artifact.Kind.VIDEO).contains(artifact.kind())) throw invalid();
        var asset = assets.metadata(owner, project, UUID.fromString(version.content().path("assetId").asText()));
        if (asset.durationMs() == null || asset.durationMs() < 1 || asset.durationMs() > LocalAsrProperties.MAX_SOURCE_DURATION_MS
                || !asset.mediaKind().name().equals(artifact.kind().name())) throw invalid();
        return asset;
    }
    ProjectEventService.EventDraft event(SubtitleDraft draft) {
        return new ProjectEventService.EventDraft("subtitle.draft.changed", 1, draft.id(), draft.version(), mapper.createObjectNode()
                .put("subtitleId", draft.id().toString()).put("sourceVersionId", draft.track().sourceVersionId().toString()));
    }
    public static List<SubtitleCue> cues(JsonNode array) {
        if (!array.isArray() || array.size() > SubtitleTrack.MAX_CUES) throw invalid();
        try {
            return array.valueStream().map(cue -> {
                fields(cue, Set.of("text", "startMs", "endMs"));
                if (!cue.path("text").isTextual() || !cue.path("startMs").isIntegralNumber() || !cue.path("startMs").canConvertToInt()
                        || !cue.path("endMs").isIntegralNumber() || !cue.path("endMs").canConvertToInt()) throw invalid();
                return new SubtitleCue(cue.path("text").asText(), cue.path("startMs").intValue(), cue.path("endMs").intValue());
            }).toList();
        } catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    public static void fields(JsonNode request, Set<String> fields) {
        if (request == null || !request.isObject() || request.propertyNames().stream().anyMatch(name -> !fields.contains(name))) throw invalid();
    }
    public static long expected(JsonNode request) {
        var value = request.path("expectedVersion");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() == Long.MAX_VALUE) throw invalid();
        return value.longValue();
    }
    public static UUID uuid(JsonNode request, String field) {
        try { return UUID.fromString(request.path(field).asText()); } catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    public static SubtitleFiles.Format format(String name) {
        try { return SubtitleFiles.Format.valueOf(name); } catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    public static ApiProblemException conflict() { return problem(HttpStatus.CONFLICT, "SUBTITLE_VERSION_CONFLICT", "conflict"); }
    public static ApiProblemException invalid() { return problem(HttpStatus.BAD_REQUEST, "SUBTITLE_INVALID", "invalid"); }
    public static ApiProblemException problem(HttpStatus status, String code, String message) {
        var text = ApiMessage.of("api.subtitle." + message);
        return new ApiProblemException(status, code, text, text, false);
    }
}
