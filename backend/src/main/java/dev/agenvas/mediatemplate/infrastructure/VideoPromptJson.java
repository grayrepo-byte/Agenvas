package dev.agenvas.mediatemplate.infrastructure;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Reference;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Common JSON field reading, while each adapter owns its upstream format and attribution. */
final class VideoPromptJson {
    private VideoPromptJson() {}
    static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.asString() : "";
    }
    static String localized(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.asString() : text(value, "zh").isBlank() ? text(value, "en") : text(value, "zh");
    }
    static List<String> strings(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) throw ThirdPartyPromptValidation.invalid();
        var result = new ArrayList<String>();
        for (var item : value) {
            if (!item.isString()) throw ThirdPartyPromptValidation.invalid();
            result.add(item.asString());
        }
        return List.copyOf(result);
    }
    static List<Reference> references(ObjectMapper mapper, JsonNode node) {
        var value = node.path("references");
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) throw ThirdPartyPromptValidation.invalid();
        var result = new ArrayList<Reference>();
        for (var item : value) result.add(mapper.treeToValue(item, Reference.class));
        return List.copyOf(result);
    }
    static JsonNode items(JsonNode root) {
        if (root.isArray()) return root;
        if (root.path("prompts").isArray()) return root.path("prompts");
        if (root.path("items").isArray()) return root.path("items");
        throw ThirdPartyPromptValidation.invalid();
    }
}
