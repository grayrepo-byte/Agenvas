package dev.agenvas.provider.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** The current Wan 2.7 image and Wan 3.0 video contracts, with finite native array fields. */
public final class WanModels implements NativeMediaProtocol {
    public static final String IMAGE_ADAPTER_ID = "WAN_IMAGE";
    public static final String VIDEO_ADAPTER_ID = "WAN_VIDEO";
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_VIDEO_BYTES = 100 * 1024 * 1024;
    public static final int MAX_AUDIO_BYTES = 15 * 1024 * 1024;
    private static final JsonNodeFactory N = JsonNodeFactory.instance;
    private static final List<Profile> PROFILES = List.of(
            new Profile(IMAGE_ADAPTER_ID, "wan2.7-image", "GENERATE", Task.Kind.IMAGE_GENERATION),
            new Profile(IMAGE_ADAPTER_ID, "wan2.7-image-pro", "GENERATE", Task.Kind.IMAGE_GENERATION),
            new Profile(VIDEO_ADAPTER_ID, "wan3.0-video", "GENERATE", Task.Kind.VIDEO_GENERATION),
            new Profile(VIDEO_ADAPTER_ID, "wan3.0-video-prime", "GENERATE", Task.Kind.VIDEO_GENERATION));

    public WanModels() {}
    @Override public Set<String> adapterIds() { return Set.of(IMAGE_ADAPTER_ID, VIDEO_ADAPTER_ID); }
    @Override public List<Profile> profiles() { return PROFILES; }
    public static boolean video(Profile p) { requireProfile(p); return p.kind() == Task.Kind.VIDEO_GENERATION; }
    public static void requireProfile(Profile p) { if (!PROFILES.contains(p)) throw NativeMediaProfiles.invalid(); }

    @Override public List<CreatorInputField> inputs(Profile p) {
        requireProfile(p);
        var fields = new ArrayList<CreatorInputField>();
        fields.add(text("prompt", "Prompt", video(p) ? "A prompt or media is required." : "Image generation or editing instructions.",
                false, video(p) ? 20_000 : 5_000, null, RunningHubDefinition.Source.PROMPT));
        if (video(p)) videoFields(fields); else imageFields(p, fields);
        fields.add(bool("watermark", "Watermark", false, false));
        fields.add(integer("seed", "Seed", video(p) ? -1 : 0, 2_147_483_647L, null, true, null));
        return List.copyOf(fields);
    }

    private static void imageFields(Profile p, List<CreatorInputField> fields) {
        for (int i = 1; i <= 9; i++) {
            String image = "image_" + i;
            fields.add(media(image, "Reference image " + i, RunningHubDefinition.FieldType.IMAGE));
            for (int box = 1; box <= 2; box++) for (String coordinate : List.of("x1", "y1", "x2", "y2"))
                fields.add(integer(image + "_bbox_" + box + "_" + coordinate,
                        "Image " + i + " box " + box + " " + coordinate, 0, 8000, null, true, null));
        }
        fields.add(text("size", "Output size", "1K, 2K, or WIDTH*HEIGHT; 4K is available only for pro text-to-image without sequential generation. Preset sizes follow the last input image ratio, or 1:1 without images.",
                true, 32, N.stringNode("2K"), null));
        fields.add(bool("enable_sequential", "Sequential images", false, false));
        fields.add(integer("n", "Image count / sequential maximum", 1, 12, N.numberNode(1), false, null));
        fields.add(bool("thinking_mode", "Thinking mode", true, true));
        for (int i = 1; i <= 10; i++) {
            fields.add(text("color_" + i + "_hex", "Palette color " + i, "HEX color, for example #C2D1E6. A palette needs 3–10 colors and is unavailable in sequential mode.",
                    false, 7, null, null));
            fields.add(new CreatorInputField("color_" + i + "_ratio", "Palette percentage " + i,
                    "Percentage with at most two decimal places; all palette percentages must total 100.00.",
                    RunningHubDefinition.FieldType.NUMBER, false, null, BigDecimal.ZERO, BigDecimal.valueOf(100),
                    null, null, true, null, null));
        }
    }

    private static void videoFields(List<CreatorInputField> fields) {
        var duration = integer("duration", "Duration (seconds)", 2, 30, N.numberNode(5), false, RunningHubDefinition.Source.DURATION_SECONDS);
        fields.add(new CreatorInputField(duration.key(), duration.label(), duration.description(), duration.type(), duration.required(),
                duration.defaultValue(), duration.minimum(), duration.maximum(), duration.maxLength(), duration.options(), duration.advanced(), duration.source(),
                new RunningHubDefinition.Condition("smart_duration", N.booleanNode(false))));
        fields.add(bool("smart_duration", "Automatic duration", false, false));
        fields.add(select("resolution", "Resolution", List.of("480P", "720P", "1080P"), "1080P"));
        fields.add(select("ratio", "Aspect ratio", List.of("adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16"), "adaptive"));
        fields.add(bool("audio", "Generate audio", true, false));
        fields.add(bool("prompt_extend", "Expand prompt", true, true));
        fields.add(media("first_frame", "First frame", RunningHubDefinition.FieldType.IMAGE));
        fields.add(media("last_frame", "Last frame", RunningHubDefinition.FieldType.IMAGE));
        for (int i = 1; i <= 10; i++) fields.add(media("image_" + i, "Reference image " + i, RunningHubDefinition.FieldType.IMAGE));
        for (int i = 1; i <= 5; i++) fields.add(media("video_" + i, "Reference video " + i, RunningHubDefinition.FieldType.VIDEO));
        for (int i = 1; i <= 5; i++) fields.add(media("audio_" + i, "Reference audio " + i, RunningHubDefinition.FieldType.AUDIO));
    }

    @Override public MediaAdapterRegistry.Declaration declaration(Profile p) {
        boolean video = video(p);
        return new MediaAdapterRegistry.Declaration(MediaPlatform.WAN, p.kind(), video ? 2 : 0,
                video ? 30 : 0, true, video ? 10 : 9,
                video ? Set.of("TEXT", "GENERAL_REFERENCE") : Set.of(), video ? "TEXT" : null,
                false, Set.of(), Set.of(), Set.of(), false, false, video ? 5 : 0, video ? 5 : 0);
    }

    @Override public void validate(Profile p, JsonNode values, boolean complete) {
        requireProfile(p);
        if (video(p)) {
            boolean frames = present(values, "first_frame") || present(values, "last_frame");
            int referenceCount = count(values, "image_", 10) + count(values, "video_", 5) + count(values, "audio_", 5);
            if (frames && referenceCount > 0 || complete && present(values, "last_frame") && !present(values, "first_frame")
                    || complete && !present(values, "prompt") && !frames && referenceCount == 0) throw NativeMediaProfiles.invalid();
            // Missing automatic-mode controls still mean native fixed duration, including direct client callers.
            if (complete && !values.path("smart_duration").asBoolean(false) && present(values, "duration")
                    && (!values.path("duration").isIntegralNumber() || !values.path("duration").canConvertToInt()
                        || values.path("duration").asInt() < 2 || values.path("duration").asInt() > 30)) throw NativeMediaProfiles.invalid();
        } else {
            boolean sequential = values.path("enable_sequential").asBoolean(false);
            int images = count(values, "image_", 9);
            if (complete && !present(values, "prompt") || !sequential && values.path("n").asInt(1) > 4) throw NativeMediaProfiles.invalid();
            validateSize(p, values.path("size").asText("2K"), sequential || images > 0);
            int colors = 0;
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 1; i <= 10; i++) {
                String hex = "color_" + i + "_hex", ratio = "color_" + i + "_ratio";
                boolean hasHex = present(values, hex), hasRatio = present(values, ratio);
                if (hasHex || hasRatio) {
                    colors++;
                    if (sequential || hasHex && !values.path(hex).asText().matches("#[0-9A-Fa-f]{6}")
                            || complete && hasHex != hasRatio) throw NativeMediaProfiles.invalid();
                    if (hasRatio) {
                        BigDecimal percentage = values.path(ratio).decimalValue();
                        if (percentage.stripTrailingZeros().scale() > 2) throw NativeMediaProfiles.invalid();
                        total = total.add(percentage);
                    }
                }
            }
            if (complete && colors > 0 && (colors < 3 || total.compareTo(BigDecimal.valueOf(100)) != 0)) throw NativeMediaProfiles.invalid();
            for (int i = 1; i <= 9; i++) for (int box = 1; box <= 2; box++) {
                String prefix = "image_" + i + "_bbox_" + box + "_";
                int coordinates = 0;
                for (String coordinate : List.of("x1", "y1", "x2", "y2")) if (present(values, prefix + coordinate)) coordinates++;
                if (coordinates > 0 && !present(values, "image_" + i)
                        || complete && coordinates > 0 && coordinates != 4) throw NativeMediaProfiles.invalid();
                if (coordinates == 4 && (values.path(prefix + "x1").asInt() >= values.path(prefix + "x2").asInt()
                        || values.path(prefix + "y1").asInt() >= values.path(prefix + "y2").asInt())) throw NativeMediaProfiles.invalid();
            }
        }
    }

    private static void validateSize(Profile p, String size, boolean restricted) {
        int maximum = "wan2.7-image-pro".equals(p.model()) && !restricted ? 4096 : 2048;
        if (Set.of("1K", "2K").contains(size)) return;
        if ("4K".equals(size)) { if (maximum < 4096) throw NativeMediaProfiles.invalid(); return; }
        if (!size.matches("[1-9][0-9]{0,4}\\*[1-9][0-9]{0,4}")) throw NativeMediaProfiles.invalid();
        String[] parts = size.split("\\*");
        long width = Long.parseLong(parts[0]), height = Long.parseLong(parts[1]), pixels = width * height;
        if (pixels < 768L * 768 || pixels > (long) maximum * maximum || width > height * 8 || height > width * 8)
            throw NativeMediaProfiles.invalid();
    }

    @Override public void validateMetadata(Profile p, String slot, Asset asset, JsonNode values) {
        var field = inputs(p).stream().filter(f -> f.key().equals(slot) && f.media()).findFirst().orElseThrow(NativeMediaProfiles::invalid);
        if (asset == null || asset.byteSize() <= 0) throw NativeMediaProfiles.invalid();
        switch (field.type()) {
            case IMAGE -> {
                if (asset.mediaKind() != Asset.MediaKind.IMAGE || asset.byteSize() > MAX_IMAGE_BYTES
                        || !Set.of("image/jpeg", "image/png", "image/bmp", "image/webp").contains(asset.contentType())) throw NativeMediaProfiles.invalid();
                dimensions(asset, 8000);
                if (!video(p)) for (int box = 1; box <= 2; box++) {
                    String prefix = slot + "_bbox_" + box + "_";
                    if (present(values, prefix + "x2") && values.path(prefix + "x2").asInt() > asset.width()
                            || present(values, prefix + "y2") && values.path(prefix + "y2").asInt() > asset.height()) throw NativeMediaProfiles.invalid();
                }
            }
            case VIDEO -> {
                if (asset.mediaKind() != Asset.MediaKind.VIDEO || asset.byteSize() > MAX_VIDEO_BYTES
                        || !Set.of("video/mp4", "video/quicktime").contains(asset.contentType())) throw NativeMediaProfiles.invalid();
                dimensions(asset, 4096); duration(asset);
            }
            case AUDIO -> {
                if (asset.mediaKind() != Asset.MediaKind.AUDIO || asset.byteSize() > MAX_AUDIO_BYTES
                        || !Set.of("audio/mpeg", "audio/wav", "audio/x-wav", "audio/wave").contains(asset.contentType())) throw NativeMediaProfiles.invalid();
                duration(asset);
            }
            default -> throw NativeMediaProfiles.invalid();
        }
    }

    /** Combined reference limits must use the exact authorized assets, before signing or submitting. */
    @Override public void validatePrepared(Profile p, JsonNode values, Map<String, Asset> assets) {
        long videoMs = 0, audioMs = 0;
        for (var entry : assets.entrySet()) {
            validateMetadata(p, entry.getKey(), entry.getValue(), values);
            if (entry.getKey().startsWith("video_")) videoMs += entry.getValue().durationMs();
            if (entry.getKey().startsWith("audio_")) audioMs += entry.getValue().durationMs();
        }
        if (videoMs > 15_000 || audioMs > 15_000 || videoMs > 0 && !values.path("smart_duration").asBoolean(false)
                && videoMs + values.path("duration").asLong(5) * 1000 > 30_000) throw NativeMediaProfiles.invalid();
    }

    @Override public void validateProbe(Profile p, String slot, JsonNode values, JsonNode probe) {
        if (!slot.startsWith("video_")) {
            if (slot.startsWith("audio_")) {
                String format = probe.path("format").path("format_name").asText();
                if (!Set.of("wav", "mp3").contains(format)) throw NativeMediaProfiles.invalid();
                probeDuration(probe);
            }
            return;
        }
        String format = probe.path("format").path("format_name").asText();
        if (!format.contains("mov") && !format.contains("mp4")) throw NativeMediaProfiles.invalid();
        probeDuration(probe);
        boolean found = false;
        for (JsonNode stream : probe.path("streams")) if ("video".equals(stream.path("codec_type").asText())) {
            if (found) throw NativeMediaProfiles.invalid();
            found = true;
            String[] parts = stream.path("avg_frame_rate").asText().split("/");
            double fps;
            try { fps = Double.parseDouble(parts[0]) / (parts.length == 2 ? Double.parseDouble(parts[1]) : 1); }
            catch (RuntimeException invalid) { throw NativeMediaProfiles.invalid(); }
            if (!Double.isFinite(fps) || fps < 16) throw NativeMediaProfiles.invalid();
        }
        if (!found) throw NativeMediaProfiles.invalid();
    }
    private static void probeDuration(JsonNode probe) {
        double seconds = probe.path("format").path("duration").asDouble(-1);
        if (!Double.isFinite(seconds) || seconds < 1 || seconds > 15) throw NativeMediaProfiles.invalid();
    }

    @Override public BigDecimal billableInputSeconds(Profile p, JsonNode values, Map<String, Asset> inputs) {
        long milliseconds = inputs.entrySet().stream().filter(e -> e.getKey().startsWith("video_"))
                .mapToLong(e -> e.getValue().durationMs()).sum();
        return BigDecimal.valueOf(milliseconds, 3);
    }

    private static void dimensions(Asset asset, int maximum) {
        if (asset.width() == null || asset.height() == null || asset.width() < 240 || asset.height() < 240
                || asset.width() > maximum || asset.height() > maximum
                || asset.width() > (long) asset.height() * 8 || asset.height() > (long) asset.width() * 8) throw NativeMediaProfiles.invalid();
    }
    private static void duration(Asset asset) {
        if (asset.durationMs() == null || asset.durationMs() < 1000 || asset.durationMs() > 15_000) throw NativeMediaProfiles.invalid();
    }
    @Override public Integer resultCount(Profile p, JsonNode values) {
        if (video(p)) return 1;
        if (values.path("enable_sequential").asBoolean(false)) return null;
        return values.path("n").asInt(1);
    }
    @Override public String renderMention(Profile p, JsonNode values, String slot) {
        if ("first_frame".equals(slot)) return "首帧";
        if ("last_frame".equals(slot)) return "尾帧";
        String prefix = slot.startsWith("video_") ? "video_" : slot.startsWith("audio_") ? "audio_" : "image_";
        int ordinal = 0;
        for (var field : inputs(p)) if (field.media() && field.key().startsWith(prefix) && present(values, field.key())) {
            ordinal++; if (slot.equals(field.key())) return ("video_".equals(prefix) ? "视频" : "audio_".equals(prefix) ? "音频" : "图") + ordinal;
        }
        throw NativeMediaProfiles.invalid();
    }
    @Override public String resolutionTier(Profile p, JsonNode values) {
        return video(p) ? values.path("resolution").asText("1080P").toLowerCase(java.util.Locale.ROOT) : null;
    }
    public static boolean present(JsonNode values, String key) { return NativeMediaProfiles.present(values, key); }
    private static int count(JsonNode values, String prefix, int maximum) {
        int result = 0; for (int i = 1; i <= maximum; i++) if (present(values, prefix + i)) result++; return result;
    }
    private static CreatorInputField media(String key, String label, RunningHubDefinition.FieldType type) {
        return new CreatorInputField(key, label, null, type, false, null, null, null, null, null, false, null, null);
    }
    private static CreatorInputField text(String key, String label, String description, boolean required, int length,
            JsonNode value, RunningHubDefinition.Source source) {
        return new CreatorInputField(key, label, description, RunningHubDefinition.FieldType.STRING, required, value,
                null, null, length, null, source == null, source, null);
    }
    private static CreatorInputField bool(String key, String label, boolean value, boolean advanced) {
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.BOOLEAN, false, N.booleanNode(value),
                null, null, null, null, advanced, null, null);
    }
    private static CreatorInputField integer(String key, String label, long minimum, long maximum, JsonNode value,
            boolean advanced, RunningHubDefinition.Source source) {
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.INTEGER, source != null, value,
                BigDecimal.valueOf(minimum), BigDecimal.valueOf(maximum), null, null, advanced, source, null);
    }
    private static CreatorInputField select(String key, String label, List<String> values, String value) {
        return new CreatorInputField(key, label, null, RunningHubDefinition.FieldType.SELECT, false, N.stringNode(value),
                null, null, null, values.stream().map(v -> new RunningHubDefinition.Option(v, N.stringNode(v))).toList(), false, null, null);
    }
}
