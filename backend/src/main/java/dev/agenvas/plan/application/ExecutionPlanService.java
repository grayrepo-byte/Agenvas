package dev.agenvas.plan.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 管理计划提案与人工审批；审批通过前不创建媒体任务，审批时重新核对输入和额度。 */
@Service
public class ExecutionPlanService {

    /** 检查项目可用性、所有者并取得项目状态版本。 */
    private final ProjectService projects;
    /** 锁定并推进 Run 状态，避免审批与取消并发覆盖。 */
    private final AgentRunRepository runRepository;
    /** 执行带用户权限检查的 Run 查询和状态迁移。 */
    private final AgentRunService runs;
    /** 持久化计划正文、步骤及审批凭据。 */
    private final ExecutionPlanRepository plans;
    /** 将模型提案校验并规范化为带版本固定的步骤草稿。 */
    private final PlanDraftValidator validator;
    /** 当前 Provider 配置版本，用于审批阶段检测配置漂移。 */
    private final PlanProviderProperties provider;
    /** 读取对应阶段固定工作流版本并校验媒体时长。 */
    private final PlanWorkflowPolicy workflows;
    /** 读取 Run 已创建的媒体任务数量以执行预算限制。 */
    private final TaskRepository taskRepository;
    /** 创建媒体任务和模型续跑任务。 */
    private final TaskService tasks;
    /** 为审批创建的媒体任务原子预留用量。 */
    private final UsageService usage;
    /** 在状态事务内分配项目事件序号并写入计划事件。 */
    private final ProjectEventService events;
    /** 生成不可变 JSON 快照与审批预留载荷。 */
    private final ObjectMapper mapper;
    /** 为计划创建、状态迁移和审批凭据提供统一时刻。 */
    private final Clock clock;
    private final MediaCapabilityService capabilities;

    /** 组装计划生命周期依赖；校验器、仓储和服务分别负责规则、持久化及副作用。 */
    public ExecutionPlanService(ProjectService projects, AgentRunRepository runRepository,
            AgentRunService runs, ExecutionPlanRepository plans, PlanDraftValidator validator,
            PlanProviderProperties provider, PlanWorkflowPolicy workflows,
            TaskRepository taskRepository, TaskService tasks,
            UsageService usage, ProjectEventService events, ObjectMapper mapper, Clock clock,
            MediaCapabilityService capabilities) {
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
        this.capabilities = capabilities;
    }

    /** 在项目事件序号锁内校验并保存模型提案，将 Run 转入等待审批且不创建媒体任务。 */
    @Transactional
    public ExecutionPlan propose(TrustedToolContext context, JsonNode proposed) {
        return events.recordChange(context.ownerId(), context.projectId(), () ->
                ProjectEventService.Change.unchanged(proposeLocked(context, proposed))).value();
    }

    /** 在项目事件行锁内读取 Run 和素材，保持与产物修订相同的锁顺序。 */
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
        String planHash = planHash(draft, inputHash);
        Instant now = clock.instant();
        ExecutionPlan plan = new ExecutionPlan(UUID.randomUUID(), context.projectId(),
                context.runId(), revision, draft.stage(), draft.needsInput()
                        ? ExecutionPlan.Status.NEEDS_INPUT : ExecutionPlan.Status.PENDING,
                draft.objective(), draft.plan(), draft.inputSnapshot(), inputHash,
                planHash, draft.providerConfigVersion(), draft.workflowVersion(),
                draft.estimate(), now, now, draft.steps());
        plans.create(plan);
        events.append(context.ownerId(), context.projectId(), planEvent("execution.plan.proposed", plan));
        runs.transition(context.ownerId(), context.projectId(), context.runId(),
                run.version(), AgentRun.Status.WAITING_APPROVAL);
        return plan;
    }

    /** 仅在请求者拥有计划所属 Run 时返回计划内容。 */
    @Transactional(readOnly = true)
    public ExecutionPlan get(UUID ownerId, UUID projectId, UUID planId) {
        ExecutionPlan plan = plans.find(projectId, planId).orElseThrow(this::notFound);
        runs.get(ownerId, projectId, plan.runId());
        return plan;
    }

    /** 校验 Run 的用户和项目归属后，按仓储提供的 ID 顺序读取该 Run 的计划。 */
    @Transactional(readOnly = true)
    public List<ExecutionPlan> listByRun(UUID ownerId, UUID projectId, UUID runId) {
        runs.get(ownerId, projectId, runId);
        return plans.findIdsByRun(projectId, runId).stream()
                .map(id -> plans.find(projectId, id).orElseThrow(this::notFound))
                .toList();
    }

    /** Lists only currently published capabilities compatible with this frozen step's output and duration. */
    @Transactional(readOnly = true)
    public List<MediaCapabilityService.Candidate> candidates(UUID ownerId, UUID projectId,
            UUID planId, String stepKey) {
        ExecutionPlan plan = get(ownerId, projectId, planId);
        ExecutionPlan.Step step = plan.steps().stream()
                .filter(item -> item.stepKey().equals(stepKey)).findFirst()
                .orElseThrow(this::notFound);
        int seconds = step.kind() == Task.Kind.VIDEO_GENERATION
                ? step.input().path("durationSeconds").asInt(-1) : 0;
        return capabilities.candidates(step.kind(), seconds, step.imageVersionId() != null);
    }

    /** An edit creates a fresh immutable proposal and invalidates every confirmation of the old hash. */
    @Transactional
    public ExecutionPlan reviseStep(UUID ownerId, UUID projectId, UUID planId,
            String stepKey, UUID capabilityId, JsonNode inputPatch, String expectedPlanHash) {
        if (inputPatch == null || !inputPatch.isObject()) {
            throw invalid("Step inputPatch must be an object");
        }
        Set<String> allowed = Set.of("prompt", "negativePrompt", "imageArtifactId",
                "imageVersionId");
        for (String field : inputPatch.propertyNames()) {
            if (!allowed.contains(field)) {
                throw invalid("Step inputPatch contains an unknown field");
            }
        }
        ExecutionPlan preliminary = get(ownerId, projectId, planId);
        return events.recordChange(ownerId, projectId, () ->
                ProjectEventService.Change.unchanged(reviseLocked(ownerId, projectId,
                        preliminary, stepKey, capabilityId, inputPatch, expectedPlanHash))).value();
    }

    private ExecutionPlan reviseLocked(UUID ownerId, UUID projectId,
            ExecutionPlan preliminary, String stepKey, UUID capabilityId,
            JsonNode inputPatch, String expectedPlanHash) {
        AgentRun run = runRepository.findForUpdate(ownerId, projectId, preliminary.runId())
                .orElseThrow(this::notFound);
        ExecutionPlan old = plans.findForUpdate(projectId, preliminary.id())
                .orElseThrow(this::notFound);
        if ((old.status() != ExecutionPlan.Status.PENDING
                && old.status() != ExecutionPlan.Status.NEEDS_INPUT)
                || run.status() != AgentRun.Status.WAITING_APPROVAL
                || !old.planHash().equals(expectedPlanHash)) {
            throw conflict("计划已变化，请重新读取后修改");
        }
        ObjectNode proposed = mapper.createObjectNode();
        proposed.put("stage", old.stage().name());
        proposed.put("objective", old.objective());
        var proposedSteps = proposed.putArray("steps");
        boolean found = false;
        for (ExecutionPlan.Step step : old.steps()) {
            ObjectNode item = proposedSteps.addObject();
            item.put("stepKey", step.stepKey());
            item.put("outputSlotKey", step.outputSlotKey());
            item.put("shotArtifactId", step.shotArtifactId().toString());
            item.put("shotVersionId", step.shotVersionId().toString());
            if (step.imageArtifactId() != null) {
                item.put("imageArtifactId", step.imageArtifactId().toString());
                item.put("imageVersionId", step.imageVersionId().toString());
            }
            item.put("prompt", step.input().path("prompt").asText());
            if (step.input().has("negativePrompt")) {
                item.put("negativePrompt", step.input().path("negativePrompt").asText());
            }
            item.set("dependsOnStepKeys", mapper.valueToTree(step.dependencyKeys()));
            if (step.binding() != null) {
                item.put("capabilityId", step.binding().capabilityId().toString());
            }
            if (step.stepKey().equals(stepKey)) {
                found = true;
                if (capabilityId != null) {
                    item.put("capabilityId", capabilityId.toString());
                }
                for (String field : inputPatch.propertyNames()) {
                    JsonNode value = inputPatch.path(field);
                    if (value.isNull()) item.remove(field);
                    else item.set(field, value.deepCopy());
                }
            }
        }
        if (!found) throw notFound();
        PlanDraftValidator.Draft draft = validator.validate(
                new TrustedToolContext(ownerId, projectId, run.id()), run, proposed,
                provider.configVersion());
        int revision = plans.nextRevision(projectId, run.id(), draft.stage());
        String inputHash = sha256(draft.inputSnapshot().toString());
        Instant now = clock.instant();
        ExecutionPlan revised = new ExecutionPlan(UUID.randomUUID(), projectId, run.id(),
                revision, draft.stage(), draft.needsInput()
                        ? ExecutionPlan.Status.NEEDS_INPUT : ExecutionPlan.Status.PENDING,
                draft.objective(), draft.plan(), draft.inputSnapshot(), inputHash,
                planHash(draft, inputHash), draft.providerConfigVersion(),
                draft.workflowVersion(), draft.estimate(), now, now, draft.steps());
        if (!plans.updateStatus(projectId, old.id(), old.status(),
                ExecutionPlan.Status.STALE, now)) {
            throw conflict("计划已变化，请重新读取后修改");
        }
        plans.create(revised);
        events.append(ownerId, projectId, planEvent("execution.plan.stale", old));
        events.append(ownerId, projectId, planEvent("execution.plan.proposed", revised));
        return revised;
    }

    /** 校验用户确认的计划摘要，再于项目锁内原子预留额度、创建步骤任务并记录事件。 */
    @Transactional
    public ApprovalResult approve(UUID ownerId, UUID projectId, UUID planId,
            String submittedPlanHash, List<String> confirmedStepKeys) {
        if (submittedPlanHash == null || !submittedPlanHash.matches("[0-9a-f]{64}")) {
            throw invalid("A valid planHash is required for approval");
        }
        ExecutionPlan preliminary = get(ownerId, projectId, planId);
        return events.recordChange(ownerId, projectId, () ->
                ProjectEventService.Change.unchanged(approveLocked(ownerId, projectId,
                        preliminary, submittedPlanHash, confirmedStepKeys))).value();
    }

    /** 锁定 Run 和计划，重验 Provider、输入版本及预算后原子预留额度并创建依赖任务。 */
    private ApprovalResult approveLocked(UUID ownerId, UUID projectId,
            ExecutionPlan preliminary, String submittedPlanHash,
            List<String> confirmedStepKeys) {
        AgentRun run = runRepository.findForUpdate(ownerId, projectId, preliminary.runId())
                .orElseThrow(this::notFound);
        ExecutionPlan plan = plans.findForUpdate(projectId, preliminary.id()).orElseThrow(this::notFound);
        if (!plan.planHash().equals(submittedPlanHash)) {
            throw conflict("Plan hash changed; reload the proposal before approval");
        }
        Set<String> expectedSteps = plan.steps().stream()
                .map(ExecutionPlan.Step::stepKey).collect(java.util.stream.Collectors.toSet());
        if (confirmedStepKeys == null || confirmedStepKeys.size() != expectedSteps.size()
                || !expectedSteps.equals(Set.copyOf(confirmedStepKeys))) {
            throw conflict("请逐项确认所有步骤");
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
        if (plan.steps().stream().allMatch(step -> step.binding() != null)) {
            for (ExecutionPlan.Step step : plan.steps()) {
                int seconds = step.kind() == Task.Kind.VIDEO_GENERATION
                        ? step.input().path("durationSeconds").asInt(-1) : 0;
                if (!capabilities.isCurrentBinding(step.binding(), step.kind(), seconds)) {
                    throw conflict("媒体能力版本或状态已变化，请重新审阅计划");
                }
                String frozenOrigin = capabilities.pinnedSnapshot(step.binding())
                        .connectionVersion().originSha256();
                String stepOrigin = step.input().has("providerOriginSha256")
                        ? step.input().path("providerOriginSha256").asText() : null;
                if (!java.util.Objects.equals(frozenOrigin, stepOrigin)) {
                    throw conflict("媒体计划的连接来源与冻结版本不一致");
                }
            }
        } else if (plan.providerConfigVersion() != provider.configVersion()
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
            if (step.binding() != null) {
                taskRepository.bindMediaTask(task.id(), step.binding());
            }
            usage.reserveMediaTask(ownerId, task,
                    plan.estimate().path("costSource").asText());
            taskIdsByStep.put(step.stepKey(), task.id());
            created.add(task);
        }
        AgentRun advanced = createResumeTurn(ownerId, projectId, run, plan,
                created, ResumeDecision.APPROVED);
        events.append(ownerId, projectId, planEvent("execution.plan.approved", plan));
        runs.transition(ownerId, projectId, run.id(), advanced.version(),
                AgentRun.Status.WAITING_TASKS);
        return new ApprovalResult(approvalId,
                plans.find(projectId, preliminary.id()).orElseThrow(), List.copyOf(created), false);
    }

    /** 拒绝待审批计划且不创建媒体任务，随后安排同一 Run 继续下一轮模型回合。 */
    @Transactional
    public ExecutionPlan reject(UUID ownerId, UUID projectId, UUID planId) {
        ExecutionPlan preliminary = get(ownerId, projectId, planId);
        return events.recordChange(ownerId, projectId, () ->
                ProjectEventService.Change.unchanged(rejectLocked(ownerId, projectId,
                        preliminary))).value();
    }

    /** 按项目后 Run 的锁顺序更新拒绝状态，避免与取消或内容修改交错。 */
    private ExecutionPlan rejectLocked(UUID ownerId, UUID projectId, ExecutionPlan preliminary) {
        AgentRun run = runRepository.findForUpdate(ownerId, projectId, preliminary.runId())
                .orElseThrow(this::notFound);
        ExecutionPlan plan = plans.findForUpdate(projectId, preliminary.id()).orElseThrow(this::notFound);
        if (plan.status() == ExecutionPlan.Status.REJECTED) {
            return plan;
        }
        if (plan.status() != ExecutionPlan.Status.PENDING
                && plan.status() != ExecutionPlan.Status.NEEDS_INPUT
                || run.status() != AgentRun.Status.WAITING_APPROVAL) {
            throw conflict("Only a pending plan can be rejected");
        }
        if (!plans.updateStatus(projectId, preliminary.id(), plan.status(),
                ExecutionPlan.Status.REJECTED, clock.instant())) {
            throw conflict("Plan status changed concurrently");
        }
        events.append(ownerId, projectId, planEvent("execution.plan.rejected", plan));
        AgentRun advanced = createResumeTurn(ownerId, projectId, run, plan,
                List.of(), ResumeDecision.REJECTED);
        runs.transition(ownerId, projectId, run.id(), advanced.version(), AgentRun.Status.RUNNING);
        return plans.find(projectId, preliminary.id()).orElseThrow();
    }

    /** 创建固定审批决定和任务依赖的持久化续跑回合；图片阶段仍等待用户选择关键帧。 */
    private AgentRun createResumeTurn(UUID ownerId, UUID projectId, AgentRun run,
            ExecutionPlan plan, List<Task> mediaTasks, ResumeDecision decision) {
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
        resume.put("resumeDecision", decision.name());
        // An image-plan continuation waits for an explicit choice of each exact result.
        // The model may not turn task completion into implicit approval of a keyframe.
        resume.put("awaitKeyframes", decision == ResumeDecision.APPROVED
                && plan.stage() == ExecutionPlan.Stage.IMAGE);
        tasks.create(ownerId, projectId, run.id(), null,
                "agent-turn-" + (previousStep + 1), Task.Kind.AGENT_TURN,
                resume, null, 1, dependencies);
        return runs.advanceStep(ownerId, projectId, run.id(), run.version(), previousStep);
    }

    /** 查询该计划首次创建的任务，用于重复审批返回原结果。 */
    private List<Task> planTasks(UUID ownerId, UUID projectId, ExecutionPlan plan) {
        return tasks.listByRun(ownerId, projectId, plan.runId()).stream()
                .filter(task -> plan.id().equals(task.planId()) && task.attemptNo() == 1)
                .toList();
    }

    /** 按可信上下文取得带行锁的 Run，缺失或越权时统一返回未找到。 */
    private AgentRun lockedRun(TrustedToolContext context) {
        return runRepository.findForUpdate(context.ownerId(), context.projectId(),
                context.runId()).orElseThrow(this::notFound);
    }

    /** 生成只包含计划标识和状态的事件载荷，不向事件总线复制计划正文。 */
    private ProjectEventService.EventDraft planEvent(String type, ExecutionPlan plan) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("planId", plan.id().toString());
        payload.put("runId", plan.runId().toString());
        payload.put("stage", plan.stage().name());
        payload.put("status", type.substring(type.lastIndexOf('.') + 1).toUpperCase());
        return new ProjectEventService.EventDraft(type, 1, plan.id(),
                plan.revision(), payload);
    }

    /** 对规范化 JSON 文本计算 UTF-8 SHA-256，用于用户确认精确提案。 */
    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String planHash(PlanDraftValidator.Draft draft, String inputHash) {
        return sha256(draft.plan().toString() + "\n" + inputHash + "\n"
                + draft.providerConfigVersion() + "\n" + draft.workflowVersion() + "\n"
                + draft.estimate().toString());
    }

    /** 构造计划不存在或不属于当前用户时的 404 响应。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "计划不存在", "计划不存在或当前用户无权访问。", false);
    }

    /** 构造计划输入不符合契约时的 400 响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PLAN_INVALID",
                "执行计划无效", detail, false);
    }

    /** 构造计划、Run 或预算状态不再允许当前决定时的 409 响应。 */
    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "PLAN_CONFLICT",
                "计划状态冲突", detail, false);
    }

    /** 一次审批决定的标识、冻结计划及新建或重放得到的任务列表。
     * @param approvalId 已持久化审批凭据的 ID
     * @param plan 审批后的计划快照
     * @param tasks 本次审批创建或重放返回的媒体任务
     * @param replayed 是否命中已经完成的相同审批
     */
    public record ApprovalResult(UUID approvalId, ExecutionPlan plan,
            List<Task> tasks, boolean replayed) {}
}
