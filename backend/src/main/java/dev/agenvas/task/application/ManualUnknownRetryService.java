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

/** 用户明确接受潜在重复费用后，为无法核对的外部提交建立可审计的新尝试。 */
@Service
public class ManualUnknownRetryService {

    /** 创建替代任务所需的精确风险确认值，不能由模型代填。 */
    public static final String RISK_ACKNOWLEDGEMENT = "ACCEPT_POSSIBLE_DUPLICATE_COST";

    /** 读取原任务，并通过统一任务规则创建替代媒体任务。 */
    private final TaskService tasks;
    /** 检查原请求、替代关系和未执行下游依赖。 */
    private final TaskRepository repository;
    /** 读取原任务已审批计划。 */
    private final ExecutionPlanService plans;
    /** 重新核验计划输入和 Provider origin。 */
    private final PlanDraftValidator validator;
    /** 检查当前 Provider 配置版本是否仍等于审批版本。 */
    private final PlanProviderProperties provider;
    /** 检查审批时的工作流版本仍可使用。 */
    private final PlanWorkflowPolicy workflows;
    /** 确认项目仍可执行新尝试。 */
    private final ProjectService projects;
    /** 确认原 Run 仍等待该媒体任务，并按条件解除阻断。 */
    private final AgentRunService runs;
    /** 对已有输出目标再次比较产物版本。 */
    private final ArtifactService artifacts;
    /** 新尝试独立预留媒体用量，原 UNKNOWN 费用记录不消失。 */
    private final UsageService usage;
    /** 与替代关系、依赖重连同事务写入项目事件。 */
    private final ProjectEventService events;
    /** 构造不包含任务输入的状态事件负载。 */
    private final ObjectMapper mapper;
    /** 给不可变替代关系记录创建时间。 */
    private final Clock clock;

    /** 组装 UNKNOWN 替代任务创建所需的风险确认、计划核验、额度与依赖更新能力。
     * @param tasks 读取和条件推进持久化任务
     * @param repository 读取原提交尝试与下游依赖
     * @param plans 核验原计划和审批状态
     * @param validator 重新校验固定输入和计划结构
     * @param provider 当前 Provider 模式及配置版本
     * @param workflows 确认原工作流版本仍受支持
     * @param projects 校验项目仍处于活动状态
     * @param runs 核验原 Run 并按条件恢复编排
     * @param artifacts 确认结果归档目标未被用户更新
     * @param usage 为新尝试单独预留用量
     * @param events 持久化替代关系和任务事件
     * @param mapper 构造安全事件负载
     * @param clock 为替代关系提供创建时间
     */
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

    /**
     * 用户确认可能重复收费后，在同一项目事务中创建一次新的媒体任务与独立用量预留。
     * 必须重新核验原 UNKNOWN 任务、已审批计划、Provider/工作流版本、固定输入和预算；
     * 只把尚未执行的 PENDING 下游任务改为依赖新任务，原 UNKNOWN 记录始终保留。
     *
     * @param ownerId 经认证且实际批准风险的用户 ID
     * @param projectId 原任务和计划所属项目
     * @param originalTaskId 状态仍为 UNKNOWN、没有可查询原请求 ID 的媒体任务
     * @param expectedTaskVersion 用户确认时看到的原任务版本
     * @param acknowledgement 必须精确等于风险确认常量
     * @param idempotencyKey 同一人工重试命令的客户端键；相同键不能用于别的任务或版本
     * @return 新建或同键重放得到的替代任务
     */
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

    /** 被替换的 UNKNOWN 任务只作为审计历史；其他未替换 UNKNOWN 或失败任务仍阻止恢复 Run。 */
    private boolean noOtherBlockers(UUID ownerId, UUID projectId, UUID runId, UUID originalId) {
        return tasks.listByRun(ownerId, projectId, runId).stream()
                .filter(task -> !task.id().equals(originalId))
                .noneMatch(task -> (task.status() == Task.Status.UNKNOWN
                        && repository.findManualReplacement(projectId, task.id()).isEmpty())
                        || task.status() == Task.Status.BLOCKED
                        || task.status() == Task.Status.FAILED
                        || task.status() == Task.Status.CANCELED);
    }

    /** 对原任务与替代任务沿用状态事件，促使客户端刷新两者及依赖关系。 */
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

    /** 原任务或审批前提变化时返回稳定冲突码，不隐式创建新的外部请求。 */
    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "UNKNOWN_RETRY_CONFLICT",
                "不能创建新尝试", detail, false);
    }
}
