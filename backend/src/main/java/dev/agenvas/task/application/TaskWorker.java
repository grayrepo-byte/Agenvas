package dev.agenvas.task.application;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Runs claimed work outside database transactions and persists only short fenced outcomes. */
public class TaskWorker {

    private final TaskService tasks;

    public TaskWorker(TaskService tasks) {
        this.tasks = tasks;
    }

    /** Claims a batch, returns from the transaction, then invokes potentially slow handlers. */
    public int runOnce(String workerId, int limit, Handler handler) {
        List<Task> claimed = tasks.claimDue(workerId, limit);
        for (Task task : claimed) {
            boolean media = task.kind() == Task.Kind.IMAGE_GENERATION
                    || task.kind() == Task.Kind.VIDEO_GENERATION;
            if (media && beginOrFailStale(task, workerId, null) == null) {
                continue;
            }
            persistOutcome(task, workerId, media, handler.execute(task));
        }
        return claimed.size();
    }

    /** Supplies the committed request key to a Mock image submitter without claiming video work. */
    public int runImagesOnce(String workerId, int limit, MediaHandler handler) {
        return runClaimedImages(tasks.claimImagesDue(workerId, limit), workerId, handler, null);
    }

    /** ComfyUI uses the persisted one-slot claim instead of the Mock worker claim path. */
    public int runComfyImagesOnce(String workerId, MediaHandler handler) {
        return runClaimedImages(tasks.claimComfyImage(workerId), workerId, handler,
                handler.candidateOriginSha256());
    }

    private int runClaimedImages(List<Task> claimed, String workerId, MediaHandler handler,
            String candidateOriginSha256) {
        for (Task task : claimed) {
            String preflightFailure = handler.preflightFailure(task);
            if (preflightFailure != null) {
                tasks.fail(task, workerId, preflightFailure);
                continue;
            }
            UUID requestKey = beginOrFailStale(task, workerId, candidateOriginSha256);
            if (requestKey == null) {
                continue;
            }
            persistOutcome(task, workerId, true, handler.execute(task, requestKey));
        }
        return claimed.size();
    }

    /** Claims only video tasks and commits the same fenced submission checkpoint. */
    public int runVideosOnce(String workerId, int limit, MediaHandler handler) {
        return runClaimedVideos(tasks.claimVideosDue(workerId, limit), workerId, handler, null);
    }

    /** ComfyUI video submissions share the persisted one-job gate with images. */
    public int runComfyVideosOnce(String workerId, MediaHandler handler) {
        return runClaimedVideos(tasks.claimComfyVideo(workerId), workerId, handler,
                handler.candidateOriginSha256());
    }

    private int runClaimedVideos(List<Task> claimed, String workerId, MediaHandler handler,
            String candidateOriginSha256) {
        for (Task task : claimed) {
            String preflightFailure = handler.preflightFailure(task);
            if (preflightFailure != null) {
                tasks.fail(task, workerId, preflightFailure);
                continue;
            }
            UUID requestKey = beginOrFailStale(task, workerId, candidateOriginSha256);
            if (requestKey == null) {
                continue;
            }
            persistOutcome(task, workerId, true, handler.execute(task, requestKey));
        }
        return claimed.size();
    }

    /** Polls saved request ids without opening any path that can call beginSubmission again. */
    public int runProviderPollsOnce(String workerId, int limit, ProviderPollHandler handler) {
        return runClaimedProviderPolls(tasks.claimProviderPolls(workerId, limit),
                workerId, handler);
    }

    /** The image adapter cannot consume another media adapter's accepted request. */
    public int runComfyImagePollsOnce(String workerId, ProviderPollHandler handler) {
        return runClaimedProviderPolls(tasks.claimComfyImagePolls(workerId, 1),
                workerId, handler);
    }

    /** Polls only previously acknowledged video prompt IDs. */
    public int runComfyVideoPollsOnce(String workerId, ProviderPollHandler handler) {
        return runClaimedProviderPolls(tasks.claimComfyVideoPolls(workerId, 1),
                workerId, handler);
    }

    private int runClaimedProviderPolls(List<Task> claimed, String workerId,
            ProviderPollHandler handler) {
        for (Task task : claimed) {
            PollResult outcome;
            try {
                outcome = handler.query(task);
            } catch (RuntimeException failure) {
                tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
                continue;
            }
            switch (outcome) {
                case PollPending pending ->
                    tasks.deferProviderPoll(task, workerId, pending.nextActionAt());
                case PollGenerated generated ->
                    tasks.succeedWithArtifact(task, workerId, generated.content());
                case PollFailed failed -> tasks.fail(task, workerId, failed.errorCode());
                case PollBlocked blocked ->
                    tasks.blockProviderPoll(task, workerId, blocked.errorCode());
            }
        }
        return claimed.size();
    }

    /** A stale shot blocks the old plan without contacting the external Provider. */
    private UUID beginOrFailStale(Task task, String workerId, String candidateOriginSha256) {
        try {
            return tasks.beginSubmission(task, workerId, candidateOriginSha256);
        } catch (ApiProblemException problem) {
            if (!"TASK_INPUT_STALE".equals(problem.code())
                    && !"TASK_PROJECT_ARCHIVED".equals(problem.code())) {
                throw problem;
            }
            tasks.blockPreSubmission(task, workerId, problem.code());
            return null;
        }
    }

    /** Commits only a normalized result through the fenced task application service. */
    private void persistOutcome(Task task, String workerId, boolean media, Outcome outcome) {
        switch (outcome) {
                case Succeeded succeeded -> tasks.succeed(task, workerId, succeeded.output());
                case GeneratedArtifact generated ->
                    tasks.succeedWithArtifact(task, workerId, generated.content());
                case Failed failed -> {
                    if (media) {
                        tasks.rejectSubmission(task, workerId, failed.errorCode());
                    } else {
                        tasks.fail(task, workerId, failed.errorCode());
                    }
                }
                case WaitingProvider waiting -> tasks.waitForProvider(
                        task,
                        workerId,
                        waiting.providerRequestId(),
                        waiting.nextActionAt());
        }
    }

    /**
     * External or local work implementation, invoked with no ambient transaction. Media submissions
     * have a committed SUBMITTING checkpoint before this handler is called.
     */
    @FunctionalInterface
    public interface Handler {
        Outcome execute(Task task);
    }

    /** Media adapter receives the durable pre-submission request key. */
    @FunctionalInterface
    public interface MediaHandler {
        /** Only a provider that sends the saved key as its request ID supplies this identity. */
        default String candidateOriginSha256() {
            return null;
        }

        /** Rejects a stale local configuration before any external submission checkpoint. */
        default String preflightFailure(Task task) {
            return null;
        }

        Outcome execute(Task task, UUID requestKey);
    }

    /** Query only the original saved providerRequestId; implementation cannot resubmit here. */
    @FunctionalInterface
    public interface ProviderPollHandler {
        PollResult query(Task task);
    }

    /** A bounded query may remain pending, return a verified file, or confirm failure. */
    public sealed interface PollResult permits PollPending, PollGenerated, PollFailed,
            PollBlocked {}

    /** Schedule a future status query without retaining a worker lease. */
    public record PollPending(Instant nextActionAt) implements PollResult {}

    /** Archive a completed result via the original Task's pinned Artifact target. */
    public record PollGenerated(JsonNode content) implements PollResult {}

    /** Only a confirmed terminal execution error becomes a Task failure. */
    public record PollFailed(String errorCode) implements PollResult {}

    /** Preserve the accepted request for manual recovery after unsafe configuration drift. */
    public record PollBlocked(String errorCode) implements PollResult {}

    /** One normalized handler outcome. */
    public sealed interface Outcome permits Succeeded, Failed, WaitingProvider,
            GeneratedArtifact {}

    /** Confirmed successful local outcome. */
    public record Succeeded(JsonNode output) implements Outcome {}

    /** Synchronous Mock or Provider result to archive through pinned Artifact CAS. */
    public record GeneratedArtifact(JsonNode content) implements Outcome {}

    /** Confirmed Provider rejection for media, or confirmed local failure for other work. */
    public record Failed(String errorCode) implements Outcome {}

    /** Accepted external work that should be polled later without occupying a worker. */
    public record WaitingProvider(String providerRequestId, Instant nextActionAt)
            implements Outcome {}
}
