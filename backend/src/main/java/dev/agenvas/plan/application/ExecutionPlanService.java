package dev.agenvas.plan.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Separates model proposals from authenticated, version-checked Task authorization. */
@Service
public class ExecutionPlanService {

    private final ProjectService projects;
    private final AgentRunRepository runRepository;
    private final AgentRunService runs;
    private final ExecutionPlanRepository plans;
    private final PlanDraftValidator validator;
    private final PlanProviderProperties provider;
    private final PlanWorkflowPolicy workflows;
    private final TaskRepository taskRepository;
    private final TaskService tasks;
    private final UsageService usage;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ExecutionPlanService(ProjectService projects, AgentRunRepository runRepository,
            AgentRunService runs, ExecutionPlanRepository plans, PlanDraftValidator validator,
            PlanProviderProperties provider, PlanWorkflowPolicy workflows,
            TaskRepository taskRepository, TaskService tasks,
            UsageService usage, ProjectEventService events, ObjectMapper mapper, Clock clock) {
        this.projects = projects;
        this.runRepository = runRepository;
        this.runs = runs;
        this.plans = plans;
        this.validator = validator;
        this.provider = provider;
        this.workflows = workflows;
        this.taskRepository = taskRepository;
        this.tasks = tasks;
        this.usage = usage;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Stores a model proposal and moves its Run to WAITING_APPROVAL without any media Task. */
    @Transactional
    public ExecutionPlan propose(TrustedToolContext context, JsonNode proposed) {
        return events.recordChange(context.ownerId(), context.projectId(), () ->
                ProjectEventService.Change.unchanged(proposeLocked(context, proposed))).value();
    }

    /** Runs after the project event row is locked, matching Artifact revision lock order. */
    private ExecutionPlan proposeLocked(TrustedToolContext context, JsonNode proposed) {
        projects.requireActiveProject(context.ownerId(), context.projectId());
        AgentRun run = lockedRun(context);
        if (run.status() != AgentRun.Status.RUNNING) {
            throw conflict("Only a running Agent may propose a new plan");
        }
        PlanDraftValidator.Draft draft = validator.validate(context, run, proposed,
                provider.configVersion());
        int revision = plans.nextRevision(context.projectId(), context.runId(), draft.stage());
        String inputHash = sha256(draft.inputSnapshot().toString());
        String planHash = sha256(draft.plan().toString() + "\n" + inputHash + "\n"
                + draft.providerConfigVersion() + "\n" + draft.workflowVersion() + "\n"
                + draft.estimate().toString());
        Instant now = clock.instant();
        ExecutionPlan plan = new ExecutionPlan(UUID.randomUUID(), context.projectId(),
                context.runId(), revision, draft.stage(), ExecutionPlan.Status.PENDING,
                draft.objective(), draft.plan(), draft.inputSnapshot(), inputHash,
                planHash, draft.providerConfigVersion(), draft.workflowVersion(),
                draft.estimate(), now, now, draft.steps());
        plans.create(plan);
        events.append(context.ownerId(), context.projectId(), planEvent("execution.plan.proposed", plan));
        runs.transition(context.ownerId(), context.projectId(), context.runId(),
                run.version(), AgentRun.Status.WAITING_APPROVAL);
        return plan;
    }

    /** Returns a nested proposal only after the requesting user owns its Run. */
    @Transactional(readOnly = true)
    public ExecutionPlan get(UUID ownerId, UUID projectId, UUID planId) {
        ExecutionPlan plan = plans.find(projectId, planId).orElseThrow(this::notFound);
        runs.get(ownerId, projectId, plan.runId());
        return plan;
    }

    /** Lists proposals for a Run after checking its owner and project scope. */
    @Transactional(readOnly = true)
    public List<ExecutionPlan> listByRun(UUID ownerId, UUID projectId, UUID runId) {
        runs.get(ownerId, projectId, runId);
        return plans.findIdsByRun(projectId, runId).stream()
                .map(id -> plans.find(projectId, id).orElseThrow(this::notFound))
                .toList();
    }

    /** Authenticated approval atomically reserves use, creates one Task per step and records events. */
    @Transactional
    public ApprovalResult approve(UUID ownerId, UUID projectId, UUID planId,
            String submittedPlanHash) {
        if (submittedPlanHash == null || !submittedPlanHash.matches("[0-9a-f]{64}")) {
            throw invalid("A valid planHash is required for approval");
        }
        ExecutionPlan preliminary = get(ownerId, projectId, planId);
        return events.recordChange(ownerId, projectId, () ->
                ProjectEventService.Change.unchanged(approveLocked(ownerId, projectId,
                        preliminary, submittedPlanHash))).value();
    }

    /** Serializes the final input read against Artifact revisions before reserving any work. */
    private ApprovalResult approveLocked(UUID ownerId, UUID projectId,
            ExecutionPlan preliminary, String submittedPlanHash) {
        AgentRun run = runRepository.findForUpdate(ownerId, projectId, preliminary.runId())
                .orElseThrow(this::notFound);
        ExecutionPlan plan = plans.findForUpdate(projectId, preliminary.id()).orElseThrow(this::notFound);
        if (!plan.planHash().equals(submittedPlanHash)) {
            throw conflict("Plan hash changed; reload the proposal before approval");
        }
        if (plan.status() == ExecutionPlan.Status.APPROVED) {
            UUID approvalId = plans.findApprovalId(projectId, preliminary.id()).orElseThrow();
            return new ApprovalResult(approvalId, plan, planTasks(ownerId, projectId, plan), true);
        }
        if (plan.status() != ExecutionPlan.Status.PENDING
                || run.status() != AgentRun.Status.WAITING_APPROVAL) {
            throw conflict("Plan or Run is no longer waiting for approval");
        }
        projects.requireActiveProject(ownerId, projectId);
        if (plan.providerConfigVersion() != provider.configVersion()
                || !workflows.version(plan.stage()).equals(plan.workflowVersion())
                || !validator.providerOriginMatches(plan)) {
            throw conflict("Provider configuration changed; propose a fresh plan");
        }
        if (!validator.currentInputsMatch(ownerId, projectId, plan.inputSnapshot())) {
            throw conflict("Plan inputs changed; propose a fresh plan");
        }
        int existingImages = Math.toIntExact(taskRepository.countByRunAndKind(projectId,
                run.id(), Task.Kind.IMAGE_GENERATION));
        int existingVideos = Math.toIntExact(taskRepository.countByRunAndKind(projectId,
                run.id(), Task.Kind.VIDEO_GENERATION));
        int additionalImages = plan.stage() == ExecutionPlan.Stage.IMAGE ? plan.steps().size() : 0;
        int additionalVideos = plan.stage() == ExecutionPlan.Stage.VIDEO ? plan.steps().size() : 0;
        if (existingImages + additionalImages > run.policySnapshot().path("maxImages").intValue()
                || existingVideos + additionalVideos
                        > run.policySnapshot().path("maxVideos").intValue()) {
            throw conflict("Run media budget would be exceeded");
        }
        ObjectNode reservation = mapper.createObjectNode();
        reservation.put("imageCount", additionalImages);
        reservation.put("videoCount", additionalVideos);
        reservation.put("videoSeconds", plan.estimate().path("videoSeconds").asText());
        reservation.put("costSource", plan.estimate().path("costSource").asText());
        UUID approvalId = UUID.randomUUID();
        Instant now = clock.instant();
        plans.insertApproval(approvalId, projectId, run.id(), plan.id(), ownerId,
                plan.planHash(), plan.inputSnapshotHash(), reservation, now);
        if (!plans.updateStatus(projectId, preliminary.id(), ExecutionPlan.Status.PENDING,
                ExecutionPlan.Status.APPROVED, now)) {
            throw conflict("Plan was approved or changed concurrently");
        }
        Map<String, UUID> taskIdsByStep = new HashMap<>();
        List<Task> created = new ArrayList<>();
        for (ExecutionPlan.Step step : plan.steps()) {
            List<UUID> dependencies = step.dependencyKeys().stream()
                    .map(key -> {
                        UUID taskId = taskIdsByStep.get(key);
                        if (taskId == null) {
                            throw new IllegalStateException("Validated DAG order was not preserved");
                        }
                        return taskId;
                    }).toList();
            Task task = tasks.createMediaTaskForNewOutput(ownerId, projectId, run.id(), plan.id(),
                    step.stepKey(), step.kind(), step.input(), null, 1,
                    dependencies, step.outputSlotKey());
            usage.reserveMediaTask(ownerId, task,
                    plan.estimate().path("costSource").asText());
            taskIdsByStep.put(step.stepKey(), task.id());
            created.add(task);
        }
        AgentRun advanced = createResumeTurn(ownerId, projectId, run, plan,
                created, "APPROVED");
        events.append(ownerId, projectId, planEvent("execution.plan.approved", plan));
        runs.transition(ownerId, projectId, run.id(), advanced.version(),
                AgentRun.Status.WAITING_TASKS);
        return new ApprovalResult(approvalId,
                plans.find(projectId, preliminary.id()).orElseThrow(), List.copyOf(created), false);
    }

    /** A rejected proposal never creates Tasks and allows the same Run to re-plan. */
    @Transactional
    public ExecutionPlan reject(UUID ownerId, UUID projectId, UUID planId) {
        ExecutionPlan preliminary = get(ownerId, projectId, planId);
        return events.recordChange(ownerId, projectId, () ->
                ProjectEventService.Change.unchanged(rejectLocked(ownerId, projectId,
                        preliminary))).value();
    }

    /** Rejects under the same project-before-Run lock order as cancellation and content edits. */
    private ExecutionPlan rejectLocked(UUID ownerId, UUID projectId, ExecutionPlan preliminary) {
        AgentRun run = runRepository.findForUpdate(ownerId, projectId, preliminary.runId())
                .orElseThrow(this::notFound);
        ExecutionPlan plan = plans.findForUpdate(projectId, preliminary.id()).orElseThrow(this::notFound);
        if (plan.status() == ExecutionPlan.Status.REJECTED) {
            return plan;
        }
        if (plan.status() != ExecutionPlan.Status.PENDING
                || run.status() != AgentRun.Status.WAITING_APPROVAL) {
            throw conflict("Only a pending plan can be rejected");
        }
        if (!plans.updateStatus(projectId, preliminary.id(), ExecutionPlan.Status.PENDING,
                ExecutionPlan.Status.REJECTED, clock.instant())) {
            throw conflict("Plan status changed concurrently");
        }
        events.append(ownerId, projectId, planEvent("execution.plan.rejected", plan));
        AgentRun advanced = createResumeTurn(ownerId, projectId, run, plan,
                List.of(), "REJECTED");
        runs.transition(ownerId, projectId, run.id(), advanced.version(), AgentRun.Status.RUNNING);
        return plans.find(projectId, preliminary.id()).orElseThrow();
    }

    /** Creates one durable continuation, pinned to this exact approval decision and dependencies. */
    private AgentRun createResumeTurn(UUID ownerId, UUID projectId, AgentRun run,
            ExecutionPlan plan, List<Task> mediaTasks, String decision) {
        int previousStep = run.nextStepIndex();
        if (previousStep >= run.policySnapshot().path("maxModelTurns").asInt(12) - 1) {
            throw conflict("Run has no model turn left for the plan result");
        }
        List<Task> planningTurns = tasks.listByRun(ownerId, projectId, run.id()).stream()
                .filter(task -> task.kind() == Task.Kind.AGENT_TURN
                        && task.input().path("stepIndex").asInt(-1) == previousStep)
                .toList();
        if (planningTurns.size() != 1) {
            throw conflict("Planning model turn is missing or ambiguous");
        }
        Task planningTurn = planningTurns.getFirst();
        if (planningTurn.status() == Task.Status.FAILED
                || planningTurn.status() == Task.Status.CANCELED) {
            throw conflict("Planning model turn has stopped");
        }
        List<UUID> dependencies = new ArrayList<>(mediaTasks.stream().map(Task::id).toList());
        if (planningTurn.status() != Task.Status.SUCCEEDED) {
            dependencies.add(planningTurn.id());
        }
        ObjectNode resume = mapper.createObjectNode();
        resume.put("schemaVersion", 1);
        resume.put("stepIndex", previousStep + 1);
        resume.put("resumePlanId", plan.id().toString());
        resume.put("resumeDecision", decision);
        // An image-plan continuation waits for an explicit choice of each exact result.
        // The model may not turn task completion into implicit approval of a keyframe.
        resume.put("awaitKeyframes", "APPROVED".equals(decision)
                && plan.stage() == ExecutionPlan.Stage.IMAGE);
        tasks.create(ownerId, projectId, run.id(), null,
                "agent-turn-" + (previousStep + 1), Task.Kind.AGENT_TURN,
                resume, null, 1, dependencies);
        return runs.advanceStep(ownerId, projectId, run.id(), run.version(), previousStep);
    }

    private List<Task> planTasks(UUID ownerId, UUID projectId, ExecutionPlan plan) {
        return tasks.listByRun(ownerId, projectId, plan.runId()).stream()
                .filter(task -> plan.id().equals(task.planId()) && task.attemptNo() == 1)
                .toList();
    }

    private AgentRun lockedRun(TrustedToolContext context) {
        return runRepository.findForUpdate(context.ownerId(), context.projectId(),
                context.runId()).orElseThrow(this::notFound);
    }

    private ProjectEventService.EventDraft planEvent(String type, ExecutionPlan plan) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("planId", plan.id().toString());
        payload.put("runId", plan.runId().toString());
        payload.put("stage", plan.stage().name());
        payload.put("status", type.substring(type.lastIndexOf('.') + 1).toUpperCase());
        return new ProjectEventService.EventDraft(type, 1, plan.id(),
                plan.revision(), payload);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "计划不存在", "计划不存在或当前用户无权访问。", false);
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PLAN_INVALID",
                "执行计划无效", detail, false);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "PLAN_CONFLICT",
                "计划状态冲突", detail, false);
    }

    /** Approval ID, frozen plan and the Tasks created (or replayed) by one decision. */
    public record ApprovalResult(UUID approvalId, ExecutionPlan plan,
            List<Task> tasks, boolean replayed) {}
}
