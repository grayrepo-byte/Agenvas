package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Data-only input contract for the fixed AutoDL prompt/duration/video protocol. */
public final class AutoDlWorkflowDefinition {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SECONDS = 30;
    public static final int MAX_IMAGES = 14;
    public static final int MAX_AUDIOS = 3;
    public static final int MAX_REFERENCES = 14;
    public static final int MAX_PROMPT = 10000;
    public static final int MAX_RESOLUTIONS = 48;
    public static final int MAX_TIERS = 16;
    public static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,119}";
    public static final String TIER_PATTERN = "[1-9][0-9]{2,3}p";
    public static final String RESOLUTION_PATTERN = "[1-9][0-9]{2,3}p(横|竖|\\(1:1\\))(\\([0-9]{1,5}\\*[0-9]{1,5}\\))?";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "id", "label", "minimumSeconds", "maximumSeconds",
            "promptLimit", "mode", "imageFields", "audioFields", "minimumImages", "minimumAudios",
            "resolutions", "defaultResolution", "supportsSeed");
    private AutoDlWorkflowDefinition() {}

    public static AutoDlWorkflows.Workflow parse(JsonNode source) {
        if (source == null || !source.isObject() || !source.propertyNames().equals(FIELDS)) throw invalid();
        integer(source, "schemaVersion", SCHEMA_VERSION, SCHEMA_VERSION);
        String id = text(source, "id", ID_PATTERN);
        String label = text(source, "label", "[^\\p{Cntrl}]{1,160}");
        int minimum = integer(source, "minimumSeconds", 1, MAX_SECONDS);
        int maximum = integer(source, "maximumSeconds", minimum, MAX_SECONDS);
        int prompt = integer(source, "promptLimit", 1, MAX_PROMPT);
        String mode = text(source, "mode", "TEXT|START_END|GENERAL_REFERENCE");
        List<String> images = strings(source, "imageFields", MAX_IMAGES, "first_frame|last_frame|ref_image_[0-9]{1,2}");
        List<String> audios = strings(source, "audioFields", MAX_AUDIOS, "ref_audio_[0-9]");
        int minimumImages = integer(source, "minimumImages", 0, images.size());
        int minimumAudios = integer(source, "minimumAudios", 0, audios.size());
        if (images.size() + audios.size() > MAX_REFERENCES || !source.path("supportsSeed").isBoolean()) throw invalid();
        if ("TEXT".equals(mode) && (!images.isEmpty() || !audios.isEmpty())) throw invalid();
        if ("START_END".equals(mode) && (!images.equals(List.of("first_frame", "last_frame")) || minimumImages != 2 || !audios.isEmpty())) throw invalid();
        if ("GENERAL_REFERENCE".equals(mode) && images.isEmpty() && audios.isEmpty()) throw invalid();
        if (!"START_END".equals(mode)) indexed(images, "ref_image_");
        indexed(audios, "ref_audio_");
        List<String> resolutions = strings(source, "resolutions", MAX_RESOLUTIONS, RESOLUTION_PATTERN);
        if (resolutions.isEmpty()) throw invalid();
        // Each published tier/ratio must have one unambiguous provider enum.
        Set<String> combinations = new HashSet<>();
        for (String resolution : resolutions) {
            String key = resolution.substring(0, resolution.indexOf('p') + 1)
                    + (resolution.contains("横") ? "横" : resolution.contains("竖") ? "竖" : "(1:1)");
            if (!combinations.add(key)) throw invalid();
        }
        if (resolutions.stream().map(value -> value.substring(0, value.indexOf('p') + 1)).distinct().count() > MAX_TIERS) throw invalid();
        String defaultResolution = text(source, "defaultResolution", TIER_PATTERN);
        if (resolutions.stream().noneMatch(value -> value.startsWith(defaultResolution + "横")
                || value.startsWith(defaultResolution + "竖") || value.startsWith(defaultResolution + "(1:1)"))) throw invalid();
        return new AutoDlWorkflows.Workflow(id, label, minimum, maximum, prompt, mode, List.copyOf(images), List.copyOf(audios),
                minimumImages, minimumAudios, List.copyOf(resolutions), defaultResolution, source.path("supportsSeed").booleanValue());
    }
    public static ObjectNode json(ObjectMapper mapper, AutoDlWorkflows.Workflow workflow) {
        return ((ObjectNode) mapper.valueToTree(workflow)).put("schemaVersion", SCHEMA_VERSION);
    }
    private static void indexed(List<String> fields, String prefix) {
        for (int i = 0; i < fields.size(); i++) if (!fields.get(i).equals(prefix + i)) throw invalid();
    }
    private static List<String> strings(JsonNode source, String name, int limit, String pattern) {
        JsonNode values = source.path(name);
        if (!values.isArray() || values.size() > limit) throw invalid();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !value.asText().matches(pattern) || result.contains(value.asText())) throw invalid();
            result.add(value.asText());
        }
        return result;
    }
    private static String text(JsonNode source, String name, String pattern) {
        JsonNode value = source.path(name);
        if (!value.isTextual() || !value.asText().matches(pattern)) throw invalid();
        return value.asText();
    }
    private static int integer(JsonNode source, String name, int minimum, int maximum) {
        JsonNode value = source.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum) throw invalid();
        return value.intValue();
    }
    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "AUTODL_WORKFLOW_UNSUPPORTED",
                ApiMessage.of("api.auto-dl-workflows.invalid-autodl-input"), ApiMessage.of("api.autodl.invalid-definition"), false);
    }
}
