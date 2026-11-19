package dev.agenvas.llm.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent tool readers that validate JSON node types without coercion. */
final class ToolArguments {
    enum UuidSyntax {
        JAVA, THIRTY_SIX_CHARACTERS, CANONICAL
    }

    private final UuidSyntax uuidSyntax;
    private final Supplier<ApiProblemException> invalid;

    ToolArguments(UuidSyntax uuidSyntax, Supplier<ApiProblemException> invalid) {
        this.uuidSyntax = uuidSyntax;
        this.invalid = invalid;
    }

    ObjectNode object(String arguments, Set<String> allowed, Function<String, JsonNode> readTree) {
        JsonNode parsed;
        try {
            parsed = readTree.apply(arguments);
        } catch (RuntimeException malformed) {
            throw invalid.get();
        }
        if (!(parsed instanceof ObjectNode object))
            throw invalid.get();
        requireAllowedFields(object, allowed);
        return object;
    }

    void requireAllowedFields(ObjectNode input, Set<String> allowed) {
        if (!allowed.containsAll(input.propertyNames()))
            throw invalid.get();
    }

    UUID uuid(JsonNode value) {
        if (!value.isTextual())
            throw invalid.get();
        String text = value.asText();
        if (uuidSyntax == UuidSyntax.THIRTY_SIX_CHARACTERS && text.length() != 36)
            throw invalid.get();
        UUID parsed;
        try {
            parsed = UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            throw invalid.get();
        }
        if (uuidSyntax == UuidSyntax.CANONICAL && !parsed.toString().equalsIgnoreCase(text))
            throw invalid.get();
        return parsed;
    }

    long nonnegativeLong(JsonNode value, long maximum) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
                || value.longValue() > maximum)
            throw invalid.get();
        return value.longValue();
    }

    int nonnegativeInt(JsonNode value) {
        return (int) nonnegativeLong(value, Integer.MAX_VALUE);
    }

    /** Applies the tool argument limit in UTF-16 code units, as counted by String.length(). */
    String text(JsonNode value, int maximumLength) {
        if (!value.isTextual() || value.asText().length() > maximumLength)
            throw invalid.get();
        return value.asText();
    }
}
