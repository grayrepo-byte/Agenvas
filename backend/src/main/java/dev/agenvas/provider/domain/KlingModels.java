package dev.agenvas.provider.domain;

import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Compiled model contracts. Creator fields contain neither graph targets nor provider URLs. */
public final class KlingModels {
    public static final String IMAGE_ADAPTER_ID = "KLING_IMAGE";
    public static final String VIDEO_ADAPTER_ID = "KLING_VIDEO";
    public static final String PROTOCOL_ID = "KLING";
    public static final String VALUES_PROPERTY = "dynamicValues";
    public enum Operation { GENERATE, MULTI_IMAGE, EXPAND, SUBJECT_COMPLETION, MOTION_CONTROL }
    public record Profile(String model, Operation operation, Task.Kind kind) {}
    public record Field(String key, String label, String description, RunningHubDefinition.FieldType type,
            boolean required, JsonNode defaultValue, BigDecimal minimum, BigDecimal maximum,
            Integer maxLength, List<RunningHubDefinition.Option> options, boolean advanced,
            RunningHubDefinition.Source source, RunningHubDefinition.Condition enabledWhen) {
        public boolean media() { return type == RunningHubDefinition.FieldType.IMAGE || type == RunningHubDefinition.FieldType.VIDEO; }
        public RunningHubDefinition.Source effectiveSource() { return source == null ? RunningHubDefinition.Source.PARAMETER : source; }
    }
    private static final JsonNodeFactory N = JsonNodeFactory.instance;
    private static final Set<String> IMAGE_MODELS = Set.of("kling-v3", "kling-v2-1", "kling-image-o1", "kling-v3-omni");
    private static final Set<String> VIDEO_MODELS = Set.of("kling-3.0-turbo", "kling-3.0", "kling-3.0-omni", "kling-o1", "kling-2.6", "kling-2.5-turbo");
    private static final List<String> RATIOS = List.of("16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3", "21:9");
    private static final List<String> VIDEO_RATIOS = List.of("16:9", "9:16", "1:1");
    private KlingModels() {}
    public static boolean adapter(String adapterId) { return Set.of(IMAGE_ADAPTER_ID, VIDEO_ADAPTER_ID).contains(adapterId); }

    public static Profile resolve(JsonNode settings, Task.Kind kind) {
        String operation = settings.path("operation").asText("GENERATE");
        String fallback = Set.of("EXPAND", "SUBJECT_COMPLETION").contains(operation) ? ""
                : kind == Task.Kind.IMAGE_GENERATION ? "kling-v3" : "kling-3.0";
        JsonNode configuredModel = settings.path("model");
        if (!configuredModel.isMissingNode() && !configuredModel.isNull() && !configuredModel.isTextual()) throw KlingProtocol.invalid();
        String model = configuredModel.asText(fallback).trim();
        return resolve(model.isEmpty() ? fallback : model, operation, kind);
    }

    public static Profile resolve(String model, String operation, Task.Kind kind) {
        Operation op;
        try { op = Operation.valueOf(operation); } catch (RuntimeException invalid) { throw KlingProtocol.invalid(); }
        if (kind == Task.Kind.IMAGE_GENERATION) {
            if (op == Operation.EXPAND || op == Operation.SUBJECT_COMPLETION) {
                if (model != null && !model.isBlank()) throw KlingProtocol.invalid();
                return new Profile("", op, kind);
            }
            if (!IMAGE_MODELS.contains(model) || op != Operation.GENERATE && op != Operation.MULTI_IMAGE
                    || op == Operation.MULTI_IMAGE && !"kling-v2-1".equals(model)) throw KlingProtocol.invalid();
        } else if (kind == Task.Kind.VIDEO_GENERATION) {
            if (!VIDEO_MODELS.contains(model) || op != Operation.GENERATE && op != Operation.MOTION_CONTROL
                    || op == Operation.MOTION_CONTROL && !Set.of("kling-3.0", "kling-2.6").contains(model)) throw KlingProtocol.invalid();
        } else throw KlingProtocol.invalid();
        return new Profile(model, op, kind);
    }

    public static List<Profile> profiles(Task.Kind kind) {
        var profiles = new ArrayList<Profile>();
        for (String model : kind == Task.Kind.IMAGE_GENERATION
                ? List.of("kling-v3", "kling-v3-omni", "kling-image-o1", "kling-v2-1")
                : List.of("kling-3.0-turbo", "kling-3.0", "kling-3.0-omni", "kling-o1", "kling-2.6", "kling-2.5-turbo"))
            profiles.add(resolve(model, "GENERATE", kind));
        if (kind == Task.Kind.IMAGE_GENERATION) {
            profiles.add(resolve("kling-v2-1", "MULTI_IMAGE", kind));
            profiles.add(resolve("", "EXPAND", kind)); profiles.add(resolve("", "SUBJECT_COMPLETION", kind));
        } else {
            profiles.add(resolve("kling-3.0", "MOTION_CONTROL", kind)); profiles.add(resolve("kling-2.6", "MOTION_CONTROL", kind));
        }
        return List.copyOf(profiles);
    }
    public static List<Profile> profiles() {
        var result = new ArrayList<>(profiles(Task.Kind.IMAGE_GENERATION));
        result.addAll(profiles(Task.Kind.VIDEO_GENERATION));
        return List.copyOf(result);
    }

    public static boolean newVideo(Profile profile) { return profile.kind() == Task.Kind.VIDEO_GENERATION; }
    public static boolean omni(Profile p) { return Set.of("kling-v3-omni", "kling-image-o1", "kling-3.0-omni", "kling-o1").contains(p.model()); }
    public static boolean motion(Profile p) { return p.operation() == Operation.MOTION_CONTROL; }
    private static boolean v3(Profile p) { return Set.of("kling-3.0", "kling-3.0-omni").contains(p.model()); }
    private static boolean oldVideo(Profile p) { return Set.of("kling-2.6", "kling-2.5-turbo").contains(p.model()); }

    public static MediaAdapterRegistry.Declaration declaration(Profile p) {
        int images = inputs(p).stream().filter(f -> f.type() == RunningHubDefinition.FieldType.IMAGE).toList().size();
        int videos = inputs(p).stream().anyMatch(f -> f.type() == RunningHubDefinition.FieldType.VIDEO) ? 1 : 0;
        boolean video = newVideo(p);
        if (video && omni(p)) images = Math.min(images, 7);
        return new MediaAdapterRegistry.Declaration(MediaPlatform.KLING, p.kind(), video ? minimumSeconds(p) : 0,
                video ? maximumSeconds(p) : 0, true, images, video ? Set.of("TEXT", "GENERAL_REFERENCE") : Set.of(),
                video ? "GENERAL_REFERENCE" : null, false,
                Set.of(), Set.of(), Set.of(), false, false, 0, videos);
    }

    public static int minimumSeconds(Profile p) { return oldVideo(p) && !motion(p) ? 5 : 3; }
    public static int maximumSeconds(Profile p) { return motion(p) ? 30 : oldVideo(p) || "kling-o1".equals(p.model()) ? 10 : 15; }

    public static List<Field> inputs(Profile p) {
        var f = new ArrayList<Field>();
        if (p.operation() != Operation.SUBJECT_COMPLETION) f.add(text("prompt", "Prompt", null,
                p.operation() != Operation.EXPAND && p.operation() != Operation.MULTI_IMAGE && !motion(p),
                newVideo(p) && (v3(p) || "kling-3.0-turbo".equals(p.model())) && !motion(p) ? 3072 : 2500,
                false, RunningHubDefinition.Source.PROMPT));
        if (!newVideo(p)) imageFields(p, f); else videoFields(p, f);
        if (p.operation() != Operation.SUBJECT_COMPLETION) f.add(bool("watermark", "Watermarked companion", false));
        return List.copyOf(f);
    }

    private static void imageFields(Profile p, List<Field> f) {
        if (p.operation() == Operation.SUBJECT_COMPLETION) { f.add(media("image_1", "Frontal subject image", true, false)); return; }
        if (p.operation() == Operation.EXPAND) {
            f.add(media("image_1", "Original image", true, false));
            for (String side : List.of("up", "down", "left", "right"))
                f.add(number(side + "_expansion_ratio", side + " expansion", 0, 2, N.numberNode(0), false, true));
        } else if (p.operation() == Operation.MULTI_IMAGE) {
            for (int i = 1; i <= 4; i++) f.add(media("image_" + i, "Subject image " + i, i == 1, false));
            f.add(media("scene_image", "Scene image", false, false)); f.add(media("style_image", "Style image", false, false));
            f.add(select("aspect_ratio", "Aspect ratio", RATIOS, "16:9"));
        } else if (omni(p)) {
            for (int i = 1; i <= 10; i++) f.add(media("image_" + i, "Reference image " + i, false, false));
            elements(f, 10, false, false);
            f.add(select("resolution", "Resolution", "kling-v3-omni".equals(p.model()) ? List.of("1k", "2k", "4k") : List.of("1k", "2k"), "1k"));
            var ratios = new ArrayList<>(RATIOS); ratios.add("auto");
            f.add(select("aspect_ratio", "Aspect ratio", ratios, "16:9"));
            if ("kling-v3-omni".equals(p.model())) {
                f.add(select("result_type", "Result type", List.of("single", "series"), "single"));
                var options = new ArrayList<RunningHubDefinition.Option>();
                for (int i = 2; i <= 9; i++) options.add(new RunningHubDefinition.Option(Integer.toString(i), N.numberNode(i)));
                options.add(new RunningHubDefinition.Option("auto", N.stringNode("auto")));
                f.add(new Field("series_amount", "Series amount", null, RunningHubDefinition.FieldType.SELECT, false,
                        N.numberNode(4), null, null, null, List.copyOf(options), false, null,
                        new RunningHubDefinition.Condition("result_type", N.stringNode("series"))));
            }
        } else {
            f.add(media("image_1", "Reference image", false, false));
            f.add(text("negative_prompt", "Negative prompt", null, false, 2500, true, null));
            f.add(select("resolution", "Resolution", List.of("1k", "2k"), "1k"));
            f.add(select("aspect_ratio", "Aspect ratio", RATIOS, "16:9"));
            if ("kling-v2-1".equals(p.model())) {
                f.add(select("image_reference", "Reference type", List.of("subject", "face"), null));
                f.add(number("image_fidelity", "Image fidelity", 0, 1, N.numberNode(0.5), true, false));
                f.add(number("human_fidelity", "Human fidelity", 0, 1, N.numberNode(0.45), true, false));
            } else elements(f, 10, false, false);
        }
        Field n = integer("n", "Image count", 1, 9, 1, null);
        if ("kling-v3-omni".equals(p.model())) n = new Field(n.key(), n.label(), n.description(), n.type(), n.required(),
                n.defaultValue(), n.minimum(), n.maximum(), n.maxLength(), n.options(), n.advanced(), n.source(),
                new RunningHubDefinition.Condition("result_type", N.stringNode("single")));
        f.add(n);
    }

    private static void videoFields(Profile p, List<Field> f) {
        if (motion(p)) {
            f.add(media("image_1", "Character image", true, false)); f.add(media("motion_video", "Motion reference video", true, true));
            f.add(select("character_orientation", "Character orientation", List.of("image", "video"), "video"));
            f.add(select("audio", "Audio", List.of("original", "off"), "original"));
            f.add(select("resolution", "Resolution", List.of("720p", "1080p"), "720p"));
            if ("kling-3.0".equals(p.model())) elements(f, 1, false, true);
            return;
        }
        if (oldVideo(p)) f.add(new Field("duration", "Duration (seconds)", null, RunningHubDefinition.FieldType.SELECT, true,
                N.numberNode(5), BigDecimal.valueOf(5), BigDecimal.valueOf(10), null,
                List.of(new RunningHubDefinition.Option("5", N.numberNode(5)), new RunningHubDefinition.Option("10", N.numberNode(10))),
                false, RunningHubDefinition.Source.DURATION_SECONDS, null));
        else f.add(integer("duration", "Duration (seconds)", minimumSeconds(p), maximumSeconds(p), 5, RunningHubDefinition.Source.DURATION_SECONDS));
        f.add(select("resolution", "Resolution", v3(p) ? List.of("720p", "1080p", "4k") : List.of("720p", "1080p"), "720p"));
        Field ratio = select("aspect_ratio", "Aspect ratio", VIDEO_RATIOS, "16:9");
        if (!omni(p)) ratio = new Field(ratio.key(), ratio.label(),
                "Applies to text-to-video only. With a first frame, the video follows the input image aspect ratio.",
                ratio.type(), ratio.required(), ratio.defaultValue(), ratio.minimum(), ratio.maximum(), ratio.maxLength(),
                ratio.options(), ratio.advanced(), ratio.source(), ratio.enabledWhen());
        f.add(ratio);
        f.add(media("first_frame", "First frame", false, false));
        if (!"kling-3.0-turbo".equals(p.model())) f.add(media("last_frame", "Last frame", false, false));
        if (v3(p) || "kling-2.6".equals(p.model()) || "kling-o1".equals(p.model()))
            f.add(select("audio", "Audio", "kling-3.0-omni".equals(p.model()) ? List.of("native", "original", "off")
                    : "kling-o1".equals(p.model()) ? List.of("original", "off") : List.of("native", "off"), "off"));
        if (v3(p)) f.add(bool("multi_shot", "Multiple shots", true));
        if (omni(p)) {
            for (int i = 1; i <= 7; i++) f.add(media("image_" + i, "Reference image " + i, false, false));
            f.add(media("feature_video", "Feature reference video", false, true)); f.add(media("base_video", "Base video to edit", false, true));
            elements(f, 7, "kling-3.0-omni".equals(p.model()), true);
        } else if ("kling-3.0".equals(p.model())) elements(f, 3, false, true);
        else if ("kling-2.6".equals(p.model())) {
            for (int i = 1; i <= 2; i++) {
                f.add(text("voiceId" + i, "Voice ID " + i, null, false, 128, true, null));
                f.add(text("voiceRefId" + i, "Voice prompt alias " + i, N.stringNode("voice_" + i), false, 128, true, null));
            }
        }
    }

    private static void elements(List<Field> f, int count, boolean videoSubjects, boolean videoGeneration) {
        for (int i = 1; i <= count; i++) {
            f.add(text("elementId" + i, "Subject ID " + i, null, false, videoGeneration ? 128 : 19, true, null));
            if (videoSubjects) f.add(select("elementType" + i, "Subject type " + i, List.of("IMAGE", "VIDEO"), "IMAGE"));
            if (videoGeneration)
                f.add(text("elementRefId" + i, "Subject prompt alias " + i, N.stringNode("element_" + i), false, 128, true, null));
        }
    }

    public static ObjectNode normalizeSettings(JsonNode settings, Task.Kind kind, ObjectMapper mapper) {
        if (settings == null || !settings.isObject()) throw KlingProtocol.invalid();
        for (String key : settings.propertyNames()) if (!MediaCapabilityConfiguration.FIELDS.contains(key)
                && !Set.of("model", "operation", "klingInputs").contains(key)) throw KlingProtocol.invalid();
        Profile p = resolve(settings, kind);
        if (motion(p) && (settings.has("minimumSeconds") || settings.has("maximumSeconds")))
            throw KlingProtocol.invalid(); // Motion output duration is determined by the valid source motion segment.
        if (settings.has("defaultDurationSeconds")) {
            if (!newVideo(p) || motion(p) || !settings.path("defaultDurationSeconds").isIntegralNumber()
                    || !settings.path("defaultDurationSeconds").canConvertToInt()) throw KlingProtocol.invalid();
            int seconds = settings.path("defaultDurationSeconds").asInt();
            if (seconds < minimumSeconds(p) || seconds > maximumSeconds(p) || oldVideo(p) && seconds != 5 && seconds != 10) throw KlingProtocol.invalid();
        }
        ObjectNode normalized = settings != null && settings.isObject() ? (ObjectNode) settings.deepCopy() : mapper.createObjectNode();
        if (p.model().isEmpty()) normalized.remove("model"); else normalized.put("model", p.model());
        normalized.put("operation", p.operation().name());
        ObjectNode defaults = normalized.path("defaultParameters").isObject()
                ? (ObjectNode) normalized.path("defaultParameters").deepCopy() : mapper.createObjectNode();
        if (normalized.has("defaultParameters") && !normalized.path("defaultParameters").isObject()) throw KlingProtocol.invalid();
        for (String key : defaults.propertyNames()) if (!VALUES_PROPERTY.equals(key)) throw KlingProtocol.invalid();
        if (defaults.has(VALUES_PROPERTY) && !defaults.path(VALUES_PROPERTY).isObject()) throw KlingProtocol.invalid();
        ObjectNode values = defaults.path(VALUES_PROPERTY).isObject()
                ? (ObjectNode) defaults.path(VALUES_PROPERTY).deepCopy() : mapper.createObjectNode();
        for (String key : values.propertyNames()) {
            Field field = inputs(p).stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow(KlingProtocol::invalid);
            if (field.media() || field.effectiveSource() != RunningHubDefinition.Source.PARAMETER) throw KlingProtocol.invalid();
        }
        for (Field field : inputs(p)) if (field.defaultValue() != null && field.effectiveSource() == RunningHubDefinition.Source.PARAMETER && !values.has(field.key()))
            values.set(field.key(), field.defaultValue().deepCopy());
        defaults.set(VALUES_PROPERTY, values); normalized.set("defaultParameters", defaults);
        validateValues(p, values, false);
        normalized.set("klingInputs", mapper.valueToTree(inputs(p)));
        return normalized;
    }

    public static ObjectNode effectiveValues(Profile p, JsonNode parameters, String prompt, Integer duration, ObjectMapper mapper) {
        if (parameters == null || !parameters.isObject()) throw KlingProtocol.invalid();
        for (String key : parameters.propertyNames()) if (!VALUES_PROPERTY.equals(key)) throw KlingProtocol.invalid();
        JsonNode supplied = parameters.path(VALUES_PROPERTY);
        if (!supplied.isMissingNode() && !supplied.isObject()) throw KlingProtocol.invalid();
        ObjectNode values = supplied.isObject() ? (ObjectNode) supplied.deepCopy() : mapper.createObjectNode();
        for (Field field : inputs(p)) {
            if (field.effectiveSource() == RunningHubDefinition.Source.PROMPT) {
                if (prompt != null) values.put(field.key(), prompt); else values.remove(field.key());
            } else if (field.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS) {
                if (duration != null) values.put(field.key(), duration); else values.set(field.key(), field.defaultValue());
            } else if (!values.has(field.key()) && field.defaultValue() != null) values.set(field.key(), field.defaultValue().deepCopy());
        }
        validateValues(p, values, false);
        return values;
    }

    public static void validateDraft(Profile p, JsonNode parameters, String prompt, Integer duration,
            List<MediaDraft.MediaInput> media, boolean complete) {
        ObjectNode values = effectiveValues(p, parameters, prompt, duration, new ObjectMapper());
        validateValues(p, values, complete);
        var assigned = new HashSet<UUID>();
        for (Field field : inputs(p)) if (field.media() && present(values, field.key())) {
            UUID id = uuid(values.path(field.key()).asText());
            boolean video = field.type() == RunningHubDefinition.FieldType.VIDEO;
            if (!assigned.add(id) || media.stream().noneMatch(input -> input.versionId().equals(id)
                    && (video ? input.role() == MediaDraft.InputRole.VIDEO_REFERENCE
                        : input.role() == MediaDraft.InputRole.REFERENCE)))
                throw KlingProtocol.invalid();
        }
        if (complete && media.stream().anyMatch(input -> !assigned.contains(input.versionId()))) throw KlingProtocol.invalid();
    }

    public static void validateValues(Profile p, JsonNode values, boolean complete) {
        if (!values.isObject()) throw KlingProtocol.invalid();
        var fields = inputs(p);
        Set<String> keys = new HashSet<>(); for (Field f : fields) keys.add(f.key());
        for (String key : values.propertyNames()) if (!keys.contains(key)) throw KlingProtocol.invalid();
        var aliases = new HashSet<String>();
        for (Field f : fields) if (f.media() && present(values, f.key())) aliases.add(f.key());
        for (Field f : fields) {
            JsonNode value = values.get(f.key());
            boolean active = f.enabledWhen() == null || f.enabledWhen().value().equals(values.path(f.enabledWhen().field()));
            if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()) {
                if (complete && active && f.required()) throw KlingProtocol.invalid();
                continue;
            }
            // Drafts retain source values across capability changes; compiled limits apply at generation time.
            if (!complete && f.effectiveSource() == RunningHubDefinition.Source.PROMPT) {
                if (!value.isTextual()) throw KlingProtocol.invalid();
                continue;
            }
            if (!complete && f.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS) {
                if (!value.isIntegralNumber()) throw KlingProtocol.invalid();
                continue;
            }
            switch (f.type()) {
                case IMAGE, VIDEO -> { if (!value.isTextual()) throw KlingProtocol.invalid(); uuid(value.asText()); }
                case BOOLEAN -> { if (!value.isBoolean()) throw KlingProtocol.invalid(); }
                case INTEGER, NUMBER -> {
                    if (!value.isNumber() || f.type() == RunningHubDefinition.FieldType.INTEGER && !value.isIntegralNumber()) throw KlingProtocol.invalid();
                    BigDecimal number = value.decimalValue();
                    if (f.minimum() != null && number.compareTo(f.minimum()) < 0 || f.maximum() != null && number.compareTo(f.maximum()) > 0) throw KlingProtocol.invalid();
                }
                case SELECT -> { if (f.options().stream().noneMatch(option -> option.value().equals(value))) throw KlingProtocol.invalid(); }
                case STRING -> {
                    if (!value.isTextual() || f.maxLength() != null && value.asText().codePointCount(0, value.asText().length()) > f.maxLength()) throw KlingProtocol.invalid();
                    if (!newVideo(p) && f.key().startsWith("elementId")) positiveLong(value.asText());
                }
                default -> throw KlingProtocol.invalid();
            }
        }
        int subjects = 0, videoSubjects = 0;
        for (int i = 1; i <= 10; i++) if (present(values, "elementId" + i)) {
            subjects++;
            if ("VIDEO".equals(values.path("elementType" + i).asText())) videoSubjects++;
            if (newVideo(p) && complete && !present(values, "elementRefId" + i)) throw KlingProtocol.invalid();
            if (present(values, "elementRefId" + i) && !aliases.add(values.path("elementRefId" + i).asText())) throw KlingProtocol.invalid();
        }
        for (int i = 1; i <= 2; i++) if (present(values, "voiceId" + i)
                && (!present(values, "first_frame") && complete || !present(values, "voiceRefId" + i) && complete || "off".equals(values.path("audio").asText())
                    || present(values, "voiceRefId" + i) && !aliases.add(values.path("voiceRefId" + i).asText()))) throw KlingProtocol.invalid();
        int images = countMedia(p, values, false), videos = countMedia(p, values, true);
        if (!newVideo(p)) {
            if (omni(p) && images + subjects > 10 || "kling-v3".equals(p.model()) && images + subjects > 10
                    || present(values, "image_reference") && !present(values, "image_1") && complete
                    || present(values, "negative_prompt") && present(values, "image_1")) throw KlingProtocol.invalid();
            if ("kling-v3-omni".equals(p.model()) && images == 0 && subjects == 0 && "auto".equals(values.path("aspect_ratio").asText())) throw KlingProtocol.invalid();
            if (p.operation() == Operation.EXPAND) {
                double area = (1 + values.path("up_expansion_ratio").asDouble() + values.path("down_expansion_ratio").asDouble())
                        * (1 + values.path("left_expansion_ratio").asDouble() + values.path("right_expansion_ratio").asDouble());
                if (area > 3 + 1e-9) throw KlingProtocol.invalid();
            }
            return;
        }
        boolean first = present(values, "first_frame"), last = present(values, "last_frame"), base = present(values, "base_video"), feature = present(values, "feature_video");
        if (motion(p)) {
            if (subjects > 0 && !"video".equals(values.path("character_orientation").asText())) throw KlingProtocol.invalid();
            return;
        }
        int seconds = values.path("duration").asInt(5);
        if (complete && (oldVideo(p) && seconds != 5 && seconds != 10 || "kling-o1".equals(p.model()) && !feature && !base && images <= (first ? 1 : 0) && subjects == 0 && seconds != 5 && seconds != 10))
            throw KlingProtocol.invalid();
        if (last && !first && complete || last && oldVideo(p) && !"1080p".equals(values.path("resolution").asText())
                || "kling-2.6".equals(p.model()) && ("native".equals(values.path("audio").asText()) && !"1080p".equals(values.path("resolution").asText())
                    || last && !"off".equals(values.path("audio").asText()))) throw KlingProtocol.invalid();
        if (!omni(p)) {
            if (subjects > 0 && !first && complete) throw KlingProtocol.invalid();
            if (complete && "kling-3.0-turbo".equals(p.model()) && first && present(values, "prompt")
                    && values.path("prompt").asText().codePointCount(0, values.path("prompt").asText().length()) > 2500) throw KlingProtocol.invalid();
            return;
        }
        int references = images - (first ? 1 : 0) - (last ? 1 : 0);
        if (videos > 1 || base && (first || last) || "kling-o1".equals(p.model()) && feature && last
                || "kling-3.0-omni".equals(p.model()) && (feature && !"off".equals(values.path("audio").asText())
                    || base && (values.path("multi_shot").asBoolean() || "native".equals(values.path("audio").asText())))) throw KlingProtocol.invalid();
        if ("kling-o1".equals(p.model())) {
            if (first && last && (references > 0 || subjects > 0)
                    || images + subjects > (videos > 0 ? 4 : 7)) throw KlingProtocol.invalid();
        } else {
            int imageSubjects = subjects - videoSubjects;
            // The shared image schema does not exempt first/last frames from its combined image/subject cap.
            if (first && subjects > 3 || videos == 0 && videoSubjects > 3
                    || videos == 0 && images + imageSubjects > (videoSubjects > 0 ? 4 : 7)
                    || videos > 0 && (videoSubjects > 1 || videoSubjects > 0 && (imageSubjects > 0 || references > 0)
                        || images + imageSubjects > 4)) throw KlingProtocol.invalid();
        }
    }

    public static String requestPath(Profile p, JsonNode values) {
        if (!newVideo(p)) return switch (p.operation()) {
            case EXPAND -> "/v1/images/editing/expand";
            case SUBJECT_COMPLETION -> "/v1/general/ai-multi-shot";
            case MULTI_IMAGE -> "/v1/images/multi-image2image";
            default -> omni(p) ? "/v1/images/omni-image" : "/v1/images/generations";
        };
        return (motion(p) ? "/motion-control/" : omni(p) ? "/omni-video/"
                : present(values, "first_frame") ? "/image-to-video/" : "/text-to-video/") + p.model();
    }
    public static String queryPath(Profile p, String id) { return newVideo(p) ? "/tasks?task_ids=" + id : requestPath(p, N.objectNode()) + "/" + id; }

    public static Integer resultCount(Profile p, JsonNode values) {
        if (newVideo(p)) return 1;
        if (p.operation() == Operation.SUBJECT_COMPLETION) return 9;
        if ("series".equals(values.path("result_type").asText())) return "auto".equals(values.path("series_amount").asText()) ? null : values.path("series_amount").asInt(4);
        return values.path("n").asInt(1);
    }

    public static void validateMetadata(Profile p, String fieldKey, Asset asset) { validateMetadata(p, fieldKey, asset, N.objectNode()); }
    public static void validateMetadata(Profile p, String fieldKey, Asset asset, JsonNode values) {
        Field f = inputs(p).stream().filter(field -> field.key().equals(fieldKey) && field.media()).findFirst().orElseThrow(KlingProtocol::invalid);
        if (f.type() == RunningHubDefinition.FieldType.IMAGE) { KlingProtocol.validateMetadata(asset, newVideo(p)); return; }
        if (asset.mediaKind() != Asset.MediaKind.VIDEO || !Set.of("video/mp4", "video/quicktime").contains(asset.contentType())
                || asset.byteSize() <= 0 || asset.byteSize() > (motion(p) ? 100_000_000 : 200_000_000)
                || asset.width() == null || asset.height() == null || asset.durationMs() == null) throw KlingProtocol.invalid();
        int minSide = motion(p) ? 340 : 700;
        int maxSide = motion(p) ? 3850 : "kling-o1".equals(p.model()) ? 2160 : 4553;
        int maxMs = motion(p) ? "image".equals(values.path("character_orientation").asText()) ? 10_000 : 30_000
                : "kling-o1".equals(p.model()) ? 10_000 : 15_500;
        if (asset.width() < minSide || asset.height() < minSide || asset.width() > maxSide || asset.height() > maxSide
                || asset.durationMs() < 3_000 || asset.durationMs() > maxMs) throw KlingProtocol.invalid();
        if (!motion(p) && !"kling-o1".equals(p.model())) {
            double ratio = (double) asset.width() / asset.height();
            if (ratio < 0.4 || ratio > 2 || (long) asset.width() * asset.height() > 8_294_400) throw KlingProtocol.invalid();
        }
    }
    public static int countMedia(Profile p, JsonNode values, boolean video) {
        return (int) inputs(p).stream().filter(f -> f.type() == (video ? RunningHubDefinition.FieldType.VIDEO : RunningHubDefinition.FieldType.IMAGE) && present(values, f.key())).count();
    }
    public static boolean present(JsonNode values, String key) { return values.hasNonNull(key) && !(values.path(key).isTextual() && values.path(key).asText().isBlank()); }
    public static long positiveLong(String value) {
        try { if (!value.matches("[1-9][0-9]{0,18}")) throw KlingProtocol.invalid(); long number = Long.parseLong(value); if (number <= 0) throw KlingProtocol.invalid(); return number; }
        catch (NumberFormatException invalid) { throw KlingProtocol.invalid(); }
    }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw KlingProtocol.invalid(); } }
    private static Field text(String key, String label, JsonNode value, boolean required, int length, boolean advanced, RunningHubDefinition.Source source) {
        return new Field(key, label, null, RunningHubDefinition.FieldType.STRING, required, value, null, null, length, List.of(), advanced, source, null);
    }
    private static Field media(String key, String label, boolean required, boolean video) {
        return new Field(key, label, null, video ? RunningHubDefinition.FieldType.VIDEO : RunningHubDefinition.FieldType.IMAGE, required, null, null, null, null, List.of(), false, null, null);
    }
    private static Field bool(String key, String label, boolean value) {
        return new Field(key, label, null, RunningHubDefinition.FieldType.BOOLEAN, false, N.booleanNode(value), null, null, null, List.of(), false, null, null);
    }
    private static Field integer(String key, String label, int min, int max, int value, RunningHubDefinition.Source source) {
        return new Field(key, label, null, RunningHubDefinition.FieldType.INTEGER, true, N.numberNode(value), BigDecimal.valueOf(min), BigDecimal.valueOf(max), null, List.of(), false, source, null);
    }
    private static Field number(String key, String label, double min, double max, JsonNode value, boolean advanced, boolean required) {
        return new Field(key, label, null, RunningHubDefinition.FieldType.NUMBER, required, value, BigDecimal.valueOf(min), BigDecimal.valueOf(max), null, List.of(), advanced, null, null);
    }
    private static Field select(String key, String label, List<String> options, String value) {
        return new Field(key, label, null, RunningHubDefinition.FieldType.SELECT, false, value == null ? null : N.stringNode(value), null, null, null,
                options.stream().map(option -> new RunningHubDefinition.Option(option, N.stringNode(option))).toList(), false, null, null);
    }
}
