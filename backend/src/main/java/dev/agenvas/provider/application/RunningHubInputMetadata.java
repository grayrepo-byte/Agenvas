package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.RunningHubDefinition.FieldType;
import dev.agenvas.provider.application.RunningHubImportService.Confidence;
import dev.agenvas.provider.application.RunningHubImportService.InsightReason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads data-only widget metadata without changing the administrator's imported source. */
final class RunningHubInputMetadata {
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final BigDecimal MIN_LONG = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);

    record Parsed(FieldType type, JsonNode defaultValue, BigDecimal minimum, BigDecimal maximum,
            Integer maxLength, boolean required, List<RunningHubDefinition.Option> options, boolean missingOptions,
            boolean ambiguousMedia, Confidence confidence, List<InsightReason> reasons) {}

    private record Widget(FieldType type, JsonNode settings, JsonNode choices) {}

    private RunningHubInputMetadata() {}

    /** Explicit protocol types win; only generic inputs borrow their type from widget metadata. */
    static Parsed parse(ObjectMapper mapper, JsonNode input) {
        Widget widget = widget(mapper, input.get("fieldData"));
        FieldType declared = type(input.path("fieldType").asText(""));
        Set<FieldType> uploads = uploadTypes(widget.settings());
        FieldType resolved = declared;
        boolean generic = declared == null || declared == FieldType.STRING;
        if (media(declared)) resolved = declared;
        else if ((generic || declared == FieldType.SELECT) && uploads.size() == 1) resolved = uploads.iterator().next();
        else if (generic) {
            // Conflicting upload markers cannot safely choose a media type or expose a server file list.
            resolved = !uploads.isEmpty() ? FieldType.STRING : widget.type();
            if (resolved == null) resolved = declared == FieldType.STRING ? FieldType.STRING : scalarType(input.get("fieldValue"));
        }
        List<RunningHubDefinition.Option> options = resolved == FieldType.SELECT && uploads.isEmpty()
                ? options(widget.choices()) : List.of();
        boolean missingOptions = resolved == FieldType.SELECT && options.isEmpty();
        if (missingOptions) resolved = FieldType.STRING;
        boolean ambiguousMedia = (generic || declared == FieldType.SELECT) && uploads.size() > 1;
        List<InsightReason> reasons = new ArrayList<>();
        Confidence confidence = Confidence.DECLARED;
        if (media(declared) || !generic && declared != FieldType.SELECT) reasons.add(InsightReason.TYPE_DECLARATION);
        else if ((generic || declared == FieldType.SELECT) && uploads.size() == 1) reasons.add(InsightReason.UPLOAD_WIDGET);
        else if (ambiguousMedia) {
            reasons.add(InsightReason.CONFLICTING_MEDIA_METADATA);
            confidence = Confidence.UNCERTAIN;
        } else if (generic && widget.type() != null) reasons.add(InsightReason.WIDGET_TYPE);
        else if (declared != null) reasons.add(InsightReason.TYPE_DECLARATION);
        else if (scalar(input.get("fieldValue"))) {
            reasons.add(InsightReason.JSON_VALUE);
            confidence = Confidence.INFERRED;
        } else {
            reasons.add(InsightReason.MISSING_TYPE_METADATA);
            confidence = Confidence.UNCERTAIN;
        }
        if (missingOptions) {
            reasons.add(InsightReason.MISSING_ENUM_OPTIONS);
            confidence = Confidence.UNCERTAIN;
        }

        boolean numeric = resolved == FieldType.NUMBER || resolved == FieldType.INTEGER;
        BigDecimal minimum = numeric ? bound(input, widget.settings(), "minimum", "min") : null;
        BigDecimal maximum = numeric ? bound(input, widget.settings(), "maximum", "max") : null;
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            minimum = null;
            maximum = null;
        }
        Integer maxLength = resolved == FieldType.STRING ? maxLength(input, widget.settings()) : null;
        JsonNode required = input.get("required");
        if (required == null || !required.isBoolean()) required = widget.settings().get("required");
        JsonNode value = normalize(mapper, input.get("fieldValue"), resolved, options, minimum, maximum, maxLength);
        if (value == null) value = normalize(mapper, widget.settings().get("default"), resolved, options, minimum, maximum, maxLength);
        return new Parsed(resolved, value, minimum, maximum, maxLength,
                required != null && required.isBoolean() && required.asBoolean(), options, missingOptions,
                ambiguousMedia, confidence, List.copyOf(reasons));
    }

    private static Widget widget(ObjectMapper mapper, JsonNode raw) {
        JsonNode data = raw;
        if (data != null && data.isTextual()) {
            try { data = mapper.readTree(data.asText()); }
            catch (RuntimeException invalid) { data = null; }
        }
        JsonNode empty = mapper.createObjectNode();
        if (data == null || data.isNull()) return new Widget(null, empty, null);
        if (data.isObject()) {
            JsonNode tag = data.hasNonNull("type") ? data.get("type") : data.get("fieldType");
            if (tag != null && tag.isNull()) tag = null;
            FieldType type = tag == null ? null : type(tag.asText(""));
            JsonNode choices = data.get("options");
            if (tag == null && choices != null && choices.isArray()) type = FieldType.SELECT;
            return new Widget(type, data, choices);
        }
        if (data.isArray() && data.size() == 2 && data.get(1).isObject()) {
            JsonNode first = data.get(0);
            if (first.isArray()) return new Widget(FieldType.SELECT, data.get(1), first);
            FieldType type = first.isTextual() ? type(first.asText()) : null;
            if (type != null) return new Widget(type, data.get(1), data.get(1).get("options"));
            // A mixed scalar/object option list is valid, but unknown widget settings are not an option.
            if (!data.get(1).has("value") || !Set.of("label", "value").containsAll(data.get(1).propertyNames()))
                return new Widget(null, data.get(1), data.get(1).get("options"));
        }
        return new Widget(data.isArray() ? FieldType.SELECT : null, empty, data.isArray() ? data : null);
    }

    private static FieldType type(String value) {
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "IMAGE" -> FieldType.IMAGE;
            case "AUDIO" -> FieldType.AUDIO;
            case "VIDEO" -> FieldType.VIDEO;
            case "INT", "INTEGER" -> FieldType.INTEGER;
            case "NUMBER", "FLOAT", "DOUBLE", "DECIMAL" -> FieldType.NUMBER;
            case "BOOL", "BOOLEAN" -> FieldType.BOOLEAN;
            case "STRING" -> FieldType.STRING;
            case "LIST", "COMBO", "SELECT" -> FieldType.SELECT;
            default -> null;
        };
    }

    private static FieldType scalarType(JsonNode value) {
        if (value != null && value.isBoolean()) return FieldType.BOOLEAN;
        if (value != null && value.isIntegralNumber()) return FieldType.INTEGER;
        if (value != null && value.isNumber()) return FieldType.NUMBER;
        return FieldType.STRING;
    }

    private static Set<FieldType> uploadTypes(JsonNode settings) {
        Set<FieldType> types = new HashSet<>();
        if (enabled(settings.get("image_upload"))) types.add(FieldType.IMAGE);
        if (enabled(settings.get("audio_upload"))) types.add(FieldType.AUDIO);
        if (enabled(settings.get("video_upload"))) types.add(FieldType.VIDEO);
        JsonNode extensions = settings.get("vhs_path_extensions");
        if (extensions != null && extensions.isArray()) types.add(FieldType.VIDEO);
        return types;
    }

    private static boolean enabled(JsonNode value) { return value != null && value.isBoolean() && value.asBoolean(); }

    private static boolean media(FieldType type) { return type == FieldType.IMAGE || type == FieldType.AUDIO || type == FieldType.VIDEO; }

    /** Keep option identity, order and duplicate values; publication remains the uniqueness boundary. */
    private static List<RunningHubDefinition.Option> options(JsonNode choices) {
        if (choices == null || !choices.isArray() || choices.size() > RunningHubDefinition.MAX_OPTIONS) return List.of();
        List<RunningHubDefinition.Option> result = new ArrayList<>();
        for (JsonNode option : choices) {
            JsonNode value = option.isObject() ? option.get("value") : option;
            if (!scalar(value)) return List.of();
            String label = option.isObject() ? option.path("label").asText(value.asText()) : value.asText();
            result.add(new RunningHubDefinition.Option(label, value.deepCopy()));
        }
        return List.copyOf(result);
    }

    private static JsonNode normalize(ObjectMapper mapper, JsonNode raw, FieldType type,
            List<RunningHubDefinition.Option> options, BigDecimal minimum, BigDecimal maximum, Integer maxLength) {
        if (media(type) || raw == null || raw.isNull() || !scalar(raw)) return null;
        JsonNode value;
        switch (type) {
            case STRING -> {
                String text = raw.asText();
                if (text.length() > (maxLength == null ? RunningHubDefinition.MAX_TEXT_LENGTH : maxLength)) return null;
                value = mapper.valueToTree(text);
            }
            case NUMBER, INTEGER -> {
                BigDecimal number = decimal(raw);
                if (number == null || minimum != null && number.compareTo(minimum) < 0
                        || maximum != null && number.compareTo(maximum) > 0) return null;
                if (type == FieldType.INTEGER) {
                    if (number.compareTo(MIN_LONG) < 0 || number.compareTo(MAX_LONG) > 0) return null;
                    try { value = mapper.valueToTree(number.longValueExact()); }
                    catch (ArithmeticException invalid) { return null; }
                } else value = mapper.valueToTree(number);
            }
            case BOOLEAN -> {
                if (raw.isBoolean()) value = raw.deepCopy();
                else if (raw.isTextual() && "true".equalsIgnoreCase(raw.asText().strip())) value = mapper.valueToTree(true);
                else if (raw.isTextual() && "false".equalsIgnoreCase(raw.asText().strip())) value = mapper.valueToTree(false);
                else return null;
            }
            case SELECT -> {
                for (var option : options) if (RunningHubDefinition.scalarEquals(option.value(), raw)) return option.value().deepCopy();
                BigDecimal number = raw.isTextual() ? decimal(raw) : null;
                if (number != null) for (var option : options)
                    if (option.value().isNumber() && number.compareTo(option.value().decimalValue()) == 0) return option.value().deepCopy();
                return null;
            }
            default -> { return null; }
        }
        return value;
    }

    private static BigDecimal bound(JsonNode input, JsonNode settings, String canonical, String alias) {
        for (JsonNode source : List.of(input, settings)) {
            BigDecimal value = decimal(source.get(canonical));
            if (value == null) value = decimal(source.get(alias));
            if (value != null) return value;
        }
        return null;
    }

    private static Integer maxLength(JsonNode input, JsonNode settings) {
        for (JsonNode source : List.of(input, settings)) for (String key : List.of("maxLength", "max_length")) {
            BigDecimal value = decimal(source.get(key));
            if (value == null || value.compareTo(BigDecimal.ONE) < 0
                    || value.compareTo(BigDecimal.valueOf(RunningHubDefinition.MAX_TEXT_LENGTH)) > 0) continue;
            try { return value.intValueExact(); }
            catch (ArithmeticException invalid) { /* Invalid widget constraints do not reject discovery. */ }
        }
        return null;
    }

    private static boolean scalar(JsonNode value) {
        return value != null && (value.isBoolean() || value.isNumber() && decimal(value) != null
                || value.isTextual() && value.asText().length() <= RunningHubDefinition.MAX_TEXT_LENGTH);
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || !(value.isTextual() || value.isNumber())) return null;
        try {
            String text = value.asText().strip();
            if (!NUMBER.matcher(text).matches()) return null;
            BigDecimal result = new BigDecimal(text);
            return Double.isFinite(result.doubleValue()) ? result : null;
        } catch (NumberFormatException invalid) { return null; }
    }
}
