package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.RunningHubDefinition.FieldType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RunningHubInputMetadataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void typeEvidenceDistinguishesExplicitMetadataFromValuesAndUncertainFallbacks() {
        assertThat(parse("{\"fieldType\":\"FLOAT\",\"fieldValue\":1}").reasons())
                .containsExactly(RunningHubImportService.InsightReason.TYPE_DECLARATION);
        assertThat(parse("{\"fieldType\":\"STRING\",\"fieldData\":[\"FLOAT\",{}]}").reasons())
                .containsExactly(RunningHubImportService.InsightReason.WIDGET_TYPE);
        assertThat(parse("{\"fieldData\":[\"STRING\",{\"image_upload\":true}]}").reasons())
                .containsExactly(RunningHubImportService.InsightReason.UPLOAD_WIDGET);
        var value = parse("{\"fieldValue\":1.5}");
        assertThat(value.confidence()).isEqualTo(RunningHubImportService.Confidence.INFERRED);
        assertThat(value.reasons()).containsExactly(RunningHubImportService.InsightReason.JSON_VALUE);
        var unknown = parse("{}");
        assertThat(unknown.confidence()).isEqualTo(RunningHubImportService.Confidence.UNCERTAIN);
        assertThat(unknown.reasons()).containsExactly(RunningHubImportService.InsightReason.MISSING_TYPE_METADATA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "AUDIO", "VIDEO"})
    void explicitMediaTypesWinOverConflictingWidgetMetadataAndClearRemoteDefaults(String type) {
        var input = mapper.createObjectNode().put("fieldType", type).put("fieldValue", "synthetic.png");
        input.set("fieldData", mapper.readTree("""
                ["COMBO", {"options":["synthetic.png","synthetic.png"],
                  "image_upload":true,"audio_upload":true,"video_upload":true,"default":"synthetic.png"}]
                """));
        var parsed = parse(input);
        assertThat(parsed.type()).isEqualTo(FieldType.valueOf(type));
        assertThat(parsed.defaultValue()).isNull();
        assertThat(parsed.options()).isEmpty();
        assertThat(parsed.missingOptions()).isFalse();
        assertValid(parsed);
    }

    @Test void genericStringsAndListsRecognizeOnlyUnambiguousUploadWidgets() {
        for (String declared : List.of("STRING", "LIST")) for (String media : List.of("image", "audio", "video")) {
            var input = mapper.createObjectNode().put("fieldType", declared).put("fieldValue", "synthetic.file");
            input.set("fieldData", mapper.readTree("[[\"synthetic.file\",\"synthetic.file\"],{\"" + media + "_upload\":true}]"));
            var parsed = parse(input);
            assertThat(parsed.type()).isEqualTo(FieldType.valueOf(media.toUpperCase(java.util.Locale.ROOT)));
            assertThat(parsed.options()).isEmpty();
            assertThat(parsed.defaultValue()).isNull();
        }
        var vhs = parse("""
                {"fieldType":"STRING","fieldValue":"synthetic.mp4",
                 "fieldData":["STRING",{"vhs_path_extensions":["mp4","webm"]}]}
                """);
        assertThat(vhs.type()).isEqualTo(FieldType.VIDEO);
        assertThat(vhs.defaultValue()).isNull();
        var ambiguous = parse("""
                {"fieldType":"STRING","fieldValue":"synthetic.file",
                 "fieldData":[["synthetic.file","synthetic.file"],{"image_upload":true,"audio_upload":true}]}
                """);
        assertThat(ambiguous.type()).isEqualTo(FieldType.STRING);
        assertThat(ambiguous.options()).isEmpty();
    }

    @Test void uploadFlagsDoNotOverrideExplicitScalarTypesOrTreatFalseTextAsEnabled() {
        var scalar = parse("""
                {"fieldType":"FLOAT","fieldValue":"12","fieldData":["INT",{"image_upload":true}]}
                """);
        assertThat(scalar.type()).isEqualTo(FieldType.NUMBER);
        assertThat(scalar.defaultValue().decimalValue()).isEqualByComparingTo("12");
        var text = parse("""
                {"fieldType":"STRING","fieldValue":"plain text",
                 "fieldData":["STRING",{"image_upload":false,"audio_upload":"true"}]}
                """);
        assertThat(text.type()).isEqualTo(FieldType.STRING);
        assertThat(text.defaultValue().asText()).isEqualTo("plain text");
    }

    @Test void typedWidgetsInferGenericTypesInArrayObjectAndJsonStringRepresentations() {
        List<JsonNode> forms = List.of(
                mapper.readTree("[\"INT\",{\"min\":1,\"max\":100,\"default\":20}]"),
                mapper.readTree("{\"type\":\"INT\",\"min\":1,\"max\":100,\"default\":20}"));
        for (JsonNode form : forms) for (boolean encoded : List.of(false, true)) {
            var input = mapper.createObjectNode().put("fieldType", "STRING").put("fieldValue", "12.0");
            input.set("fieldData", encoded ? mapper.valueToTree(form.toString()) : form);
            var parsed = parse(input);
            assertThat(parsed.type()).isEqualTo(FieldType.INTEGER);
            assertThat(parsed.defaultValue().isIntegralNumber()).isTrue();
            assertThat(parsed.defaultValue().asLong()).isEqualTo(12);
            assertThat(parsed.minimum()).isEqualByComparingTo("1");
            assertThat(parsed.maximum()).isEqualByComparingTo("100");
            assertValid(parsed);
        }
    }

    @Test void missingTypesUseNativeScalarsButDoNotGuessNumericOrBooleanStrings() {
        assertThat(parse("{\"fieldValue\":12}").type()).isEqualTo(FieldType.INTEGER);
        assertThat(parse("{\"fieldType\":\"unknown\",\"fieldValue\":1.25}").type()).isEqualTo(FieldType.NUMBER);
        assertThat(parse("{\"fieldValue\":false}").type()).isEqualTo(FieldType.BOOLEAN);
        assertThat(parse("{\"fieldValue\":\"12\"}").type()).isEqualTo(FieldType.STRING);
        assertThat(parse("{\"fieldValue\":\"false\"}").type()).isEqualTo(FieldType.STRING);
        var explicit = parse("{\"fieldType\":\"STRING\",\"fieldValue\":12}");
        assertThat(explicit.type()).isEqualTo(FieldType.STRING);
        assertThat(explicit.defaultValue().asText()).isEqualTo("12");
    }

    @Test void unknownTypedWrappersDoNotBecomeEnumsOrHideUploadMarkers() {
        var unknown = parse("""
                {"fieldType":"STRING","fieldData":["UNKNOWN",{"options":["first","second"]}]}
                """);
        assertThat(unknown.type()).isEqualTo(FieldType.STRING);
        assertThat(unknown.options()).isEmpty();
        var unknownObject = parse("""
                {"fieldType":"STRING","fieldData":{"type":"UNKNOWN","options":["first","second"]}}
                """);
        assertThat(unknownObject.type()).isEqualTo(FieldType.STRING);
        assertThat(unknownObject.options()).isEmpty();
        var upload = parse("""
                {"fieldType":"STRING","fieldData":["UNKNOWN",{"value":"synthetic.png","image_upload":true}]}
                """);
        assertThat(upload.type()).isEqualTo(FieldType.IMAGE);
        assertThat(upload.options()).isEmpty();
        var mixedOptions = parse("""
                {"fieldType":"LIST","fieldData":["first",{"label":"Second","value":"second"}]}
                """);
        assertThat(mixedOptions.type()).isEqualTo(FieldType.SELECT);
        assertThat(mixedOptions.options()).extracting(option -> option.value().asText()).containsExactly("first", "second");
    }

    @Test void meaningfulZeroFalseAndEmptyDefaultsWinOverWidgetDefaults() {
        var integer = parse("{\"fieldType\":\" int \",\"fieldValue\":0,\"fieldData\":[\"INT\",{\"default\":20}]}");
        assertThat(integer.type()).isEqualTo(FieldType.INTEGER);
        assertThat(integer.defaultValue().asLong()).isZero();
        assertThat(parse("{\"fieldType\":\"BOOLEAN\",\"fieldValue\":false,\"fieldData\":[\"BOOLEAN\",{\"default\":true}]}")
                .defaultValue().asBoolean()).isFalse();
        assertThat(parse("{\"fieldType\":\"STRING\",\"fieldValue\":\"\",\"fieldData\":[\"STRING\",{\"default\":\"fallback\"}]}")
                .defaultValue().asText()).isEmpty();
    }

    @Test void invalidCurrentValuesFallBackToValidatedWidgetDefaults() {
        var integer = parse("""
                {"fieldType":"INT","fieldValue":"1.5","fieldData":["INT",{"default":"20.0","min":2,"max":30}]}
                """);
        assertThat(integer.defaultValue().asLong()).isEqualTo(20);
        assertThat(parse("{\"fieldType\":\"BOOLEAN\",\"fieldValue\":\"invalid\",\"fieldData\":[\"BOOLEAN\",{\"default\":\"False\"}]}")
                .defaultValue().asBoolean()).isFalse();
        assertThat(parse("{\"fieldType\":\"FLOAT\",\"fieldValue\":null,\"fieldData\":[\"FLOAT\",{\"default\":1.5}]}")
                .defaultValue().decimalValue()).isEqualByComparingTo("1.5");
        var outOfBounds = parse("{\"fieldType\":\"FLOAT\",\"fieldValue\":12,\"fieldData\":[\"FLOAT\",{\"min\":0,\"max\":10,\"default\":5}]}");
        assertThat(outOfBounds.defaultValue().decimalValue()).isEqualByComparingTo("5");
        assertValid(integer);
        assertValid(outOfBounds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "1.1", "1 trailing", "NaN", "Infinity", "1e9999"})
    void integersRejectOverflowFractionsAndInvalidNumbersWithoutRejectingTheField(String value) {
        var input = mapper.createObjectNode().put("fieldType", "INTEGER").put("fieldValue", value);
        var parsed = parse(input);
        assertThat(parsed.defaultValue()).isNull();
        assertValid(parsed);
    }

    @Test void defaultsRejectContainersAndBooleanNumbersWhileSupportingBooleanCase() {
        for (String value : List.of("{}", "[]", "1", "0", "\"1\"")) {
            assertThat(parse("{\"fieldType\":\"BOOLEAN\",\"fieldValue\":" + value + "}").defaultValue()).isNull();
        }
        assertThat(parse("{\"fieldType\":\"BOOL\",\"fieldValue\":\"True\"}").defaultValue().asBoolean()).isTrue();
        assertThat(parse("{\"fieldType\":\"BOOL\",\"fieldValue\":\"False\"}").defaultValue().asBoolean()).isFalse();
        assertThat(parse("{\"fieldType\":\"FLOAT\",\"fieldValue\":{}}").defaultValue()).isNull();
        assertThat(parse("{\"fieldType\":\"STRING\",\"fieldValue\":[]}").defaultValue()).isNull();
    }

    @Test void dropdownRepresentationsKeepTypedOptionsAndCurrentDefaults() {
        for (String data : List.of("[\"COMBO\",{\"options\":[1,\"1\",false],\"default\":false}]",
                "[[1,\"1\",false],{\"default\":false}]", "{\"options\":[1,\"1\",false],\"default\":false}")) {
            var input = mapper.createObjectNode().put("fieldType", "LIST").put("fieldValue", "1");
            input.set("fieldData", mapper.readTree(data));
            var parsed = parse(input);
            assertThat(parsed.type()).isEqualTo(FieldType.SELECT);
            assertThat(parsed.options()).extracting(option -> option.value().getNodeType().name())
                    .containsExactly("NUMBER", "STRING", "BOOLEAN");
            assertThat(parsed.defaultValue().isTextual()).isTrue();
            assertThat(parsed.defaultValue().asText()).isEqualTo("1");
            assertValid(parsed);
        }
    }

    @Test void dropdownNumericTextFallsBackOnlyAfterAnExactTypedMatch() {
        assertThat(parse("{\"fieldType\":\"LIST\",\"fieldValue\":\"1.0\",\"fieldData\":[1,\"1.0\"]}").defaultValue().isTextual()).isTrue();
        assertThat(parse("{\"fieldType\":\"LIST\",\"fieldValue\":\"1.0\",\"fieldData\":[1,2]}").defaultValue().asInt()).isEqualTo(1);
        assertThat(parse("{\"fieldType\":\"LIST\",\"fieldValue\":1,\"fieldData\":[\"1\",\"2\"]}").defaultValue()).isNull();
        assertThat(parse("{\"fieldType\":\"LIST\",\"fieldValue\":\"false\",\"fieldData\":[true,false]}").defaultValue()).isNull();
        assertThat(parse("{\"fieldType\":\"LIST\",\"fieldValue\":\"missing\",\"fieldData\":[\"COMBO\",{\"options\":[\"first\",\"second\"],\"default\":\"second\"}]}")
                .defaultValue().asText()).isEqualTo("second");
    }

    @Test void realDropdownDuplicatesRemainForPublicationValidationAndDoNotCollapseLabels() {
        var parsed = parse("""
                {"fieldType":"LIST","fieldValue":1,"fieldData":{"options":[
                  {"label":"same label","value":1},{"label":"same label","value":"1"},
                  {"label":"numeric duplicate","value":1.0}]}}
                """);
        assertThat(parsed.options()).hasSize(3);
        assertThat(parsed.options()).extracting(RunningHubDefinition.Option::label)
                .containsExactly("same label", "same label", "numeric duplicate");
        assertThatThrownBy(() -> assertValid(parsed)).hasMessageContaining("重复");
    }

    @Test void dropdownOptionCountUsesRawEntriesAndMalformedListsKeepTheFallbackSignal() {
        var input = mapper.createObjectNode().put("fieldType", "LIST").put("fieldValue", "same");
        var choices = input.putArray("fieldData");
        for (int index = 0; index < RunningHubDefinition.MAX_OPTIONS; index++) choices.add("same");
        assertThat(parse(input).options()).hasSize(RunningHubDefinition.MAX_OPTIONS);
        choices.add("same");
        assertThat(parse(input).options()).isEmpty();
        assertThat(parse(input).type()).isEqualTo(FieldType.STRING);
        assertThat(parse(input).missingOptions()).isTrue();
        for (String data : List.of("null", "{}", "[null]", "[{\"label\":\"no value\"}]", "\"not JSON\"")) {
            var malformed = parse("{\"fieldType\":\"LIST\",\"fieldData\":" + data + "}");
            assertThat(malformed.missingOptions()).isTrue();
            assertThat(malformed.type()).isEqualTo(FieldType.STRING);
        }
    }

    @Test void invalidOrContradictoryConstraintsDoNotRejectDiscovery() {
        var reversed = parse("{\"fieldType\":\"FLOAT\",\"fieldValue\":12,\"fieldData\":[\"FLOAT\",{\"min\":20,\"max\":1}]}");
        assertThat(reversed.minimum()).isNull();
        assertThat(reversed.maximum()).isNull();
        assertThat(reversed.defaultValue().decimalValue()).isEqualByComparingTo("12");
        var invalid = parse("{\"fieldType\":\"FLOAT\",\"fieldValue\":1.5,\"fieldData\":[\"FLOAT\",{\"min\":\"NaN\",\"max\":\"1e9999\"}]}");
        assertThat(invalid.minimum()).isNull();
        assertThat(invalid.maximum()).isNull();
        assertValid(reversed);
        assertValid(invalid);
    }

    @Test void stringLimitsAndRequiredUseOnlyValidExplicitMetadata() {
        var limited = parse("""
                {"fieldType":"STRING","fieldValue":"too long","required":false,
                 "fieldData":["STRING",{"maxLength":4,"required":true,"default":"okay"}]}
                """);
        assertThat(limited.maxLength()).isEqualTo(4);
        assertThat(limited.defaultValue().asText()).isEqualTo("okay");
        assertThat(limited.required()).isFalse();
        var required = parse("{\"fieldType\":\"STRING\",\"required\":\"true\",\"fieldData\":[\"STRING\",{\"required\":true,\"maxLength\":20001}]}");
        assertThat(required.required()).isTrue();
        assertThat(required.maxLength()).isNull();
        assertThat(parse("{\"fieldType\":\"STRING\",\"required\":\"true\"}").required()).isFalse();
        assertValid(limited);
    }

    @Test void parsingLeavesTheCompleteSourceUnchangedAndUsesNoDescriptionDefaults() {
        ObjectNode source = (ObjectNode) mapper.readTree("""
                {"nodeName":"PrimitiveFloat","description":"时长默认 6 秒","fieldType":"FLOAT",
                 "fieldValue":"12"}
                """);
        source.put("fieldData", "[\"FLOAT\",{\"min\":0.1,\"max\":16,\"default\":1,\"step\":0.1}]");
        var before = source.deepCopy();
        var parsed = parse(source);
        assertThat(parsed.type()).isEqualTo(FieldType.NUMBER);
        assertThat(parsed.defaultValue().decimalValue()).isEqualByComparingTo("12");
        assertThat(source).isEqualTo(before);
        assertValid(parsed);
    }

    private RunningHubInputMetadata.Parsed parse(String json) { return parse(mapper.readTree(json)); }
    private RunningHubInputMetadata.Parsed parse(JsonNode input) { return RunningHubInputMetadata.parse(mapper, input); }

    private void assertValid(RunningHubInputMetadata.Parsed parsed) {
        RunningHubDefinition.validateFields(List.of(new RunningHubDefinition.Field("input1", "Synthetic parameter", null,
                parsed.type(), parsed.required(), parsed.defaultValue(), parsed.minimum(), parsed.maximum(), parsed.maxLength(),
                parsed.options(), false, "1", "value", RunningHubDefinition.Source.PARAMETER,
                RunningHubDefinition.Encoding.NATIVE, null, null)));
    }
}
