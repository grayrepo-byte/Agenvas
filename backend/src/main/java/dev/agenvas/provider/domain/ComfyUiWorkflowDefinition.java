package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** An administrator-published graph. Callers can supply values only at its declared inputs. */
public record ComfyUiWorkflowDefinition(int schemaVersion, JsonNode graph, List<Binding> bindings,
        Output output, int width, int height, int minimumSeconds, int maximumSeconds,
        int fps, int frameMultiple, int frameOffset, List<RunningHubDefinition.Field> parameters) {
    public static final String SETTINGS_KEY = "comfyWorkflow";
    public static final String PUBLIC_INPUTS_KEY = "comfyInputs";
    public static final String PROMPT_KEY = "prompt";
    public static final String DURATION_KEY = "durationSeconds";
    public static final String REFERENCE_KEY_PREFIX = "reference_";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 256 * 1024;
    public static final int MAX_NODES = 256;
    public static final int MAX_BINDINGS = 128;
    public static final int MAX_REFERENCES = 14;
    public static final int MAX_SECONDS = 30;
    public static final int MAX_SIDE = 4096;
    private static final int MAX_INPUTS = 128;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_FPS = 120;
    private static final int MAX_FRAME_MULTIPLE = 64;
    private static final int PIXEL_ALIGNMENT = 8;
    private static final int MAX_INPUT_LABEL_LENGTH = 160;
    private static final Set<String> REQUIRED_FIELDS = Set.of("schemaVersion", "graph", "bindings", "output",
            "width", "height", "minimumSeconds", "maximumSeconds", "fps", "frameMultiple", "frameOffset");
    private static final Set<String> FIELDS = java.util.stream.Stream.concat(REQUIRED_FIELDS.stream(),
            java.util.stream.Stream.of("parameters")).collect(java.util.stream.Collectors.toUnmodifiableSet());

    public enum Source { PROMPT, NEGATIVE_PROMPT, SEED, WIDTH, HEIGHT, REFERENCE_IMAGE,
        DURATION_SECONDS, FRAME_COUNT, FPS, BATCH_SIZE }
    public record Binding(String nodeId, String inputName, Source source, Integer referenceIndex) {}
    public record Output(String nodeId, String field) {}
    public record Dimensions(int width, int height) {}

    public static boolean configured(JsonNode settings) { return settings.has(SETTINGS_KEY); }

    /** Accept the API export or a /prompt request envelope; never a UI-format workflow. */
    public static ObjectNode importGraph(ObjectMapper mapper, String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw invalid("graph");
        JsonNode source;
        try { source = mapper.readTree(json); }
        catch (RuntimeException malformed) { throw invalid("graph"); }
        if (source != null && source.has("prompt")) source = source.get("prompt");
        return validateGraph(mapper, source);
    }

    public static ComfyUiWorkflowDefinition parse(ObjectMapper mapper, JsonNode source, Task.Kind kind) {
        if (source == null || !source.isObject() || !FIELDS.containsAll(source.propertyNames())
                || !source.propertyNames().containsAll(REQUIRED_FIELDS)
                || source.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES
                || integer(source, "schemaVersion", SCHEMA_VERSION, SCHEMA_VERSION) != SCHEMA_VERSION) throw invalid("definition");
        ObjectNode graph = validateGraph(mapper, source.get("graph"));
        JsonNode rawOutput = source.path("output");
        if (!rawOutput.isObject() || !Set.of("nodeId", "field").equals(rawOutput.propertyNames())) throw invalid("output");
        if (!rawOutput.path("nodeId").isTextual() || !rawOutput.path("field").isTextual()) throw invalid("output");
        String outputNode = rawOutput.path("nodeId").asText();
        String outputField = rawOutput.path("field").asText();
        if (!graph.has(outputNode) || !(kind == Task.Kind.IMAGE_GENERATION
                ? Set.of("images") : Set.of("images", "gifs", "videos")).contains(outputField)) throw invalid("output");
        Set<String> ancestors = new HashSet<>();
        visit(graph, outputNode, new HashSet<>(), ancestors);
        JsonNode rawBindings = source.path("bindings");
        if (!rawBindings.isArray() || rawBindings.size() > MAX_BINDINGS) throw invalid("bindings");
        List<Binding> bindings = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        Set<Integer> references = new HashSet<>();
        Set<Source> sources = new HashSet<>();
        for (JsonNode raw : rawBindings) {
            if (!raw.isObject() || !Set.of("nodeId", "inputName", "source", "referenceIndex").containsAll(raw.propertyNames())
                    || !raw.has("nodeId") || !raw.has("inputName") || !raw.has("source")) throw invalid("bindings");
            if (!raw.path("nodeId").isTextual() || !raw.path("inputName").isTextual() || !raw.path("source").isTextual()) throw invalid("bindings");
            String nodeId = raw.path("nodeId").asText();
            String inputName = raw.path("inputName").asText();
            Source bindingSource;
            try { bindingSource = Source.valueOf(raw.path("source").asText()); }
            catch (IllegalArgumentException unknown) { throw invalid("bindings"); }
            JsonNode input = graph.path(nodeId).path("inputs").get(inputName);
            boolean text = Set.of(Source.PROMPT, Source.NEGATIVE_PROMPT, Source.REFERENCE_IMAGE).contains(bindingSource);
            if (input == null || (text ? !input.isTextual() : !input.isNumber())
                    || !ancestors.contains(nodeId) || !targets.add(nodeId + ":" + inputName)) throw invalid("bindings");
            Integer index = null;
            if (bindingSource == Source.REFERENCE_IMAGE) {
                index = integer(raw, "referenceIndex", 0, MAX_REFERENCES - 1);
                references.add(index);
            } else if (raw.hasNonNull("referenceIndex")) throw invalid("bindings");
            if (kind == Task.Kind.IMAGE_GENERATION && Set.of(Source.DURATION_SECONDS, Source.FRAME_COUNT, Source.FPS).contains(bindingSource)) throw invalid("bindings");
            bindings.add(new Binding(nodeId, inputName, bindingSource, index));
            sources.add(bindingSource);
        }
        if (sources.contains(Source.WIDTH) != sources.contains(Source.HEIGHT)) throw invalid("bindings");
        List<RunningHubDefinition.Field> parameters = parseParameters(mapper, source.get("parameters"), graph, ancestors, targets);
        for (int index = 0; index < references.size(); index++) if (!references.contains(index)) throw invalid("references");
        int minimum = integer(source, "minimumSeconds", kind == Task.Kind.VIDEO_GENERATION ? 1 : 0, MAX_SECONDS);
        int maximum = integer(source, "maximumSeconds", minimum, kind == Task.Kind.VIDEO_GENERATION ? MAX_SECONDS : 0);
        if (kind == Task.Kind.VIDEO_GENERATION && !sources.contains(Source.DURATION_SECONDS) && !sources.contains(Source.FRAME_COUNT)) throw invalid("duration");
        if (sources.contains(Source.FRAME_COUNT) && !sources.contains(Source.FPS)) throw invalid("duration");
        int multiple = integer(source, "frameMultiple", 1, MAX_FRAME_MULTIPLE);
        return new ComfyUiWorkflowDefinition(SCHEMA_VERSION, graph, List.copyOf(bindings), new Output(outputNode, outputField),
                integer(source, "width", PIXEL_ALIGNMENT, MAX_SIDE), integer(source, "height", PIXEL_ALIGNMENT, MAX_SIDE),
                minimum, maximum, integer(source, "fps", 1, MAX_FPS), multiple, integer(source, "frameOffset", 0, multiple - 1), parameters);
    }

    private static List<RunningHubDefinition.Field> parseParameters(ObjectMapper mapper, JsonNode raw,
            JsonNode graph, Set<String> ancestors, Set<String> targets) {
        if (raw == null || raw.isNull()) return List.of();
        List<RunningHubDefinition.Field> parameters = new ArrayList<>();
        for (var field : RunningHubDefinition.parseFields(mapper, raw)) {
            if (field.nodeId() == null || field.fieldName() == null) throw invalid("parameters");
            JsonNode target = graph.path(field.nodeId()).path("inputs").get(field.fieldName());
            if (field.media() || field.effectiveSource() != RunningHubDefinition.Source.PARAMETER
                    || field.effectiveEncoding() != RunningHubDefinition.Encoding.NATIVE || field.resourceFormat() != null
                    || field.key() == null || Set.of(PROMPT_KEY, DURATION_KEY).contains(field.key())
                    || field.key().startsWith(REFERENCE_KEY_PREFIX)
                    || !ancestors.contains(field.nodeId()) || target == null || !scalarCompatible(field.type(), target)
                    || !targets.add(field.nodeId() + ":" + field.fieldName())) throw invalid("parameters");
            JsonNode defaultValue = field.defaultValue() == null || field.defaultValue().isNull() ? target.deepCopy() : field.defaultValue();
            if (!sameScalarKind(target, defaultValue) || field.options() != null && field.options().stream()
                    .anyMatch(option -> option == null || !sameScalarKind(target, option.value()))) throw invalid("parameters");
            parameters.add(new RunningHubDefinition.Field(field.key(), field.label(), field.description(), field.type(),
                    field.required(), defaultValue, field.minimum(), field.maximum(), field.maxLength(), field.options(),
                    field.advanced(), field.nodeId(), field.fieldName(), field.source(), field.encoding(), field.resourceFormat(), field.enabledWhen()));
        }
        RunningHubDefinition.validateFields(parameters);
        return List.copyOf(parameters);
    }

    private static boolean scalarCompatible(RunningHubDefinition.FieldType type, JsonNode value) {
        if (type == null) return false;
        return switch (type) {
            case STRING -> value.isTextual();
            case BOOLEAN -> value.isBoolean();
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case SELECT -> value.isTextual() || value.isNumber() || value.isBoolean();
            default -> false;
        };
    }

    private static boolean sameScalarKind(JsonNode target, JsonNode value) {
        return value != null && (target.isTextual() && value.isTextual()
                || target.isNumber() && value.isNumber() || target.isBoolean() && value.isBoolean());
    }

    private static ObjectNode validateGraph(ObjectMapper mapper, JsonNode source) {
        if (source == null || !source.isObject() || source.isEmpty() || source.size() > MAX_NODES
                || source.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw invalid("graph");
        ObjectNode graph = mapper.createObjectNode();
        for (String id : source.propertyNames().stream().sorted().toList()) {
            JsonNode node = source.path(id);
            if (!id.matches("[0-9]{1,8}") || !node.isObject()
                    || !Set.of("class_type", "inputs", "_meta").containsAll(node.propertyNames())
                    || !node.path("class_type").isTextual() || node.path("class_type").asText().isBlank()
                    || node.path("class_type").asText().length() > 160
                    || !node.path("inputs").isObject() || node.path("inputs").size() > MAX_INPUTS) throw invalid("graph");
            ObjectNode normalized = graph.putObject(id);
            normalized.set("class_type", node.get("class_type"));
            ObjectNode inputs = normalized.putObject("inputs");
            for (String name : node.path("inputs").propertyNames().stream().sorted().toList()) {
                if (name.isBlank() || name.length() > 160) throw invalid("graph");
                JsonNode value = node.path("inputs").get(name);
                validateValue(value, 0);
                if (isLink(value) && (!source.has(value.get(0).asText()) || !value.get(1).canConvertToInt() || value.get(1).intValue() < 0)) throw invalid("graph");
                inputs.set(name, value.deepCopy());
            }
            if (node.path("_meta").path("title").isTextual()) {
                String title = node.path("_meta").path("title").asText();
                if (title.length() > 160) throw invalid("graph");
                normalized.putObject("_meta").put("title", title);
            }
        }
        Set<String> complete = new HashSet<>();
        for (String id : graph.propertyNames()) visit(graph, id, new HashSet<>(), complete);
        return graph;
    }

    private static void validateValue(JsonNode value, int depth) {
        if (depth > MAX_DEPTH) throw invalid("graph");
        if ((value.isArray() || value.isObject())) for (JsonNode child : value) validateValue(child, depth + 1);
    }

    private static boolean isLink(JsonNode value) {
        return value.isArray() && value.size() == 2 && value.get(0).isTextual() && value.get(1).isIntegralNumber();
    }

    private static void visit(JsonNode graph, String id, Set<String> visiting, Set<String> complete) {
        if (complete.contains(id)) return;
        if (!visiting.add(id)) throw invalid("graph");
        for (JsonNode input : graph.path(id).path("inputs")) if (isLink(input)) visit(graph, input.get(0).asText(), visiting, complete);
        visiting.remove(id);
        complete.add(id);
    }

    public int referenceCount() {
        return bindings.stream().filter(binding -> binding.source() == Source.REFERENCE_IMAGE)
                .mapToInt(binding -> binding.referenceIndex() + 1).max().orElse(0);
    }

    public void requireReferences(int count) {
        if (count != referenceCount()) throw invalid("references");
    }

    public boolean maps(Source source) { return bindings.stream().anyMatch(binding -> binding.source() == source); }

    /** Public metadata contains declared defaults only, never graph literals or fixed model inputs. */
    public List<RunningHubDefinition.Field> inputs() {
        List<RunningHubDefinition.Field> result = new ArrayList<>(parameters);
        bindings.stream().filter(binding -> binding.source() == Source.PROMPT).findFirst().ifPresent(binding ->
                result.add(input(PROMPT_KEY, binding, RunningHubDefinition.FieldType.STRING,
                        RunningHubDefinition.Source.PROMPT, null, null, MediaAdapterRegistry.COMFY_MAX_PROMPT_LENGTH)));
        for (int index = 0; index < referenceCount(); index++) {
            int referenceIndex = index;
            Binding binding = bindings.stream().filter(candidate -> candidate.source() == Source.REFERENCE_IMAGE
                    && candidate.referenceIndex() == referenceIndex).findFirst().orElseThrow();
            result.add(input(REFERENCE_KEY_PREFIX + index, binding, RunningHubDefinition.FieldType.IMAGE,
                    RunningHubDefinition.Source.PARAMETER, null, null, null));
        }
        bindings.stream().filter(binding -> Set.of(Source.DURATION_SECONDS, Source.FRAME_COUNT).contains(binding.source()))
                .findFirst().ifPresent(binding -> result.add(input(DURATION_KEY, binding, RunningHubDefinition.FieldType.INTEGER,
                        RunningHubDefinition.Source.DURATION_SECONDS, minimumSeconds, maximumSeconds, null)));
        return List.copyOf(result);
    }

    private RunningHubDefinition.Field input(String key, Binding binding, RunningHubDefinition.FieldType type,
            RunningHubDefinition.Source source, Integer minimum, Integer maximum, Integer maxLength) {
        String title = graph.path(binding.nodeId()).path("_meta").path("title").asText("");
        String label = title.isBlank() ? binding.inputName() : title + " · " + binding.inputName();
        if (label.length() > MAX_INPUT_LABEL_LENGTH) label = binding.inputName();
        return new RunningHubDefinition.Field(key, label, null, type, true, null,
                minimum == null ? null : java.math.BigDecimal.valueOf(minimum),
                maximum == null ? null : java.math.BigDecimal.valueOf(maximum), maxLength, null, false,
                binding.nodeId(), binding.inputName(), source, RunningHubDefinition.Encoding.NATIVE, null, null);
    }

    /** Preserve legacy positional drafts while new drafts can assign the same image to several named slots. */
    public ObjectNode values(ObjectMapper mapper, JsonNode cardParameters, String prompt, Integer seconds,
            List<UUID> positionalVersions, boolean executing) {
        JsonNode supplied = cardParameters.path(RunningHubDefinition.VALUES_PROPERTY);
        if (!supplied.isMissingNode() && !supplied.isObject()) throw invalid("parameters");
        ObjectNode raw = supplied.isMissingNode() ? mapper.createObjectNode() : (ObjectNode) supplied.deepCopy();
        if (raw.has(PROMPT_KEY) || raw.has(DURATION_KEY)) throw invalid("parameters");
        if (supplied.isMissingNode()) {
            for (int index = 0; index < Math.min(referenceCount(), positionalVersions.size()); index++)
                raw.put(REFERENCE_KEY_PREFIX + index, positionalVersions.get(index).toString());
        }
        ObjectNode effective = RunningHubDefinition.inputValues(mapper, inputs(), raw, prompt, seconds, executing);
        effective.remove(PROMPT_KEY);
        effective.remove(DURATION_KEY);
        return effective;
    }

    /** Standard image/video controls retain their existing validation outside the named input contract. */
    public static ObjectNode standardParameters(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) throw invalid("parameters");
        ObjectNode standard = (ObjectNode) parameters.deepCopy();
        standard.remove(RunningHubDefinition.VALUES_PROPERTY);
        return standard;
    }

    public MediaAdapterRegistry.Declaration declaration(Task.Kind kind) {
        boolean video = kind == Task.Kind.VIDEO_GENERATION;
        Set<String> modes = video ? Set.of(referenceCount() == 0 ? "TEXT" : "GENERAL_REFERENCE") : Set.of();
        return new MediaAdapterRegistry.Declaration(MediaPlatform.COMFYUI, kind, minimumSeconds, maximumSeconds,
                true, referenceCount(), modes, video ? modes.iterator().next() : null, false,
                !video ? maps(Source.WIDTH) ? Set.of("AUTO", "1:1", "9:16", "16:9") : Set.of("AUTO") : Set.of(),
                !video ? Set.of("1K") : Set.of(), Set.of(), false, false, 0, 0);
    }

    /** Preserve the configured pixel scale while applying an explicitly frozen aspect ratio. */
    public Dimensions dimensions(String ratio) {
        if (!maps(Source.WIDTH) || "AUTO".equals(ratio)) return new Dimensions(width, height);
        int longest = Math.max(width, height);
        return switch (ratio) {
            case "1:1" -> new Dimensions(longest, longest);
            case "16:9" -> new Dimensions(longest, aligned(longest * 9 / 16));
            case "9:16" -> new Dimensions(aligned(longest * 9 / 16), longest);
            default -> throw invalid("dimensions");
        };
    }

    private static int aligned(int pixels) { return Math.max(PIXEL_ALIGNMENT, pixels / PIXEL_ALIGNMENT * PIXEL_ALIGNMENT); }

    /** Render a fresh copy: neither the published graph nor another task's inputs can change. */
    public ObjectNode render(ObjectMapper mapper, String prompt, String negativePrompt, long seed,
            Dimensions dimensions, int seconds, List<String> uploadedImages) {
        return render(mapper, prompt, negativePrompt, seed, dimensions, seconds, uploadedImages, mapper.createObjectNode());
    }

    public ObjectNode render(ObjectMapper mapper, String prompt, String negativePrompt, long seed,
            Dimensions dimensions, int seconds, List<String> uploadedImages, JsonNode frozenValues) {
        requireReferences(uploadedImages.size());
        if (maximumSeconds > 0 && (seconds < minimumSeconds || seconds > maximumSeconds)) throw invalid("duration");
        ObjectNode rendered = (ObjectNode) graph.deepCopy();
        for (Binding binding : bindings) {
            ObjectNode inputs = (ObjectNode) rendered.path(binding.nodeId()).path("inputs");
            JsonNode value = switch (binding.source()) {
                case PROMPT -> mapper.valueToTree(prompt);
                case NEGATIVE_PROMPT -> mapper.valueToTree(negativePrompt);
                case SEED -> mapper.valueToTree(seed);
                case WIDTH -> mapper.valueToTree(dimensions.width());
                case HEIGHT -> mapper.valueToTree(dimensions.height());
                case REFERENCE_IMAGE -> mapper.valueToTree(uploadedImages.get(binding.referenceIndex()));
                case DURATION_SECONDS -> mapper.valueToTree(seconds);
                case FRAME_COUNT -> mapper.valueToTree(seconds * fps / frameMultiple * frameMultiple + frameOffset);
                case FPS -> mapper.valueToTree(fps);
                case BATCH_SIZE -> mapper.valueToTree(1);
            };
            inputs.set(binding.inputName(), value);
        }
        List<RunningHubDefinition.Field> scalarFields = parameters;
        ObjectNode supplied = mapper.createObjectNode();
        for (var field : scalarFields) if (frozenValues.has(field.key())) supplied.set(field.key(), frozenValues.get(field.key()));
        ObjectNode resolved = RunningHubDefinition.inputValues(mapper, scalarFields, supplied, "", null, true);
        for (var field : scalarFields) if (resolved.has(field.key()))
            ((ObjectNode) rendered.path(field.nodeId()).path("inputs")).set(field.fieldName(), resolved.get(field.key()));
        return rendered;
    }

    private static int integer(JsonNode source, String key, int minimum, int maximum) {
        JsonNode value = source.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum) throw invalid(key);
        return value.intValue();
    }

    private static ApiProblemException invalid(String field) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "COMFYUI_WORKFLOW_INVALID",
                ApiMessage.of("api.comfy-workflow.title"), ApiMessage.of("api.comfy-workflow.invalid", field), false);
    }
}
