package dev.agenvas.provider.domain;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reviewed H3 parameter mappings, never downloaded or executed as ComfyUI graphs. */
public final class AutoDlWorkflows {
    public static final String ADAPTER_ID = "AUTODL_COMFY_VIDEO";
    public static final String DEFAULT_WORKFLOW = "minimax_h3_z0903";
    public static final long MAX_SEED = 999_999_999_999_999L;
    public static final int MAX_REFERENCE_BYTES = 15 * 1024 * 1024;
    public static final int MAX_TOTAL_REFERENCE_BYTES = 60 * 1024 * 1024;
    public static final Set<String> SETTINGS = Set.of("workflowId", "videoResolution", "seed");
    public static final List<Workflow> ALL = load();

    public record Workflow(String id, String label, int minimumSeconds, int maximumSeconds,
            int promptLimit, String mode, List<String> imageFields, List<String> audioFields,
            int minimumImages, int minimumAudios, List<String> resolutions,
            String defaultResolution, boolean supportsSeed) {
        public MediaAdapterRegistry.Declaration declaration() {
            return new MediaAdapterRegistry.Declaration(MediaPlatform.AUTODL,
                    Task.Kind.VIDEO_GENERATION, minimumSeconds, maximumSeconds, false,
                    imageFields.size(), Set.of(mode), mode, "START_END".equals(mode),
                    Set.of(), Set.of(), Set.of(), false, false, audioFields.size());
        }
        public String resolution(String tier, String ratio) {
            String orientation = switch (ratio) {
                case "9:16" -> "竖";
                case "16:9" -> "横";
                case "1:1" -> "(1:1)";
                default -> throw invalid(ApiMessage.of("api.auto-dl-workflows.autodl-does-not-support-this-frame"));
            };
            return resolutions.stream().filter(value -> value.startsWith(tier + orientation))
                    .findFirst().orElseThrow(() -> invalid(ApiMessage.of("api.auto-dl-workflows.this-workflow-does-not-support", tier, ratio)));
        }
        public void validate(String prompt, int seconds, String inputMode, int images, int audios) {
            if (prompt.isBlank() || prompt.codePointCount(0, prompt.length()) > promptLimit
                    || seconds < minimumSeconds || seconds > maximumSeconds || !mode.equals(inputMode)
                    || images < minimumImages || images > imageFields.size()
                    || audios < minimumAudios || audios > audioFields.size()) {
                throw invalid(ApiMessage.of("api.auto-dl-workflows.autodl-workflow-input-mismatch-requires-images-audio-inputs-and-a", label, minimumImages, imageFields.size(), minimumAudios, audioFields.size(), minimumSeconds, maximumSeconds));
            }
        }
    }
    private AutoDlWorkflows() {}
    public static Workflow require(JsonNode settings) {
        return require(settings.path("workflowId").asText(DEFAULT_WORKFLOW));
    }
    public static Workflow require(String id) {
        return ALL.stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> invalid(ApiMessage.of("api.auto-dl-workflows.unsupported-autodl-workflow-id")));
    }
    public static void normalize(JsonNode source, ObjectNode target) {
        Workflow workflow = require(source);
        target.put("workflowId", workflow.id());
        JsonNode value = source.get("videoResolution");
        String tier = value == null ? workflow.defaultResolution() : value.asText();
        if (value != null && !value.isTextual() || workflow.resolutions().stream()
                .noneMatch(resolution -> resolution.startsWith(tier + "竖")
                        || resolution.startsWith(tier + "横") || resolution.startsWith(tier + "(1:1)")))
            throw invalid(ApiMessage.of("api.auto-dl-workflows.this-workflow-does-not-support-this-resolution"));
        target.put("videoResolution", tier);
        if (source.has("seed")) {
            JsonNode seed = source.get("seed");
            if (!workflow.supportsSeed() || !seed.isIntegralNumber() || !seed.canConvertToLong()
                    || seed.longValue() < 1 || seed.longValue() > MAX_SEED)
                throw invalid(ApiMessage.of("api.auto-dl-workflows.this-workflow-does-not-support-this-seed-the-valid-range", MAX_SEED));
            target.put("seed", seed.longValue());
        }
    }
    private static List<Workflow> load() {
        try (var stream = AutoDlWorkflows.class.getResourceAsStream("/providers/autodl-h3-workflows.json")) {
            if (stream == null) throw new IllegalStateException("AutoDL manifest missing");
            return List.copyOf(Arrays.asList(new ObjectMapper().readValue(stream, Workflow[].class)));
        } catch (IOException failure) { throw new IllegalStateException("AutoDL manifest unreadable", failure); }
    }
    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.auto-dl-workflows.invalid-autodl-input"), detail, false);
    }
}
