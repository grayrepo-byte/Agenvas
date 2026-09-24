package dev.agenvas.llm.application;

import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ShotKeyframeSelection;
import dev.agenvas.plan.application.ShotKeyframeSelectionRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds an authenticated, durable approval and media-result checkpoint to the next model prompt. */
@Service
public class PlanResumeContextService {

    private static final int MAX_RESULT_CHARS = 40_000;

    private final ExecutionPlanService plans;
    private final TaskService tasks;
    private final ShotKeyframeSelectionRepository keyframes;

    public PlanResumeContextService(ExecutionPlanService plans, TaskService tasks,
            ShotKeyframeSelectionRepository keyframes) {
        this.plans = plans;
        this.tasks = tasks;
        this.keyframes = keyframes;
    }

    /** Never infers approval from model text; reads the exact persisted user decision. */
    @Transactional(readOnly = true)
    public List<Message> append(UUID ownerId, UUID projectId, UUID runId,
            Task resumeTask, List<Message> history) {
        if (resumeTask == null || !resumeTask.projectId().equals(projectId)
                || !resumeTask.runId().equals(runId) || history == null) {
            throw new IllegalArgumentException("Resume Task does not match the Run");
        }
        UUID planId;
        try {
            planId = UUID.fromString(resumeTask.input().path("resumePlanId").asText());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Resume Task lacks a valid plan ID", failure);
        }
        ExecutionPlan plan = plans.get(ownerId, projectId, planId);
        if (!plan.runId().equals(runId)) {
            throw new IllegalStateException("Resume plan belongs to another Run");
        }
        String decision = resumeTask.input().path("resumeDecision").asText();
        StringBuilder summary = new StringBuilder("User decision for plan ")
                .append(plan.id()).append(" (stage ").append(plan.stage()).append("):\n");
        if ("REJECTED".equals(decision) && plan.status() == ExecutionPlan.Status.REJECTED) {
            summary.append("REJECTED. No media tasks were authorized. Re-plan only if useful.");
        } else if ("APPROVED".equals(decision)
                && plan.status() == ExecutionPlan.Status.APPROVED) {
            summary.append("APPROVED by the authenticated user. Persisted media results:\n");
            List<Task> results = tasks.listByRun(ownerId, projectId, runId).stream()
                    .filter(task -> plan.id().equals(task.planId())).toList();
            if (results.size() != plan.steps().size()
                    || results.stream().anyMatch(task -> task.status() != Task.Status.SUCCEEDED
                            || task.output() == null)) {
                throw new IllegalStateException("Approved media results are not complete");
            }
            for (Task result : results) {
                summary.append("stepKey=").append(result.stepKey())
                        .append(" taskId=").append(result.id())
                        .append(" kind=").append(result.kind())
                        .append(" output=").append(result.output()).append('\n');
                if (plan.stage() == ExecutionPlan.Stage.VIDEO) {
                    summary.append("shotArtifactId=")
                            .append(result.input().path("shotArtifactId").asText())
                            .append(" shotVersionId=")
                            .append(result.input().path("shotVersionId").asText())
                            .append(" videoArtifactId=")
                            .append(result.output().path("artifactId").asText())
                            .append(" videoVersionId=")
                            .append(result.output().path("artifactVersionId").asText())
                            .append('\n');
                }
                if (summary.length() > MAX_RESULT_CHARS) {
                    throw new IllegalStateException("Media result context exceeds the limit");
                }
            }
            if (plan.stage() == ExecutionPlan.Stage.IMAGE) {
                summary.append("Authenticated human keyframe choices (exact versions):\n");
                for (Task result : results) {
                    UUID shotId = UUID.fromString(result.input().path("shotArtifactId").asText());
                    ShotKeyframeSelection choice = keyframes.find(projectId, shotId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Image plan resumed without a human keyframe choice"));
                    if (!choice.sourceTaskId().equals(result.id())
                            || !choice.shotVersionId().toString().equals(
                                    result.input().path("shotVersionId").asText())
                            || !choice.imageArtifactId().toString().equals(
                                    result.output().path("artifactId").asText())
                            || !choice.imageVersionId().toString().equals(
                                    result.output().path("artifactVersionId").asText())) {
                        throw new IllegalStateException("Keyframe choice no longer matches the result");
                    }
                    summary.append("shotArtifactId=").append(shotId)
                            .append(" shotVersionId=").append(choice.shotVersionId())
                            .append(" imageArtifactId=").append(choice.imageArtifactId())
                            .append(" imageVersionId=").append(choice.imageVersionId())
                            .append('\n');
                }
            }
        } else {
            throw new IllegalStateException("Resume Task conflicts with the saved plan decision");
        }
        List<Message> resumed = new ArrayList<>(history);
        resumed.add(new UserMessage(summary.toString()));
        return List.copyOf(resumed);
    }
}
