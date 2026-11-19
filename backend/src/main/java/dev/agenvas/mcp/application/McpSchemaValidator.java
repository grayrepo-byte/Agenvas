package dev.agenvas.mcp.application;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The closed JSON Schema subset shared by MCP input and output contracts. */
final class McpSchemaValidator {
    private static final int MAX_DEPTH = 32;
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean",
            "null");
    // Preserve whole-string matching for the published color and digest contracts.
    private static final Set<String> PATTERNS = Set.of("^#[0-9A-F]{6}$", "^#[0-9a-fA-F]{6}$", "^[0-9a-f]{64}$");
    // Documentation and client hints. They never constrain or supply values at runtime.
    private static final Set<String> ANNOTATIONS = Set.of("description", "default");

    private McpSchemaValidator() {
    }

    static void requireSupported(JsonNode schema, String path) {
        if (!schema.isObject())
            throw unsupported(path, "expected a schema object");
        if (schema.has("anyOf")) {
            // Matching short-circuits to the alternatives. Sibling constraints would be ignored.
            for (String keyword : schema.propertyNames())
                if (!"anyOf".equals(keyword) && !ANNOTATIONS.contains(keyword))
                    throw unsupported(path, "anyOf cannot be combined with " + keyword);
            JsonNode alternatives = schema.path("anyOf");
            if (!alternatives.isArray() || alternatives.isEmpty())
                throw unsupported(path, "anyOf must have schema alternatives");
            for (int i = 0; i < alternatives.size(); i++)
                requireSupported(alternatives.path(i), path + "/anyOf/" + i);
            return;
        }

        String type = schema.path("type").asText();
        if (!TYPES.contains(type))
            throw unsupported(path, "missing or unsupported type");
        if ("array".equals(type) && !schema.has("items"))
            throw unsupported(path, "array requires items");
        for (String keyword : schema.propertyNames()) {
            JsonNode constraint = schema.path(keyword);
            String location = path + "/" + keyword;
            boolean supported = switch (keyword) {
                case "type", "description", "default" -> true;
                case "enum" -> constraint.isArray() && !constraint.isEmpty();
                case "properties" -> "object".equals(type) && constraint.isObject();
                case "required" -> "object".equals(type) && constraint.isArray()
                        && constraint.valueStream().allMatch(JsonNode::isString);
                case "additionalProperties" -> "object".equals(type) && constraint.isBoolean();
                case "items" -> "array".equals(type);
                case "uniqueItems" -> "array".equals(type) && constraint.isBoolean();
                case "minItems", "maxItems" -> "array".equals(type) && isSize(constraint);
                case "minLength", "maxLength" -> "string".equals(type) && isSize(constraint);
                case "pattern" ->
                    "string".equals(type) && constraint.isString() && PATTERNS.contains(constraint.asText());
                case "format" -> "string".equals(type) && "uuid".equals(constraint.asText());
                case "minimum", "maximum" -> ("integer".equals(type) || "number".equals(type))
                        && constraint.isNumber();
                default -> false;
            };
            if (!supported)
                throw unsupported(location, "unsupported constraint for " + type);
            if ("properties".equals(keyword))
                for (String property : constraint.propertyNames())
                    requireSupported(constraint.path(property), location + "/" + property);
            if ("items".equals(keyword))
                requireSupported(constraint, location);
        }
    }

    private static boolean isSize(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
    }

    private static IllegalStateException unsupported(String path, String reason) {
        return new IllegalStateException("Unsupported MCP schema at " + path + ": " + reason);
    }

    static boolean matches(JsonNode schema, JsonNode value) {
        return matches(schema, value, 0);
    }

    private static boolean matches(JsonNode schema, JsonNode value, int depth) {
        if (depth > MAX_DEPTH)
            return false;
        if (schema.has("anyOf"))
            return schema.path("anyOf").valueStream().anyMatch(s -> matches(s, value, depth + 1));
        String type = schema.path("type").asText();
        boolean typed = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
        if (!typed)
            return false;
        if (schema.has("enum") && schema.path("enum").valueStream().noneMatch(value::equals))
            return false;
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.has(required.asText()))
                    return false;
            JsonNode props = schema.path("properties");
            for (String key : value.propertyNames()) {
                if (props.has(key)) {
                    if (!matches(props.path(key), value.path(key), depth + 1))
                        return false;
                } else if (schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean())
                    return false;
            }
        } else if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(0)
                    || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE))
                return false;
            if (schema.path("uniqueItems").asBoolean() && value.valueStream().distinct().count() != value.size())
                return false;
            for (JsonNode item : value)
                if (!matches(schema.path("items"), item, depth + 1))
                    return false;
        } else if (value.isString()) {
            String text = value.asText();
            if (text.length() < schema.path("minLength").asInt(0)
                    || text.length() > schema.path("maxLength").asInt(Integer.MAX_VALUE))
                return false;
            if (schema.has("pattern") && !text.matches(schema.path("pattern").asText()))
                return false;
            if ("uuid".equals(schema.path("format").asText())) {
                try {
                    if (!UUID.fromString(text).toString().equalsIgnoreCase(text))
                        return false;
                } catch (IllegalArgumentException invalid) {
                    return false;
                }
            }
        } else if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0)
                return false;
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0)
                return false;
        }
        return true;
    }
}
