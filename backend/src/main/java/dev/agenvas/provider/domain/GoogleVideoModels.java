package dev.agenvas.provider.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Current Gemini Developer API video contracts; Vertex-only options are deliberately absent. */
public final class GoogleVideoModels implements NativeMediaProtocol {
    public static final String VEO = "GOOGLE_VEO";
    public static final String OMNI = "GOOGLE_OMNI_VIDEO";
    public static final String GENERATE = "GENERATE";
    public static final String EDIT = "EDIT";
    public static final String EXTEND = "EXTEND";
    public static final String OMNI_MODEL = "gemini-omni-1.1-flash";
    public static final List<String> VEO_MODELS = List.of("veo-3.1-generate-preview",
            "veo-3.1-fast-generate-preview", "veo-3.1-lite-generate-preview");
    public static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    public static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    /** Application inline budget, not a claimed provider maximum. */
    public static final int MAX_INPUT_BYTES = 60 * 1024 * 1024;
    public static final int OMNI_REFERENCE_IMAGES = 6;
    private static final JsonNodeFactory N = JsonNodeFactory.instance;

    @Override public Set<String> adapterIds() { return Set.of(VEO, OMNI); }
    @Override public List<Profile> profiles() {
        List<Profile> profiles = new ArrayList<>();
        for (String model : VEO_MODELS) {
            profiles.add(new Profile(VEO, model, GENERATE, Task.Kind.VIDEO_GENERATION));
            if (!model.contains("lite")) profiles.add(new Profile(VEO, model, EXTEND, Task.Kind.VIDEO_GENERATION));
        }
        for (String operation : List.of(GENERATE, EDIT, EXTEND))
            profiles.add(new Profile(OMNI, OMNI_MODEL, operation, Task.Kind.VIDEO_GENERATION));
        return List.copyOf(profiles);
    }
    public static boolean veo(Profile p) { return VEO.equals(p.adapterId()); }
    public static boolean lite(Profile p) { return p.model().contains("lite"); }
    public static boolean extension(Profile p) { return EXTEND.equals(p.operation()); }
    public static boolean present(JsonNode values, String key) {
        JsonNode value = values.path(key);
        return !value.isMissingNode() && !value.isNull() && (!value.isTextual() || !value.asText().isBlank());
    }
    public static void requireProfile(Profile p) {
        if (!new GoogleVideoModels().profiles().contains(p)) throw invalid();
    }

    @Override public List<CreatorInputField> inputs(Profile p) {
        requireProfile(p);
        List<CreatorInputField> fields = new ArrayList<>();
        fields.add(text("prompt", "Prompt", true, 12000, RunningHubDefinition.Source.PROMPT, false));
        if (veo(p)) {
            fields.add(select("durationSeconds", "Duration (seconds)", List.of(4, 6, 8), 8,
                    RunningHubDefinition.Source.DURATION_SECONDS));
            fields.add(select("aspectRatio", "Aspect ratio", List.of("16:9", "9:16"), "16:9", null));
            fields.add(select("resolution", "Resolution", extension(p) ? List.of("720p")
                    : lite(p) ? List.of("720p", "1080p") : List.of("720p", "1080p", "4k"), "720p", null));
            fields.add(select("personGeneration", "People generation", List.of("allow_all", "allow_adult"), null, null));
            fields.add(text("negativePrompt", "Negative prompt", false, 12000, null, true));
            fields.add(bool("enhancePrompt", "Enhance prompt", null));
            fields.add(integer("seed", "Seed", 0, 4294967295L, null));
            if (extension(p)) fields.add(media("base_video", "Previous Veo video", true, true));
            else {
                fields.add(media("first_frame", "First frame", false, false));
                fields.add(media("last_frame", "Last frame", false, false));
                if (!lite(p)) for (int i = 1; i <= 3; i++) fields.add(media("image_" + i, "Reference image " + i, false, false));
            }
        } else {
            fields.add(select("aspect_ratio", "Aspect ratio", List.of("16:9", "9:16"), "16:9", null));
            fields.add(select("resolution", "Resolution", List.of("360p", "720p", "1080p", "4k"), "720p", null));
            // The REST schema specifies a string, without a documented canonical duration spelling.
            fields.add(text("duration", "Native duration", false, 32, null, true));
            fields.add(select("delivery", "Video delivery", List.of("inline", "uri"), "uri", null));
            fields.add(bool("background", "Background execution", true));
            fields.add(integer("seed", "Seed", Integer.MIN_VALUE, Integer.MAX_VALUE, null));
            fields.add(integer("max_output_tokens", "Maximum output tokens", 1, Integer.MAX_VALUE, null));
            if (GENERATE.equals(p.operation())) {
                fields.add(select("task", "Generation task", List.of("auto", "text_to_video", "image_to_video", "reference_to_video"), "auto", null));
                fields.add(media("first_frame", "First frame", false, false));
                fields.add(media("last_frame", "Last frame", false, false));
                for (int i = 1; i <= OMNI_REFERENCE_IMAGES; i++) fields.add(media("image_" + i, "Reference image " + i, false, false));
                for (int i = 1; i <= 3; i++) fields.add(media("video_" + i, "Reference video " + i, false, true));
            } else {
                fields.add(media("base_video", "Video to " + p.operation().toLowerCase(java.util.Locale.ROOT), true, true));
                for (int i = 1; i <= OMNI_REFERENCE_IMAGES; i++) fields.add(media("image_" + i, "Reference image " + i, false, false));
                for (int i = 1; i <= 3; i++) fields.add(media("video_" + i, "Reference video " + i, false, true));
            }
        }
        return List.copyOf(fields);
    }

    @Override public MediaAdapterRegistry.Declaration declaration(Profile p) {
        requireProfile(p);
        int images = (int) inputs(p).stream().filter(f -> f.type() == RunningHubDefinition.FieldType.IMAGE).count();
        int videos = (int) inputs(p).stream().filter(f -> f.type() == RunningHubDefinition.FieldType.VIDEO).count();
        return new MediaAdapterRegistry.Declaration(MediaPlatform.GOOGLE, Task.Kind.VIDEO_GENERATION,
                veo(p) ? 4 : 3, veo(p) ? 8 : 10, false, images,
                Set.of("TEXT", "GENERAL_REFERENCE"), "GENERAL_REFERENCE", false,
                Set.of(), Set.of(), Set.of(), false, false, 0, videos);
    }

    @Override public void validate(Profile p, JsonNode values, boolean complete) {
        requireProfile(p);
        if (!complete) return; // Persist an incomplete draft; full combinations are checked at acceptance.
        int refs = count(values, "image_", veo(p) ? 3 : OMNI_REFERENCE_IMAGES);
        int videos = count(values, "video_", 3);
        boolean first = present(values, "first_frame"), last = present(values, "last_frame");
        if (last && !first) throw invalid();
        if (veo(p)) {
            int duration = values.path("durationSeconds").asInt(8);
            if ((extension(p) || refs > 0 || !"720p".equals(values.path("resolution").asText("720p"))) && duration != 8) throw invalid();
            String people = values.path("personGeneration").asText("");
            if (!people.isEmpty() && (first || refs > 0) && !"allow_adult".equals(people)) throw invalid();
        } else {
            String task = values.path("task").asText("auto");
            if (GENERATE.equals(p.operation())) {
                if ("text_to_video".equals(task) && (first || refs > 0 || videos > 0)
                        || "image_to_video".equals(task) && (!first || refs > 0 || videos > 0)
                        || "reference_to_video".equals(task) && (first || refs + videos == 0)) throw invalid();
            }
        }
    }

    @Override public void validateMetadata(Profile p, String slot, Asset asset, JsonNode values) {
        requireProfile(p);
        boolean video = "base_video".equals(slot) || slot.startsWith("video_");
        if (asset.byteSize() < 1 || asset.width() == null || asset.height() == null
                || asset.width() < 1 || asset.height() < 1 || asset.mediaKind() != (video ? Asset.MediaKind.VIDEO : Asset.MediaKind.IMAGE)
                || !(video ? VIDEO_TYPES : IMAGE_TYPES).contains(asset.contentType())
                || asset.byteSize() > (video ? MAX_INPUT_BYTES : MAX_IMAGE_BYTES)) throw invalid();
        if (video) {
            if (asset.durationMs() == null || asset.durationMs() < 1) throw invalid();
            int maximum = veo(p) ? 141000 : slot.startsWith("video_") ? 3000 : 10000;
            if (asset.durationMs() > maximum) throw invalid();
            if (veo(p) && (asset.width() != 1280 || asset.height() != 720)
                    && (asset.width() != 720 || asset.height() != 1280)) throw invalid();
        }
    }
    @Override public Integer resultCount(Profile p, JsonNode values) { requireProfile(p); return 1; }
    @Override public Set<String> reusableSlots(Profile profile) {
        requireProfile(profile);
        // A loop may intentionally start and end on the same authorized image version.
        return GENERATE.equals(profile.operation()) ? Set.of("first_frame", "last_frame") : Set.of();
    }
    @Override public void validateSource(Profile profile, String slot, ArtifactVersion version) {
        requireProfile(profile);
        if (veo(profile) && extension(profile) && "base_video".equals(slot)
                && (version == null || version.createdByKind() != ArtifactVersion.CreatedByKind.TASK)) throw invalid();
    }
    @Override public boolean usesMediaUrl(Profile profile, CreatorInputField field) { return false; }
    @Override public void validateProbe(Profile profile, String slot, JsonNode values, JsonNode probe) {
        if (!"base_video".equals(slot) && !slot.startsWith("video_")) return;
        double seconds = probe.path("format").path("duration").asDouble(-1);
        double maximum = veo(profile) ? 141 : slot.startsWith("video_") ? 3 : 10;
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > maximum + 0.001) throw invalid();
    }
    @Override public String renderMention(Profile p, JsonNode values, String slot) {
        requireProfile(p);
        if (veo(p)) {
            if ("first_frame".equals(slot)) return "first frame";
            if ("last_frame".equals(slot)) return "last frame";
            if ("base_video".equals(slot)) return "source video";
            int position = 1;
            for (CreatorInputField field : inputs(p)) if (field.key().startsWith("image_") && present(values, field.key())) {
                if (field.key().equals(slot)) return "reference image " + position;
                position++;
            }
            throw invalid();
        }
        if ("first_frame".equals(slot)) return "<FIRST_FRAME>";
        if ("last_frame".equals(slot)) return "<LAST_FRAME>";
        if ("base_video".equals(slot)) return "<VIDEO_0>";
        String prefix = slot.startsWith("video_") ? "video_" : "image_";
        int position = 0;
        for (CreatorInputField field : inputs(p)) if (field.key().startsWith(prefix) && present(values, field.key())) {
            if (field.key().equals(slot)) return "<" + ("video_".equals(prefix) ? "VIDEO_REF_" : "IMAGE_REF_") + position + ">";
            position++;
        }
        throw invalid();
    }
    public static NativeMediaFailure invalid() { return new NativeMediaFailure("PROVIDER_UNSUPPORTED_INPUT"); }
    private static int count(JsonNode values, String prefix, int max) { int n = 0; for (int i = 1; i <= max; i++) if (present(values, prefix + i)) n++; return n; }
    private static CreatorInputField text(String key, String label, boolean required, int max, RunningHubDefinition.Source source, boolean advanced) {
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.STRING, required, null, null, null, max, List.of(), advanced, source, null);
    }
    private static CreatorInputField media(String key, String label, boolean required, boolean video) {
        return new CreatorInputField(key, label, null, video ? RunningHubDefinition.FieldType.VIDEO : RunningHubDefinition.FieldType.IMAGE,
                required, null, null, null, null, List.of(), false, null, null);
    }
    private static CreatorInputField select(String key, String label, List<?> options, Object defaultValue, RunningHubDefinition.Source source) {
        var choices = options.stream().map(v -> new RunningHubDefinition.Option(v.toString(), v instanceof Integer i ? N.numberNode(i) : N.stringNode(v.toString()))).toList();
        JsonNode selected = defaultValue == null ? null : defaultValue instanceof Integer i ? N.numberNode(i) : N.stringNode(defaultValue.toString());
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.SELECT, false, selected,
                null, null, null, choices, false, source, null);
    }
    private static CreatorInputField bool(String key, String label, Boolean value) {
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.BOOLEAN, false,
                value == null ? null : N.booleanNode(value), null, null, null, List.of(), true, null, null);
    }
    private static CreatorInputField integer(String key, String label, long min, long max, Long value) {
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.INTEGER, false,
                value == null ? null : N.numberNode(value), BigDecimal.valueOf(min), BigDecimal.valueOf(max), null, List.of(), true, null, null);
    }
}
