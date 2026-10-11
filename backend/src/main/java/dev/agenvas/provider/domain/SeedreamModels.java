package dev.agenvas.provider.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Official Ark image contracts, including native group generation and layer decomposition. */
public final class SeedreamModels implements NativeMediaProtocol {
    public static final String ADAPTER_ID = "ARK_SEEDREAM_IMAGE";
    public static final String PRO = "doubao-seedream-5-0-pro-260628";
    public static final String FLASH = "doubao-seedream-5-0-flash-260915";
    public static final String LITE = "doubao-seedream-5-0-260128";
    public static final String LITE_ALIAS = "doubao-seedream-5-0-lite-260128";
    public static final String GENERATE = "GENERATE";
    public static final String LAYERS = "LAYER_DECOMPOSITION";
    public static final String EDIT = "INTERACTIVE_EDIT";
    public static final int MAX_INPUT_BYTES = 30_000_000;
    public static final int MAX_PROMPT_LENGTH = 20_000;
    public static final int MAX_EDIT_JSON_LENGTH = 16_000;
    private static final List<String> MODELS = List.of(PRO, FLASH, LITE);
    private static final JsonNodeFactory N = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(MAX_EDIT_JSON_LENGTH)
                    .maxNumberLength(16).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public SeedreamModels() {}

    @Override public Set<String> adapterIds() { return Set.of(ADAPTER_ID); }

    @Override public List<Profile> profiles() {
        var profiles = new ArrayList<Profile>();
        for (String model : MODELS) profiles.add(new Profile(ADAPTER_ID, model, GENERATE, Task.Kind.IMAGE_GENERATION));
        for (String model : List.of(PRO, FLASH)) {
            profiles.add(new Profile(ADAPTER_ID, model, LAYERS, Task.Kind.IMAGE_GENERATION));
            profiles.add(new Profile(ADAPTER_ID, model, EDIT, Task.Kind.IMAGE_GENERATION));
        }
        return List.copyOf(profiles);
    }

    public Profile resolve(String model, String operation) {
        return profiles().stream().filter(p -> p.model().equals(model) && p.operation().equals(operation))
                .findFirst().orElseThrow(SeedreamModels::invalid);
    }

    public static boolean proFamily(Profile profile) { return Set.of(PRO, FLASH).contains(profile.model()); }
    public static boolean lite(Profile profile) { return Set.of(LITE, LITE_ALIAS).contains(profile.model()); }
    public static boolean layers(Profile profile) { return LAYERS.equals(profile.operation()); }
    public static int maximumImages(Profile profile) { return layers(profile) ? 1 : proFamily(profile) ? 10 : 14; }
    public static boolean present(JsonNode values, String key) {
        JsonNode value = values.get(key);
        return value != null && !value.isNull() && (!value.isTextual() || !value.asText().isBlank());
    }
    public static int imageCount(Profile profile, JsonNode values) {
        int count = 0;
        for (int i = 1; i <= maximumImages(profile); i++) if (present(values, "image" + i)) count++;
        return count;
    }

    @Override public List<CreatorInputField> inputs(Profile profile) {
        requireProfile(profile);
        var fields = new ArrayList<CreatorInputField>();
        fields.add(new CreatorInputField("prompt", "Prompt", layers(profile) ? "Optional instructions describing the layers to extract." : null,
                RunningHubDefinition.FieldType.STRING, !layers(profile), null, null, null, MAX_PROMPT_LENGTH,
                null, false, RunningHubDefinition.Source.PROMPT, null));
        for (int i = 1; i <= maximumImages(profile); i++) fields.add(new CreatorInputField("image" + i,
                layers(profile) ? "Image to decompose" : "Reference image " + i,
                "Exact authorized image version; slots are submitted in number order.", RunningHubDefinition.FieldType.IMAGE,
                i == 1 && (layers(profile) || EDIT.equals(profile.operation())), null, null, null, null, null, false, null, null));
        fields.add(new CreatorInputField("size", "Output size", sizeDescription(profile),
                layers(profile) ? RunningHubDefinition.FieldType.SELECT : RunningHubDefinition.FieldType.STRING,
                false, N.stringNode(defaultSize(profile)), null, null, layers(profile) ? null : 32,
                options(layers(profile) ? List.of("auto", "1K", "1.5K", "2K") : proFamily(profile)
                        ? List.of("1K", "1.5K", "2K") : List.of("2K", "3K", "4K")), false, null, null));
        if (proFamily(profile) || lite(profile)) fields.add(select("output_format", "Output format", List.of("jpeg", "png"), "jpeg", null));
        if (proFamily(profile) && !layers(profile)) fields.add(select("background", "Background",
                List.of("opaque", "transparent"), "opaque", "Transparent output requires one reference with an alpha channel and PNG output."));
        fields.add(select("optimize_prompt_mode", "Prompt optimization", PRO.equals(profile.model())
                ? List.of("standard", "fast") : List.of("standard"), "standard", null));
        if (!proFamily(profile)) {
            fields.add(select("sequential_image_generation", "Group generation", List.of("disabled", "auto"), "disabled",
                    "Auto chooses the number of related outputs; reference images plus actual generated images cannot exceed 15."));
            fields.add(new CreatorInputField("max_images", "Maximum generated images", "Upper bound, not an exact output count.",
                    RunningHubDefinition.FieldType.INTEGER, false, N.numberNode(15), BigDecimal.ONE, BigDecimal.valueOf(15), null,
                    null, false, null, new RunningHubDefinition.Condition("sequential_image_generation", N.stringNode("auto"))));
        }
        if (lite(profile)) fields.add(bool("web_search", "Web search", false,
                "The model may search current public information. Search usage is separate from image output count."));
        fields.add(bool("watermark", "AI watermark", true, null));
        if (EDIT.equals(profile.operation())) fields.add(new CreatorInputField("edit_regions", "Edit regions (JSON)",
                "Optional array of {image:1,type:point|bbox,coordinates:[0..999],instruction:text}; at most 16 regions. Drawn arrows can instead be included in the reference image.",
                RunningHubDefinition.FieldType.STRING, false, null, null, null, MAX_EDIT_JSON_LENGTH, null, true, null, null));
        return List.copyOf(fields);
    }

    @Override public MediaAdapterRegistry.Declaration declaration(Profile profile) {
        requireProfile(profile);
        return new MediaAdapterRegistry.Declaration(MediaPlatform.ARK, Task.Kind.IMAGE_GENERATION, 0, 0, false,
                maximumImages(profile), Set.of(), null, false, Set.of(), Set.of(), Set.of(), false, false, 0, 0);
    }

    @Override public void validate(Profile profile, JsonNode values, boolean complete) {
        requireProfile(profile);
        if (values == null || !values.isObject()) throw invalid();
        int count = imageCount(profile, values);
        // Contiguous numbering makes prompt image numbers unambiguous and independent of node ordering.
        if (complete) for (int i = 1; i <= count; i++) if (!present(values, "image" + i)) throw invalid();
        if (complete && (layers(profile) || EDIT.equals(profile.operation())) && count == 0) throw invalid();
        if (present(values, "size")) validateSize(profile, values.path("size").asText());
        if ("transparent".equals(values.path("background").asText())) {
            if (!proFamily(profile) || layers(profile) || count > 1 || complete && count != 1
                    || !"png".equals(values.path("output_format").asText("png"))) throw invalid();
        }
        if (present(values, "edit_regions")) editRegions(profile, values, complete);
        if (complete && !layers(profile) && (!present(values, "prompt")
                || values.path("prompt").asText().codePointCount(0, values.path("prompt").asText().length()) > MAX_PROMPT_LENGTH)) throw invalid();
    }

    @Override public void validateMetadata(Profile profile, String slot, Asset asset, JsonNode values) {
        requireProfile(profile);
        if (!slot.matches("image(?:[1-9]|1[0-4])") || Integer.parseInt(slot.substring(5)) > maximumImages(profile)
                || asset.mediaKind() != Asset.MediaKind.IMAGE || asset.byteSize() < 1 || asset.byteSize() > MAX_INPUT_BYTES
                || asset.width() == null || asset.height() == null
                || !(layers(profile) ? Set.of("image/png", "image/jpeg") : Set.of("image/png", "image/jpeg", "image/webp")).contains(asset.contentType())) throw invalid();
        dimensions(asset.width(), asset.height(), layers(profile));
        if ("transparent".equals(values.path("background").asText()) && "image/jpeg".equals(asset.contentType())) throw invalid();
    }

    @Override public void validateProbe(Profile profile, String slot, JsonNode values, JsonNode probe) {
        if (!"transparent".equals(values.path("background").asText())) return;
        boolean alpha = false;
        for (JsonNode stream : probe.path("streams")) if ("video".equals(stream.path("codec_type").asText())) {
            String format = stream.path("pix_fmt").asText();
            alpha = format.startsWith("rgba") || format.startsWith("bgra") || format.startsWith("argb")
                    || format.startsWith("abgr") || format.startsWith("yuva") || format.startsWith("gbrap")
                    || format.startsWith("ya") || "pal8".equals(format);
        }
        if (!alpha) throw invalid();
    }

    public static void dimensions(int width, int height, boolean layers) {
        long pixels = (long) width * height;
        if (width <= 14 || height <= 14 || pixels < (layers ? 262_144 : 196) || pixels > 36_000_000
                || (long) width > 16L * height || (long) height > 16L * width) throw invalid();
    }

    @Override public Integer resultCount(Profile profile, JsonNode values) {
        requireProfile(profile);
        return layers(profile) || "auto".equals(values.path("sequential_image_generation").asText()) ? null : 1;
    }

    public static int maximumResults(Profile profile, JsonNode values) {
        return layers(profile) ? 17 : "auto".equals(values.path("sequential_image_generation").asText())
                ? Math.min(values.path("max_images").asInt(15), 15 - imageCount(profile, values)) : 1;
    }

    @Override public String renderMention(Profile profile, JsonNode values, String slot) {
        requireProfile(profile);
        if (!slot.matches("image(?:[1-9]|1[0-4])") || !present(values, slot)) throw invalid();
        return "图 " + slot.substring(5);
    }

    @Override public String resolutionTier(Profile profile, JsonNode values) {
        String size = values.path("size").asText(defaultSize(profile));
        if ("auto".equals(size)) return null;
        if (size.matches("(?:1|1.5|2|3|4)K")) return size;
        int[] dimensions = explicitSize(size);
        long pixels = (long) dimensions[0] * dimensions[1];
        return pixels <= 1024L * 1024 ? "1K" : pixels <= 1536L * 1536 ? "1.5K"
                : pixels <= 2048L * 2048 ? "2K" : pixels <= 3072L * 3072 ? "3K" : "4K";
    }

    public static String defaultSize(Profile profile) { return layers(profile) ? "auto" : proFamily(profile) ? "2K" : "2048x2048"; }

    public static void validateSize(Profile profile, String size) {
        List<String> presets = layers(profile) ? List.of("auto", "1K", "1.5K", "2K")
                : proFamily(profile) ? List.of("1K", "1.5K", "2K") : lite(profile) ? List.of("2K", "3K", "4K")
                : List.of("2K", "3K", "4K");
        if (presets.contains(size)) return;
        if (layers(profile)) throw invalid();
        int[] dimensions = explicitSize(size);
        long pixels = (long) dimensions[0] * dimensions[1];
        long minimum = proFamily(profile) ? 921_600 : 3_686_400;
        long maximum = proFamily(profile) ? 4_624_220 : 16_777_216;
        if (pixels < minimum || pixels > maximum || (long) dimensions[0] > 16L * dimensions[1]
                || (long) dimensions[1] > 16L * dimensions[0]) throw invalid();
    }

    private static int[] explicitSize(String size) {
        if (size == null || !size.matches("[1-9][0-9]{0,5}x[1-9][0-9]{0,5}")) throw invalid();
        String[] values = size.split("x");
        return new int[]{Integer.parseInt(values[0]), Integer.parseInt(values[1])};
    }

    /** Finite helper schema renders official prompt tags; it never becomes an upstream body fragment. */
    public static JsonNode editRegions(Profile profile, JsonNode values, boolean complete) {
        if (!EDIT.equals(profile.operation()) || !values.path("edit_regions").isTextual()) throw invalid();
        String raw = values.path("edit_regions").asText();
        if (raw.length() > MAX_EDIT_JSON_LENGTH) throw invalid();
        JsonNode regions;
        try { regions = JSON.readTree(raw); } catch (RuntimeException malformed) { throw invalid(); }
        if (!regions.isArray() || regions.isEmpty() || regions.size() > 16) throw invalid();
        for (var region : regions) {
            if (!region.isObject() || !region.propertyNames().equals(Set.of("image", "type", "coordinates", "instruction"))
                    || !region.path("image").isIntegralNumber() || !region.path("image").canConvertToInt()
                    || region.path("image").asInt() < 1 || region.path("image").asInt() > maximumImages(profile)
                    || complete && !present(values, "image" + region.path("image").asInt())
                    || !region.path("type").isTextual() || !Set.of("point", "bbox").contains(region.path("type").asText())
                    || !region.path("instruction").isTextual() || region.path("instruction").asText().isBlank()
                    || region.path("instruction").asText().length() > 1000) throw invalid();
            JsonNode coordinates = region.path("coordinates");
            if (!coordinates.isArray() || coordinates.size() != ("point".equals(region.path("type").asText()) ? 2 : 4)) throw invalid();
            for (JsonNode coordinate : coordinates) if (!coordinate.isIntegralNumber() || !coordinate.canConvertToInt()
                    || coordinate.asInt() < 0 || coordinate.asInt() > 999) throw invalid();
            if (coordinates.size() == 4 && (coordinates.get(0).asInt() >= coordinates.get(2).asInt()
                    || coordinates.get(1).asInt() >= coordinates.get(3).asInt())) throw invalid();
        }
        return regions;
    }

    public static String prompt(Profile profile, JsonNode values) {
        StringBuilder result = new StringBuilder(values.path("prompt").asText(""));
        if (present(values, "edit_regions")) for (JsonNode region : editRegions(profile, values, true)) {
            String type = region.path("type").asText();
            result.append("\n图 ").append(region.path("image").asInt()).append(" <").append(type).append('>');
            for (int i = 0; i < region.path("coordinates").size(); i++) {
                if (i > 0) result.append(' ');
                result.append(region.path("coordinates").get(i).asInt());
            }
            result.append("</").append(type).append("> ").append(region.path("instruction").asText());
        }
        if (result.codePointCount(0, result.length()) > MAX_PROMPT_LENGTH) throw invalid();
        return result.toString();
    }

    private void requireProfile(Profile profile) { if (profile == null || !profiles().contains(profile)) throw invalid(); }
    private static String sizeDescription(Profile p) {
        return layers(p) ? "Auto follows the original aspect ratio within the 1K–2K range; explicit width/height is unsupported."
                : proFamily(p) ? "1K, 1.5K, 2K, or widthxheight; 921600–4624220 pixels; aspect ratio 1/16–16."
                : "2K, 3K, 4K, or widthxheight; 3686400–16777216 pixels; aspect ratio 1/16–16.";
    }
    private static List<RunningHubDefinition.Option> options(List<String> values) {
        return values.stream().map(value -> new RunningHubDefinition.Option(value, N.stringNode(value))).toList();
    }
    private static CreatorInputField select(String key, String label, List<String> values, String initial, String description) {
        return new CreatorInputField(key, label, description, RunningHubDefinition.FieldType.SELECT, false,
                N.stringNode(initial), null, null, null, options(values), false, null, null);
    }
    private static CreatorInputField bool(String key, String label, boolean initial, String description) {
        return new CreatorInputField(key, label, description, RunningHubDefinition.FieldType.BOOLEAN, false,
                N.booleanNode(initial), null, null, null, null, false, null, null);
    }
    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.media-capability-service.media-capability-does-not-support-this-input"),
                ApiMessage.of("api.media-capability-service.media-capability-does-not-support-this-input"), false);
    }
}
