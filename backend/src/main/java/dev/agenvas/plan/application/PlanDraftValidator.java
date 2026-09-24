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

/** Validates a model proposal as an owner-scoped, acyclic and version-pinned media DAG. */
@Component
public class PlanDraftValidator {

    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}");
    private static final Set<String> PLAN_FIELDS = Set.of("stage", "objective", "steps");
    private static final Set<String> STEP_FIELDS = Set.of("stepKey", "outputSlotKey",
            "shotArtifactId", "shotVersionId", "imageArtifactId", "imageVersionId",
            "prompt", "negativePrompt", "dependsOnStepKeys");

    private final ArtifactService artifacts;
    private final ShotKeyframeSelectionRepository selections;
    private final TaskService tasks;
    private final ObjectMapper mapper;
    private final PlanWorkflowPolicy workflows;
    private final PlanProviderProperties provider;
    private final ObjectProvider<ComfyUiClient> comfyClient;

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

    /** Produces only server-validated fields; provider and workflow versions are server-owned. */
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

    /** Approval cannot silently authorize a plan against a different ComfyUI origin. */
    public boolean providerOriginMatches(ExecutionPlan plan) {
        if (!"comfyui".equalsIgnoreCase(provider.mode())) return true;
        String origin = comfyClient.getObject().originSha256();
        return !plan.steps().isEmpty() && plan.steps().stream().allMatch(step ->
                origin.equals(step.input().path("providerOriginSha256").asText()));
    }

    /** Rechecks content selection without considering canvas positions or event sequence. */
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

    private List<StepDraft> sortAcyclic(Map<String, StepDraft> byKey) {
        Map<String, Integer> state = new HashMap<>();
        List<StepDraft> sorted = new ArrayList<>();
        for (String key : byKey.keySet().stream().sorted().toList()) {
            visit(key, byKey, state, sorted);
        }
        return sorted;
    }

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

    private void allowOnly(JsonNode value, Set<String> names) {
        for (String field : value.propertyNames()) {
            if (!names.contains(field)) {
                throw invalid("Plan contains an unknown field");
            }
        }
    }

    private String requiredKey(JsonNode value, String field) {
        String text = requiredText(value, field, 160);
        if (!KEY.matcher(text).matches()) {
            throw invalid("Plan key is invalid");
        }
        return text;
    }

    private String requiredText(JsonNode value, String field, int maximum) {
        JsonNode text = value.path(field);
        if (!text.isTextual() || text.asText().isBlank() || text.asText().length() > maximum) {
            throw invalid("Plan field " + field + " is invalid");
        }
        return text.asText();
    }

    private UUID requiredUuid(JsonNode value, String field) {
        try {
            return UUID.fromString(requiredText(value, field, 36));
        } catch (IllegalArgumentException exception) {
            throw invalid("Plan field " + field + " must be a UUID");
        }
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PLAN_INVALID",
                "执行计划无效", detail, false);
    }

    /** Validated application-owned plan proposal before hash and persistence. */
    public record Draft(ExecutionPlan.Stage stage, String objective, JsonNode plan,
            JsonNode inputSnapshot, JsonNode estimate, int providerConfigVersion,
            String workflowVersion, List<ExecutionPlan.Step> steps) {}

    private record StepDraft(String stepKey, String outputSlotKey,
            UUID shotArtifactId, UUID shotVersionId, UUID imageArtifactId,
            UUID imageVersionId, List<String> dependencies, JsonNode input) {}
}
