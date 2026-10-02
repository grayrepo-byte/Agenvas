package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AutoDlWorkflowDefinition;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.infrastructure.AutoDlClient;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Official public metadata is only a candidate; publishing remains an administrator action. */
@Service
public class AutoDlWorkflowDiscoveryService {
    private static final int MAX_PAGES = 10;
    private static final int MAX_LABEL_LENGTH = 160;
    private static final int MAX_SOURCE_CHARACTERS = 2 * 1024 * 1024;
    private final AutoDlClient client;
    private final ObjectMapper mapper;
    public AutoDlWorkflowDiscoveryService(AutoDlClient client, ObjectMapper mapper) { this.client = client; this.mapper = mapper; }
    public record Entry(String id, String label) {}
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode data = client.workflowCatalog(page);
                if (!data.path("list").isArray() || data.path("list").size() > AutoDlClient.CATALOG_PAGE_SIZE
                        || !data.path("max_page").isIntegralNumber() || !data.path("max_page").canConvertToInt()
                        || data.path("max_page").asInt() < 1 || data.path("max_page").asInt() > MAX_PAGES) throw new AutoDlClient.TechnicalFailure();
                for (JsonNode value : data.path("list")) {
                    String id = value.path("uuid").asText();
                    String label = value.path("name").asText();
                    if (!id.matches(AutoDlWorkflowDefinition.ID_PATTERN) || label.isBlank() || label.length() > MAX_LABEL_LENGTH)
                        throw new AutoDlClient.TechnicalFailure();
                    entries.add(new Entry(id, label));
                }
                if (page >= data.path("max_page").asInt(1)) return List.copyOf(entries);
            }
            throw new AutoDlClient.TechnicalFailure();
        } catch (AutoDlClient.TechnicalFailure failure) { throw unavailable(); }
    }
    public ObjectNode preview(String id, JsonNode supplied) {
        if (id == null || !id.matches(AutoDlWorkflowDefinition.ID_PATTERN)) throw AutoDlWorkflowDefinition.invalid();
        JsonNode data;
        try { data = supplied == null || supplied.isNull() ? client.workflowMetadata(id) : supplied; }
        catch (AutoDlClient.TechnicalFailure failure) { throw unavailable(); }
        if (data.toString().length() > MAX_SOURCE_CHARACTERS) throw AutoDlWorkflowDefinition.invalid();
        if (data.has("data")) data = data.get("data");
        if (!id.equals(data.path("uuid").asText()) || !data.path("input_rules").isObject()) throw AutoDlWorkflowDefinition.invalid();
        JsonNode rules = data.path("input_rules");
        if (!"integer".equals(rules.path("duration").path("type").asText())
                || !List.of("string", "prompt").contains(rules.path("prompt").path("type").asText())
                || !"enum".equals(rules.path("resolution").path("type").asText())
                || !rules.path("resolution").path("options").isArray()) throw AutoDlWorkflowDefinition.invalid();
        List<String> images = new ArrayList<>();
        List<String> audios = new ArrayList<>();
        for (String field : rules.propertyNames()) {
            if (field.matches("ref_image_[0-9]{1,2}")) images.add(field);
            else if (field.matches("ref_audio_[0-9]")) audios.add(field);
            else if (!List.of("prompt", "duration", "resolution", "seed", "first_frame", "last_frame").contains(field))
                throw AutoDlWorkflowDefinition.invalid();
        }
        images.sort(java.util.Comparator.comparingInt(value -> Integer.parseInt(value.substring("ref_image_".length()))));
        audios.sort(java.util.Comparator.comparingInt(value -> Integer.parseInt(value.substring("ref_audio_".length()))));
        boolean frames = rules.has("first_frame") || rules.has("last_frame");
        if (frames) {
            if (!images.isEmpty()) throw AutoDlWorkflowDefinition.invalid();
            images = List.of("first_frame", "last_frame");
        }
        for (String field : images) if (!"image".equals(rules.path(field).path("type").asText())) throw AutoDlWorkflowDefinition.invalid();
        for (String field : audios) if (!"audio".equals(rules.path(field).path("type").asText())) throw AutoDlWorkflowDefinition.invalid();
        if (rules.has("seed") && (!"integer".equals(rules.path("seed").path("type").asText())
                || !rules.path("seed").path("min").canConvertToLong()
                || !rules.path("seed").path("max").canConvertToLong()
                || !rules.path("seed").path("min").isIntegralNumber()
                || !rules.path("seed").path("max").isIntegralNumber()
                || rules.path("seed").path("min").asLong(-1) < 0
                || rules.path("seed").path("min").asLong(-1) > 1
                || rules.path("seed").path("max").asLong(-1) != AutoDlWorkflows.MAX_SEED)) throw AutoDlWorkflowDefinition.invalid();
        List<String> resolutions = new ArrayList<>();
        for (JsonNode option : rules.path("resolution").path("options")) {
            if (!option.path("label").isTextual()) throw AutoDlWorkflowDefinition.invalid();
            resolutions.add(option.path("label").asText());
        }
        String defaultValue = rules.path("resolution").path("default").asText();
        if (!resolutions.contains(defaultValue)) throw AutoDlWorkflowDefinition.invalid();
        if (!rules.path("duration").path("min").isIntegralNumber() || !rules.path("duration").path("min").canConvertToInt()
                || !rules.path("duration").path("max").isIntegralNumber() || !rules.path("duration").path("max").canConvertToInt()
                || !rules.path("prompt").path("max_length").isIntegralNumber() || !rules.path("prompt").path("max_length").canConvertToInt()) throw AutoDlWorkflowDefinition.invalid();
        var workflow = new AutoDlWorkflows.Workflow(id, data.path("name").asText(), rules.path("duration").path("min").asInt(),
                rules.path("duration").path("max").asInt(), Math.min(AutoDlWorkflowDefinition.MAX_PROMPT, rules.path("prompt").path("max_length").asInt()),
                frames ? "START_END" : images.isEmpty() && audios.isEmpty() ? "TEXT" : "GENERAL_REFERENCE",
                images, audios, minimum(rules, images), minimum(rules, audios), resolutions,
                defaultValue.substring(0, defaultValue.indexOf('p') + 1), rules.has("seed"));
        ObjectNode definition = AutoDlWorkflowDefinition.json(mapper, workflow);
        AutoDlWorkflowDefinition.parse(definition);
        return definition;
    }
    private static int minimum(JsonNode rules, List<String> fields) {
        int minimum = 0;
        boolean optionalSeen = false;
        for (String field : fields) {
            JsonNode required = rules.path(field).path("required");
            if (!required.isBoolean()) throw AutoDlWorkflowDefinition.invalid();
            if (required.booleanValue()) {
                // The fixed indexed protocol fills a prefix, so non-prefix mandatory slots are unsupported.
                if (optionalSeen) throw AutoDlWorkflowDefinition.invalid();
                minimum++;
            } else optionalSeen = true;
        }
        return minimum;
    }
    private static ApiProblemException unavailable() {
        return new ApiProblemException(HttpStatus.BAD_GATEWAY, "AUTODL_CATALOG_UNAVAILABLE",
                ApiMessage.of("api.auto-dl-workflows.invalid-autodl-input"), ApiMessage.of("api.autodl.catalog-unavailable"), true);
    }
}
