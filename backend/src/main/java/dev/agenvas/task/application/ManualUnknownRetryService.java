package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.PlanDraftValidator;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.plan.application.PlanWorkflowPolicy;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit, auditable new attempt for an ambiguous external submission; never retries implicitly. */
@Service
public class ManualUnknownRetryService {

    public static final String RISK_ACKNOWLEDGEMENT = "ACCEPT_POSSIBLE_DUPLICATE_COST";

    private final TaskService tasks;
    private final TaskRepository repository;
    private final ExecutionPlanService plans;
    private final PlanDraftValidator validator;
    private final PlanProviderProperties provider;
    private final PlanWorkflowPolicy workflows;
    private final ProjectService projects;
    private final AgentRunService runs;
    private final ArtifactService artifacts;
    private final UsageService usage;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ManualUnknownRetryService(TaskService tasks, TaskRepository repository,
            ExecutionPlanService plans, PlanDraftValidator validator,
            PlanProviderProperties provider, PlanWorkflowPolicy workflows,
            ProjectService projects, AgentRunService runs, ArtifactService artifacts,
            UsageService usage, ProjectEventService events, ObjectMapper mapper, Clock clock) {
        this.tasks = tasks;
        this.repository = repository;
        this.plans = plans;
        this.validator = validator;
        this.provider = provider;
        this.workflows = workflows;
        this.projects = projects;
        this.runs = runs;
        this.artifacts = artifacts;
        this.usage = usage;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Creates one separately reserved Task and redirects only pending downstream work. */
    @Transactional
    public Task create(UUID ownerId, UUID projectId, UUID originalTaskId,
            long expectedTaskVersion, String acknowledgement, String idempotencyKey) {
        if (!RISK_ACKNOWLEDGEMENT.equals(acknowledgement)
                || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 120 || expectedTaskVersion < 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "确认信息无效", "必须明确接受可能重复收费，并提供版本与幂等键。", false);
        }
        return events.recordChange(ownerId, projectId, () -> {
            Task original = tasks.get(ownerId, projectId, originalTaskId);
            TaskRepository.ManualReplacement replay = repository.findManualReplacementByKey(
                    projectId, ownerId, idempotencyKey).orElse(null);
            if (replay != null) {
                if (!replay.originalTaskId().equals(originalTaskId)
                        || replay.originalTaskVersion() != expectedTaskVersion) {
                    throw conflict("相同幂等键已用于不同的任务或版本。");
                }
                return ProjectEventService.Change.unchanged(tasks.get(ownerId, projectId,
                        replay.replacementTaskId()));
            }
            if (repository.findManualReplacement(projectId, originalTaskId).isPresent()) {
                throw conflict("该 UNKNOWN 任务已有新尝试，请刷新任务列表。");
            }
            if (original.version() != expectedTaskVersion
                    || original.status() != Task.Status.UNKNOWN || original.cancelRequested()
                    || original.providerRequestId() != null || original.planId() == null
                    || (original.kind() != Task.Kind.IMAGE_GENERATION
                            && original.kind() != Task.Kind.VIDEO_GENERATION)) {
                throw conflict("原任务状态或版本已变化，请重新核对后确认。");
            }
            projects.requireActiveProject(ownerId, projectId);
            AgentRun run = runs.get(ownerId, projectId, original.runId());
            if (run.status() != AgentRun.Status.BLOCKED
                    && run.status() != AgentRun.Status.WAITING_TASKS) {
                throw conflict("Run 不再等待该媒体任务。");
            }
            ExecutionPlan plan = plans.get(ownerId, projectId, original.planId());
            if (plan.status() != ExecutionPlan.Status.APPROVED
                    || !plan.runId().equals(run.id())
                    || plan.steps().stream().noneMatch(step -> step.stepKey().equals(original.stepKey())
                            && step.kind() == original.kind()
                            && step.input().equals(original.input()))
                    || plan.providerConfigVersion() != provider.configVersion()
                    || !workflows.version(plan.stage()).equals(plan.workflowVersion())
                    || !validator.providerOriginMatches(plan)
                    || !validator.currentInputsMatch(ownerId, projectId, plan.inputSnapshot())) {
                throw conflict("计划、Provider 配置或输入已变化，不能沿用原批准。");
            }
            int limit = run.policySnapshot().path(original.kind() == Task.Kind.IMAGE_GENERATION
                    ? "maxImages" : "maxVideos").intValue();
            if (repository.countByRunAndKind(projectId, run.id(), original.kind()) >= limit) {
                throw conflict("新尝试会超过 Run 的媒体任务预算。");
            }
            TaskRepository.ArtifactTarget target = repository.findArtifactTarget(original.id())
                    .orElseThrow(() -> conflict("原任务缺少固定的输出目标。"));
            List<Task> dependents = repository.dependentTasks(projectId, original.id());
            if (dependents.stream().anyMatch(task -> task.status() != Task.Status.PENDING
                    || !task.runId().equals(run.id()))) {
                throw conflict("下游任务已经开始，不能安全改指向新尝试。");
            }
            List<UUID> dependencies = repository.dependencyIds(projectId, original.id());
            Task replacement;
            if (target.artifactId() != null) {
                Artifact current = artifacts.get(ownerId, projectId, target.artifactId()).artifact();
                if (current.archivedAt() != null || current.version() != target.expectedArtifactVersion()
                        || !current.currentVersionId().equals(target.expectedCurrentVersionId())) {
                    throw conflict("媒体目标版本已变化，不能沿用原批准。");
                }
                replacement = tasks.createMediaTask(ownerId, projectId, run.id(), plan.id(),
                        original.stepKey(), original.kind(), original.input(), original.providerId(),
                        original.attemptNo() + 1, dependencies, target.artifactId());
            } else {
                replacement = tasks.createMediaTaskForNewOutput(ownerId, projectId, run.id(),
                        plan.id(), original.stepKey(), original.kind(), original.input(),
                        original.providerId(), original.attemptNo() + 1, dependencies,
                        target.outputSlotKey());
            }
            usage.reserveMediaTask(ownerId, replacement,
                    plan.estimate().path("costSource").asText());
            repository.createManualReplacement(new TaskRepository.ManualReplacement(projectId,
                    originalTaskId, replacement.id(), ownerId, expectedTaskVersion,
                    idempotencyKey, clock.instant()));
            List<Task> rewired = repository.rewirePendingDependents(projectId, original.id(),
                    replacement.id(), clock.instant());
            if (rewired.size() != dependents.size()) {
                throw new IllegalStateException("Not every pending dependent was rewired");
            }
            for (Task dependent : rewired) {
                events.append(ownerId, projectId, statusEvent(dependent, null));
            }
            events.append(ownerId, projectId, statusEvent(original, replacement.id()));
            tasks.promoteAfterKeyframeSelection(projectId, run.id());
            if (run.status() == AgentRun.Status.BLOCKED && noOtherBlockers(ownerId, projectId,
                    run.id(), original.id())) {
                AgentRun resumed = runs.transition(ownerId, projectId, run.id(), run.version(),
                        AgentRun.Status.RUNNING);
                runs.transition(ownerId, projectId, run.id(), resumed.version(),
                        AgentRun.Status.WAITING_TASKS);
            }
            return ProjectEventService.Change.unchanged(replacement);
        }).value();
    }

    /** Superseded UNKNOWN rows remain audit history, not active Run blockers. */
    private boolean noOtherBlockers(UUID ownerId, UUID projectId, UUID runId, UUID originalId) {
        return tasks.listByRun(ownerId, projectId, runId).stream()
                .filter(task -> !task.id().equals(originalId))
                .noneMatch(task -> (task.status() == Task.Status.UNKNOWN
                        && repository.findManualReplacement(projectId, task.id()).isEmpty())
                        || task.status() == Task.Status.BLOCKED
                        || task.status() == Task.Status.FAILED
                        || task.status() == Task.Status.CANCELED);
    }

    /** Existing task event type causes snapshot clients to refresh both original and successor. */
    private ProjectEventService.EventDraft statusEvent(Task task, UUID replacementId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("taskId", task.id().toString());
        payload.put("status", task.status().name());
        payload.put("cancelRequested", task.cancelRequested());
        payload.put("possibleExternalCost", task.status() == Task.Status.UNKNOWN);
        if (replacementId != null) payload.put("replacementTaskId", replacementId.toString());
        return new ProjectEventService.EventDraft("task.status.changed", 1,
                task.id(), task.version(), payload);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "UNKNOWN_RETRY_CONFLICT",
                "不能创建新尝试", detail, false);
    }
}
