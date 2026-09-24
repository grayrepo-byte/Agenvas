package dev.agenvas.plan.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.application.TaskService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 将模型提案校验为用户作用域内无环且固定素材版本的媒体执行图。 */
@Component
public class PlanDraftValidator {

    /** 计划步骤键和输出槽键的允许字符及长度。 */
    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}");
    /** 顶层计划输入白名单，Provider 选择和版本由服务端确定。 */
    private static final Set<String> PLAN_FIELDS = Set.of("stage", "objective", "steps");
    /** 单个步骤允许的模型字段，避免模型注入执行配置或资源路径。 */
    private static final Set<String> STEP_FIELDS = Set.of("stepKey", "outputSlotKey",
            "shotArtifactId", "shotVersionId", "imageArtifactId", "imageVersionId",
            "prompt", "negativePrompt", "dependsOnStepKeys");

    /** 验证输入版本的类型、当前选择和 Run 可见范围。 */
    private final ArtifactService artifacts;
    /** 读取并核对每个镜头当前选定的关键帧版本。 */
    private final ShotKeyframeSelectionRepository selections;
    /** 验证关键帧来源任务的 Run 归属和成功状态。 */
    private final TaskService tasks;
    /** 构造规范化计划与固定输入快照。 */
    private final ObjectMapper mapper;
    /** 提供各阶段工作流版本及视频时长约束。 */
    private final PlanWorkflowPolicy workflows;
    /** 提供当前 Provider 模式与配置摘要。 */
    private final PlanProviderProperties provider;
    /** 仅在 ComfyUI 模式读取端点摘要，Mock 模式不实例化客户端。 */
    private final ObjectProvider<ComfyUiClient> comfyClient;

    /** 注入产物、关键帧和工作流校验能力，不在校验器内发起媒体副作用。 */
    public PlanDraftValidator(ArtifactService artifacts,
            ShotKeyframeSelectionRepository selections, TaskService tasks, ObjectMapper mapper,
            PlanWorkflowPolicy workflows, PlanProviderProperties provider,
            ObjectProvider<ComfyUiClient> comfyClient) {
        this.artifacts = artifacts;
        this.selections = selections;
        this.tasks = tasks;
        this.mapper = mapper;
        this.workflows = workflows;
        this.provider = provider;
        this.comfyClient = comfyClient;
    }

        /** 校验模型计划、固定素材版本并生成确定顺序的服务端任务草稿。 */
    public Draft validate(TrustedToolContext context, AgentRun run, JsonNode proposed,
            int providerConfigVersion) {
        if (!proposed.isObject()) {
            throw invalid("Plan must be an object");
        }
        allowOnly(proposed, PLAN_FIELDS);
        ExecutionPlan.Stage stage;
        try {
            stage = ExecutionPlan.Stage.valueOf(requiredText(proposed, "stage", 16));
        } catch (IllegalArgumentException exception) {
            throw invalid("Plan stage must be IMAGE or VIDEO");
        }
        String objective = requiredText(proposed, "objective", 1_000);
        JsonNode suppliedSteps = proposed.path("steps");
        if (!suppliedSteps.isArray() || suppliedSteps.isEmpty() || suppliedSteps.size() > 6) {
            throw invalid("Plan must have one to six steps");
        }
        String redoShot = run.contextSnapshot().path("redoShotArtifactId").asText("");
        if (!redoShot.isEmpty() && suppliedSteps.size() != 1) {
            throw invalid("Scoped redo plan must contain exactly one target shot");
        }
        String workflowVersion = workflows.version(stage);
        String providerOriginSha256 = "comfyui".equalsIgnoreCase(provider.mode())
                ? comfyClient.getObject().originSha256() : null;
        Map<String, StepDraft> byKey = new HashMap<>();
        Set<String> slots = new HashSet<>();
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        ArrayNode pinned = snapshot.putArray("inputs");
        ArrayNode chosenKeyframes = snapshot.putArray("keyframeSelections");
        int videoDurationMs = 0;
        for (JsonNode supplied : suppliedSteps) {
            if (!supplied.isObject()) {
                throw invalid("Each plan step must be an object");
            }
            allowOnly(supplied, STEP_FIELDS);
            String stepKey = requiredKey(supplied, "stepKey");
            String outputSlotKey = requiredKey(supplied, "outputSlotKey");
            if (byKey.containsKey(stepKey) || !slots.add(outputSlotKey)) {
                throw invalid("Plan step keys and output slots must be unique");
            }
            UUID shotArtifactId = requiredUuid(supplied, "shotArtifactId");
            if (!redoShot.isEmpty() && !redoShot.equals(shotArtifactId.toString())) {
                throw invalid("Scoped redo cannot plan media for another shot");
            }
            UUID shotVersionId = requiredUuid(supplied, "shotVersionId");
            ArtifactVersion shot = pinnedVersion(context, run, shotArtifactId,
                    shotVersionId, Artifact.Kind.SHOT, pinned);
            UUID imageArtifactId = null;
            UUID imageVersionId = null;
            Long keyframeSelectionVersion = null;
            if (stage == ExecutionPlan.Stage.VIDEO) {
                imageArtifactId = requiredUuid(supplied, "imageArtifactId");
                imageVersionId = requiredUuid(supplied, "imageVersionId");
                pinnedVersion(context, run, imageArtifactId, imageVersionId,
                        Artifact.Kind.IMAGE, pinned);
                ShotKeyframeSelection selection = selections.find(context.projectId(),
                        shotArtifactId).orElseThrow(() -> invalid(
                                "Video plan requires a human-selected keyframe for every shot"));
                if (!shotVersionId.equals(selection.shotVersionId())
                        || !imageArtifactId.equals(selection.imageArtifactId())
                        || !imageVersionId.equals(selection.imageVersionId())) {
                    throw invalid("Video input does not match the selected keyframe version");
                }
                keyframeSelectionVersion = selection.version();
                Task selectedSource = tasks.get(context.ownerId(), context.projectId(),
                        selection.sourceTaskId());
                if (!context.runId().equals(selectedSource.runId())
                        || selectedSource.status() != Task.Status.SUCCEEDED) {
                    throw invalid("Video keyframe was not completed in this Run");
                }
                ObjectNode chosen = chosenKeyframes.addObject();
                chosen.put("shotArtifactId", shotArtifactId.toString());
                chosen.put("shotVersionId", shotVersionId.toString());
                chosen.put("imageVersionId", imageVersionId.toString());
                chosen.put("selectionVersion", selection.version());
                int durationMs = shot.content().path("durationMs").intValue();
                workflows.requireVideoDuration(durationMs);
                videoDurationMs = Math.addExact(videoDurationMs, durationMs);
            } else if (supplied.has("imageArtifactId") || supplied.has("imageVersionId")) {
                throw invalid("Image plan cannot choose a video input keyframe");
            }
            String prompt = requiredText(supplied, "prompt", 8_000);
            String negativePrompt = supplied.has("negativePrompt")
                    ? requiredText(supplied, "negativePrompt", 8_000) : null;
            List<String> dependencies = dependencies(supplied.path("dependsOnStepKeys"));
            ObjectNode taskInput = mapper.createObjectNode();
            taskInput.put("shotArtifactId", shotArtifactId.toString());
            taskInput.put("shotVersionId", shotVersionId.toString());
            if (stage == ExecutionPlan.Stage.IMAGE
                    && shot.content().has("selectedImageVersionId")) {
                UUID referenceVersionId = UUID.fromString(
                        shot.content().path("selectedImageVersionId").asText());
                ArtifactVersion reference = artifacts.requireImageVersionForTask(
                        context.ownerId(), context.projectId(), referenceVersionId);
                ArtifactService.ArtifactView currentReference = artifacts.get(context.ownerId(),
                        context.projectId(), reference.artifactId());
                if (currentReference.artifact().archivedAt() != null
                        || !referenceVersionId.equals(currentReference.currentVersion().id())) {
                    throw invalid("Selected reference image is no longer current");
                }
                ObjectNode referencePin = pinned.addObject();
                referencePin.put("artifactId", reference.artifactId().toString());
                referencePin.put("versionId", referenceVersionId.toString());
                referencePin.put("artifactVersion", currentReference.artifact().version());
                taskInput.put("referenceImageVersionId", referenceVersionId.toString());
            }
            if (imageVersionId != null) {
                taskInput.put("imageArtifactId", imageArtifactId.toString());
                taskInput.put("imageVersionId", imageVersionId.toString());
                taskInput.put("keyframeSelectionVersion", keyframeSelectionVersion.longValue());
                taskInput.put("durationMs", shot.content().path("durationMs").intValue());
            }
            taskInput.put("prompt", prompt);
            if (negativePrompt != null) {
                taskInput.put("negativePrompt", negativePrompt);
            }
            taskInput.put("providerConfigVersion", providerConfigVersion);
            if (providerOriginSha256 != null) {
                taskInput.put("providerOriginSha256", providerOriginSha256);
            }
            taskInput.put("workflowVersion", workflowVersion);
            byKey.put(stepKey, new StepDraft(stepKey, outputSlotKey, shotArtifactId,
                    shotVersionId, imageArtifactId, imageVersionId,
                    dependencies, taskInput));
        }
        List<StepDraft> sorted = sortAcyclic(byKey);
        List<ExecutionPlan.Step> steps = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            StepDraft step = sorted.get(index);
            steps.add(new ExecutionPlan.Step(step.stepKey(), index,
                    stage == ExecutionPlan.Stage.IMAGE
                            ? Task.Kind.IMAGE_GENERATION : Task.Kind.VIDEO_GENERATION,
                    step.shotArtifactId(), step.shotVersionId(),
                    step.imageArtifactId(), step.imageVersionId(),
                    step.outputSlotKey(), step.input(), step.dependencies()));
        }
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("schemaVersion", 1);
        normalized.put("stage", stage.name());
        normalized.put("objective", objective);
        ArrayNode normalizedSteps = normalized.putArray("steps");
        for (ExecutionPlan.Step step : steps) {
            ObjectNode item = normalizedSteps.addObject();
            item.put("stepKey", step.stepKey());
            item.put("outputSlotKey", step.outputSlotKey());
            item.set("input", step.input().deepCopy());
            item.set("dependsOnStepKeys", mapper.valueToTree(step.dependencyKeys()));
        }
        ObjectNode estimate = mapper.createObjectNode();
        estimate.put("imageCount", stage == ExecutionPlan.Stage.IMAGE ? steps.size() : 0);
        estimate.put("videoCount", stage == ExecutionPlan.Stage.VIDEO ? steps.size() : 0);
        estimate.put("videoSeconds", BigDecimal.valueOf(videoDurationMs, 3).toPlainString());
        estimate.put("costSource", "mock".equals(provider.mode())
                ? "MOCK_UNPRICED" : "PROVIDER_UNPRICED");
        return new Draft(stage, objective, normalized, snapshot, estimate,
                providerConfigVersion, workflowVersion, List.copyOf(steps));
    }

        /** 审批时确认计划固定的 ComfyUI 来源仍与当前配置一致。 */
    public boolean providerOriginMatches(ExecutionPlan plan) {
        if (!"comfyui".equalsIgnoreCase(provider.mode())) return true;
        String origin = comfyClient.getObject().originSha256();
        return !plan.steps().isEmpty() && plan.steps().stream().allMatch(step ->
                origin.equals(step.input().path("providerOriginSha256").asText()));
    }

        /** 比较计划快照中的素材版本与关键帧选择版本，布局和事件序号不参与判断。 */
    public boolean currentInputsMatch(UUID ownerId, UUID projectId, JsonNode snapshot) {
        JsonNode inputs = snapshot.path("inputs");
        if (!inputs.isArray()) {
            return false;
        }
        for (JsonNode input : inputs) {
            try {
                UUID artifactId = UUID.fromString(input.path("artifactId").asText());
                UUID versionId = UUID.fromString(input.path("versionId").asText());
                ArtifactService.ArtifactView current = artifacts.get(ownerId, projectId, artifactId);
                if (!versionId.equals(current.currentVersion().id())
                        || current.artifact().version() != input.path("artifactVersion").longValue()
                        || current.artifact().archivedAt() != null) {
                    return false;
                }
            } catch (ApiProblemException | IllegalArgumentException exception) {
                return false;
            }
        }
        JsonNode chosen = snapshot.path("keyframeSelections");
        if (!chosen.isArray()) {
            return false;
        }
        for (JsonNode expected : chosen) {
            try {
                UUID shotId = UUID.fromString(expected.path("shotArtifactId").asText());
                UUID shotVersionId = UUID.fromString(expected.path("shotVersionId").asText());
                UUID imageVersionId = UUID.fromString(expected.path("imageVersionId").asText());
                ShotKeyframeSelection current = selections.find(projectId, shotId).orElse(null);
                if (current == null || !shotVersionId.equals(current.shotVersionId())
                        || !imageVersionId.equals(current.imageVersionId())
                        || current.version() != expected.path("selectionVersion").longValue()) {
                    return false;
                }
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return true;
    }

    /** 验证版本属于声明的产物、对本次 Run 可见且仍是该产物当前版本，并写入快照。 */
    private ArtifactVersion pinnedVersion(TrustedToolContext context, AgentRun run,
            UUID artifactId, UUID versionId, Artifact.Kind expectedKind, ArrayNode snapshot) {
        ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                context.projectId(), context.runId(), versionId, run.contextSnapshot());
        if (!artifactId.equals(version.artifactId())) {
            throw invalid("Input version does not belong to the declared Artifact");
        }
        ArtifactService.ArtifactView current = artifacts.get(context.ownerId(),
                context.projectId(), artifactId);
        if (current.artifact().kind() != expectedKind
                || current.artifact().archivedAt() != null
                || !versionId.equals(current.currentVersion().id())) {
            throw invalid("Input type or current selection does not match the proposed version");
        }
        ObjectNode pin = snapshot.addObject();
        pin.put("artifactId", artifactId.toString());
        pin.put("versionId", versionId.toString());
        pin.put("artifactVersion", current.artifact().version());
        return version;
    }

    /** 校验依赖键数组的形状、格式和唯一性；目标是否存在由拓扑排序阶段确认。 */
    private List<String> dependencies(JsonNode values) {
        if (!values.isArray() || values.size() > 6) {
            throw invalid("Step dependencies must be an array of at most six keys");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !KEY.matcher(value.asText()).matches()
                    || !unique.add(value.asText())) {
                throw invalid("Step dependencies contain an invalid or duplicate key");
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    /** 以稳定的键顺序执行 DFS，输出依赖优先的顺序并拒绝环路。 */
    private List<StepDraft> sortAcyclic(Map<String, StepDraft> byKey) {
        Map<String, Integer> state = new HashMap<>();
        List<StepDraft> sorted = new ArrayList<>();
        for (String key : byKey.keySet().stream().sorted().toList()) {
            visit(key, byKey, state, sorted);
        }
        return sorted;
    }

    /** 三色 DFS：灰色节点表示回边，黑色节点表示已加入结果。 */
    private void visit(String key, Map<String, StepDraft> byKey,
            Map<String, Integer> state, List<StepDraft> sorted) {
        StepDraft step = byKey.get(key);
        if (step == null) {
            throw invalid("Dependency step does not exist");
        }
        int status = state.getOrDefault(key, 0);
        if (status == 1) {
            throw invalid("Plan dependency graph has a cycle");
        }
        if (status == 2) {
            return;
        }
        state.put(key, 1);
        for (String dependency : step.dependencies()) {
            visit(dependency, byKey, state, sorted);
        }
        state.put(key, 2);
        sorted.add(step);
    }

    /** 拒绝计划契约未声明的字段，避免未经校验的数据进入执行快照。 */
    private void allowOnly(JsonNode value, Set<String> names) {
        for (String field : value.propertyNames()) {
            if (!names.contains(field)) {
                throw invalid("Plan contains an unknown field");
            }
        }
    }

    /** 读取符合执行键字符集的字段名，并限制长度以适配持久化键。 */
    private String requiredKey(JsonNode value, String field) {
        String text = requiredText(value, field, 160);
        if (!KEY.matcher(text).matches()) {
            throw invalid("Plan key is invalid");
        }
        return text;
    }

    /** 读取非空文本并限制字符数；提示词和标识字段共用类型校验但采用不同上限。 */
    private String requiredText(JsonNode value, String field, int maximum) {
        JsonNode text = value.path(field);
        if (!text.isTextual() || text.asText().isBlank() || text.asText().length() > maximum) {
            throw invalid("Plan field " + field + " is invalid");
        }
        return text.asText();
    }

    /** 将输入字段解析为 UUID，格式错误统一报告为计划参数错误。 */
    private UUID requiredUuid(JsonNode value, String field) {
        try {
            return UUID.fromString(requiredText(value, field, 36));
        } catch (IllegalArgumentException exception) {
            throw invalid("Plan field " + field + " must be a UUID");
        }
    }

    /** 构造计划结构或引用校验失败时返回的 400 问题响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PLAN_INVALID",
                "执行计划无效", detail, false);
    }

    /** 已通过领域校验、尚待审批和持久化的应用侧计划草稿。
     * @param stage 计划阶段，决定步骤生成类型
     * @param objective 用户可审阅的计划目标
     * @param plan 已去除未知字段并规范化顺序的提案
     * @param inputSnapshot 审批时重验素材与关键帧所需的版本快照
     * @param estimate 按步骤数和时长计算的非价格用量估算
     * @param providerConfigVersion 计划固定的 Provider 配置版本
     * @param workflowVersion 计划固定的工作流模板版本
     * @param steps 按依赖拓扑排序的任务步骤
     */
    public record Draft(ExecutionPlan.Stage stage, String objective, JsonNode plan,
            JsonNode inputSnapshot, JsonNode estimate, int providerConfigVersion,
            String workflowVersion, List<ExecutionPlan.Step> steps) {}

    /** 校验阶段的临时步骤；其依赖仍用 stepKey 表示，直到排序和解析完成。
     * @param stepKey 步骤的计划内唯一键
     * @param outputSlotKey 输出写入的镜头内容槽位
     * @param shotArtifactId 步骤修改的镜头产物
     * @param shotVersionId 创建计划时固定的镜头版本
     * @param imageArtifactId 步骤使用的输入图片产物；不使用图片时为空
     * @param imageVersionId 精确图片版本；不使用图片时为空
     * @param dependencies 依赖步骤键；仅引用本计划中的前置步骤
     * @param input 经结构和领域规则规范化的 Provider 输入
     */
    private record StepDraft(String stepKey, String outputSlotKey,
            UUID shotArtifactId, UUID shotVersionId, UUID imageArtifactId,
            UUID imageVersionId, List<String> dependencies, JsonNode input) {}
}
