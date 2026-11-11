package dev.agenvas.subtitle.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.subtitle.domain.SubtitleFiles;
import dev.agenvas.subtitle.domain.SubtitleTrack;
import dev.agenvas.subtitle.infrastructure.SubtitleRepository;
import dev.agenvas.task.application.SubtitleTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Accepts explicit local recognition and applies its result only to the exact unchanged draft. */
@Service
public class SubtitleRecognitionService {
    private final SubtitleService subtitles;
    private final SubtitleRepository drafts;
    private final SubtitleTaskService jobs;
    private final TaskService tasks;
    private final WhisperCppTranscriber recognizer;
    private final AssetService assets;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final dev.agenvas.subtitle.runtime.ManagedAsrService runtime;
    public SubtitleRecognitionService(SubtitleService subtitles, SubtitleRepository drafts, SubtitleTaskService jobs, TaskService tasks,
            WhisperCppTranscriber recognizer, AssetService assets, ProjectService projects, ProjectEventService events, ObjectMapper mapper, Clock clock,
            dev.agenvas.subtitle.runtime.ManagedAsrService runtime) {
        this.subtitles = subtitles; this.drafts = drafts; this.jobs = jobs; this.tasks = tasks; this.recognizer = recognizer;
        this.assets = assets; this.projects = projects; this.events = events; this.mapper = mapper; this.clock = clock;
        this.runtime = runtime;
    }
    public JsonNode capabilities(UUID owner, UUID project) {
        projects.get(owner, project);
        return mapper.createObjectNode().put("availability", runtime.get().readiness().name())
                .put("maxSourceDurationMs", LocalAsrProperties.MAX_SOURCE_DURATION_MS).put("maxCues", SubtitleTrack.MAX_CUES)
                .put("maxFileBytes", SubtitleFiles.MAX_FILE_BYTES);
    }
    public Task start(UUID owner, UUID project, UUID id, JsonNode raw, String key) {
        SubtitleService.fields(raw, Set.of("expectedVersion", "language"));
        long expected = SubtitleService.expected(raw);
        String language = raw.path("language").asText();
        if (!language.matches("auto|[a-z]{2,3}")) throw SubtitleService.invalid();
        var request = mapper.createObjectNode().put("subtitleId", id.toString()).put("expectedVersion", expected).put("language", language);
        return events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var prior = jobs.prior(owner, project, key, request);
            if (prior.isPresent()) return ProjectEventService.Change.unchanged(prior.get());
            var draft = subtitles.get(owner, project, id);
            if (draft.version() != expected) throw SubtitleService.conflict();
            if (draft.taskId() != null) {
                var current = tasks.get(owner, project, draft.taskId());
                if (Set.of(Task.Status.READY, Task.Status.RUNNING).contains(current.status()))
                    throw SubtitleService.problem(HttpStatus.CONFLICT, "LOCAL_ASR_BUSY", "busy");
            }
            runtime.requireReady();
            var availability = recognizer.availability();
            if (availability != WhisperCppTranscriber.Availability.AVAILABLE)
                throw SubtitleService.problem(HttpStatus.SERVICE_UNAVAILABLE,
                        availability == WhisperCppTranscriber.Availability.NOT_CONFIGURED ? "LOCAL_ASR_NOT_CONFIGURED" : "LOCAL_ASR_UNAVAILABLE",
                        availability == WhisperCppTranscriber.Availability.NOT_CONFIGURED ? "not-configured" : "unavailable");
            var source = subtitles.source(owner, project, draft.track().sourceVersionId());
            var input = mapper.createObjectNode().put("schemaVersion", 1).put("subtitleId", id.toString())
                    .put("sourceVersionId", draft.track().sourceVersionId().toString()).put("sourceAssetId", source.id().toString())
                    .put("sourceDurationMs", source.durationMs()).put("expectedSubtitleVersion", expected + 1).put("language", language);
            input.set("recognitionRequest", request);
            var task = jobs.createWithinChange(owner, project, key, input);
            if (!drafts.attachTask(project, id, expected, task.id(), clock.instant())) throw SubtitleService.conflict();
            events.append(owner, project, subtitles.event(drafts.find(project, id).orElseThrow()));
            return ProjectEventService.Change.unchanged(task);
        }).value();
    }
    public Task cancel(UUID owner, UUID project, UUID id, UUID taskId) {
        var draft = subtitles.get(owner, project, id);
        if (!taskId.equals(draft.taskId())) throw SubtitleService.conflict();
        return jobs.cancel(owner, project, taskId);
    }
    public int processNext(String workerId) {
        var claimed = tasks.claimSubtitleTranscriptions(workerId, 1);
        for (Task lease : claimed) process(lease);
        return claimed.size();
    }
    private void process(Task lease) {
        UUID owner = tasks.ownerForWorker(lease);
        try {
            runtime.requireReady();
            UUID sourceId = UUID.fromString(lease.input().path("sourceVersionId").asText());
            var source = subtitles.source(owner, lease.projectId(), sourceId);
            if (!source.id().toString().equals(lease.input().path("sourceAssetId").asText())
                    || source.durationMs() != lease.input().path("sourceDurationMs").asInt()) throw new LocalAsrException(LocalAsrException.Code.INVALID_SOURCE);
            var file = assets.get(owner, lease.projectId(), source.id());
            var result = recognizer.transcribe(new WhisperCppTranscriber.Source(sourceId, file.path(), source.durationMs()),
                    lease.input().path("language").asText(),
                    () -> tasks.get(owner, lease.projectId(), lease.id()).cancelRequested() || runtime.shouldStopRecognition(),
                    () -> tasks.heartbeat(lease.id(), lease.leaseOwner(), lease.leaseEpoch()), runtime::requireReady);
            complete(lease, result);
        } catch (CancellationException canceled) {
            if (!Thread.currentThread().isInterrupted()) failIfActive(lease, owner, "LOCAL_ASR_CANCELED");
        } catch (LocalAsrException failure) {
            failIfActive(lease, owner, failure.getMessage());
        } catch (ApiProblemException failure) {
            // Lease/cancel/DB heartbeat errors must not be mislabeled as recognition failures.
            if (!"TASK_LEASE_LOST".equals(failure.code())) failIfActive(lease, owner, failure.code().startsWith("LOCAL_ASR_") ? failure.code() : "LOCAL_ASR_INVALID_SOURCE");
        }
    }
    private void failIfActive(Task lease, UUID owner, String code) {
        var current = tasks.get(owner, lease.projectId(), lease.id());
        if (current.status() == Task.Status.RUNNING) tasks.fail(lease, lease.leaseOwner(), code);
    }
    /** One project transaction fences the Task and draft together; stale workers cannot change corrections. */
    public void complete(Task lease, SubtitleTrack result) {
        UUID owner = tasks.ownerForWorker(lease);
        if (lease.kind() != Task.Kind.SUBTITLE_TRANSCRIPTION || !result.sourceVersionId().toString().equals(lease.input().path("sourceVersionId").asText())
                || result.sourceDurationMs() != lease.input().path("sourceDurationMs").asInt()
                || !result.language().equals(lease.input().path("language").asText())) throw SubtitleService.invalid();
        events.recordChange(owner, lease.projectId(), () -> {
            var current = tasks.get(owner, lease.projectId(), lease.id());
            if (current.cancelRequested()) { tasks.fail(lease, lease.leaseOwner(), "LOCAL_ASR_CANCELED"); return ProjectEventService.Change.unchanged(null); }
            UUID id = UUID.fromString(lease.input().path("subtitleId").asText());
            boolean applied = drafts.applyRecognition(lease.projectId(), id, lease.input().path("expectedSubtitleVersion").asLong(), lease.id(), result, clock.instant());
            var output = mapper.createObjectNode().put("subtitleId", id.toString()).put("sourceVersionId", result.sourceVersionId().toString())
                    .put("sourceDurationMs", result.sourceDurationMs()).put("language", result.language()).put("draft", true).put("applied", applied);
            output.set("cues", mapper.valueToTree(result.cues()));
            tasks.succeed(lease, lease.leaseOwner(), output);
            if (applied) events.append(owner, lease.projectId(), subtitles.event(drafts.find(lease.projectId(), id).orElseThrow()));
            return ProjectEventService.Change.unchanged(null);
        });
    }
}
