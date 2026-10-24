package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.RunningHubDefinition;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Deterministic editor suggestions only. Unknown inputs remain manual parameters;
 * neither imported text nor a suggestion can publish a capability or run its graph. */
final class RunningHubInputDiscovery {
    private static final Set<String> TEXT_INPUTS = Set.of("text", "string", "value", "prompt", "positive", "positiveprompt", "prompttext",
            "textpositive", "mainprompt", "inputtext", "textinput", "description", "caption");
    private static final Set<String> FILE_INPUTS = Set.of("image", "audio", "video", "file", "filename", "path", "url", "value", "input",
            "reference", "imagepath", "audiopath", "videopath", "imageurl", "audiourl", "videourl");
    private static final Set<String> IMAGE_INPUTS = Set.of("inputimage", "referenceimage", "refimage", "sourceimage", "initimage",
            "startimage", "endimage", "firstframe", "lastframe", "startframe", "endframe", "controlimage");
    private static final Set<String> AUDIO_INPUTS = Set.of("inputaudio", "referenceaudio", "refaudio", "sourceaudio", "voiceaudio", "referencevoice");
    private static final Set<String> VIDEO_INPUTS = Set.of("inputvideo", "referencevideo", "refvideo", "sourcevideo");
    private static final Set<String> CREATIVE_INPUTS = Set.of("aspectratio", "ratio", "resolution", "size", "width", "height",
            "imagewidth", "imageheight", "videowidth", "videoheight", "outputwidth", "outputheight", "megapixels", "outputresolution",
            "duration", "durationseconds", "seconds", "videoduration", "audioduration", "lengthseconds",
            "fps", "framerate", "framespersecond", "quality", "imagequality", "videoquality", "outputquality");
    private static final Set<String> INTERNAL_INPUTS = Set.of("seed", "noiseseed", "randomseed", "steps", "numsteps", "samplingsteps",
            "cfg", "cfgscale", "guidance", "guidancescale", "scheduler", "sampler", "samplername", "model", "modelname", "ckptname",
            "checkpoint", "filenameprefix", "upload", "forcerate", "multiple", "alignment", "pixelalignment", "roundto",
            "framemultiple", "frameoffset");
    private static final Set<String> FLOAT_PRIMITIVES = Set.of("primitivefloat", "float", "easyfloat");
    private static final Set<String> SCALAR_PRIMITIVES = Set.of("primitivefloat", "float", "easyfloat", "primitiveint", "int", "easyint",
            "primitivestring", "primitivestringmultiline", "primitiveboolean", "boolean");
    private static final Set<String> URL_LOADERS = Set.of("loadimagefromurl", "loadaudiofromurl", "loadvideofromurl",
            "loadimageurl", "loadaudiourl", "loadvideourl");
    private static final Pattern EXPLICIT_STAGE = Pattern.compile(
            "(?iu)^(stage\\s*[0-9]+|phase\\s*[0-9]+|第?[一二三四五六七八九十0-9]+阶段|阶段\\s*[一二三四五六七八九十0-9]+|基础生成|放大输出|最终输出|后期处理|base generation|output upscale|final output|post[ -]processing)(?=$|[\\s:：·—–\\-()（）])");

    enum PromptRole { NONE, TEXT, EXPLICIT, POSITIVE_CONDITIONING }
    record Hint(RunningHubDefinition.FieldType type, PromptRole promptPriority) {}

    /** JSON can serialize 15.0 as 15; a declared Float primitive must still accept fractional input. */
    static RunningHubDefinition.FieldType workflowType(JsonNode value, String fieldName, String nodeType) {
        if (value.isBoolean()) return RunningHubDefinition.FieldType.BOOLEAN;
        if (value.isNumber()) {
            if (inputName(fieldName).equals("value") && FLOAT_PRIMITIVES.contains(normalize(nodeType)))
                return RunningHubDefinition.FieldType.NUMBER;
            return value.isIntegralNumber() ? RunningHubDefinition.FieldType.INTEGER : RunningHubDefinition.FieldType.NUMBER;
        }
        return RunningHubDefinition.FieldType.STRING;
    }

    static boolean declaredWorkflowType(String fieldName, String nodeType) {
        return inputName(fieldName).equals("value") && FLOAT_PRIMITIVES.contains(normalize(nodeType));
    }

    /** These are display suggestions only; graph order and node numbers do not imply a creation stage. */
    static String group(String title) {
        if (title == null) return null;
        var match = EXPLICIT_STAGE.matcher(title.strip());
        if (!match.find()) return null;
        String group = match.group(1);
        return group.length() <= RunningHubDefinition.MAX_GROUP_LENGTH ? group : null;
    }

    /** The loader class wins over its generic image/audio/video port name.
     * Unknown classes keep the format unset for the administrator to inspect. */
    static RunningHubDefinition.ResourceFormat resourceFormat(String fieldName, String nodeType) {
        if (urlLoader(nodeType) || urlInput(fieldName)) return RunningHubDefinition.ResourceFormat.URL;
        if (fileLoader(nodeType) || inputName(fieldName).equals("filename")) return RunningHubDefinition.ResourceFormat.FILE_NAME;
        return null;
    }

    static boolean urlLoader(String nodeType) { return URL_LOADERS.contains(normalize(nodeType)); }
    static boolean fileLoader(String nodeType) { return normalize(nodeType).equals("loadimage"); }
    static boolean negativeInput(String fieldName, String nodeType, String label) {
        return negative(inputName(fieldName)) || negative(normalize(nodeType)) || negative(normalize(label));
    }

    /** A direct primitive feeding a known creative port carries that port's purpose.
     * Do not propagate through expressions, switches or arbitrary graph execution. */
    static Set<String> creativeSources(JsonNode graph) {
        Set<String> result = new HashSet<>();
        for (var node : graph.properties()) {
            String purpose = normalize(node.getValue().path("class_type").asText(""))
                    + normalize(node.getValue().path("_meta").path("title").asText(""));
            if (contains(purpose, "preview", "预览")) continue;
            for (var input : node.getValue().path("inputs").properties()) {
                if (!CREATIVE_INPUTS.contains(inputName(input.getKey()))) continue;
                String upstream = connection(input.getValue());
                if (upstream == null || input.getValue().get(1).asInt() != 0) continue;
                JsonNode primitive = graph.path(upstream);
                if (!SCALAR_PRIMITIVES.contains(normalize(primitive.path("class_type").asText("")))) continue;
                JsonNode value = primitive.path("inputs").path("value");
                if (value.isTextual() || value.isNumber() || value.isBoolean()) result.add(upstream + ":value");
            }
        }
        return Set.copyOf(result);
    }

    static Hint hint(RunningHubDefinition.FieldType declaredType, String fieldName, String nodeType, String label) {
        if (declaredType != RunningHubDefinition.FieldType.STRING) return new Hint(declaredType, PromptRole.NONE);
        String name = inputName(fieldName);
        String node = normalize(nodeType);
        String title = normalize(label);
        if (IMAGE_INPUTS.contains(name)) return new Hint(RunningHubDefinition.FieldType.IMAGE, PromptRole.NONE);
        if (AUDIO_INPUTS.contains(name)) return new Hint(RunningHubDefinition.FieldType.AUDIO, PromptRole.NONE);
        if (VIDEO_INPUTS.contains(name)) return new Hint(RunningHubDefinition.FieldType.VIDEO, PromptRole.NONE);
        if (FILE_INPUTS.contains(name)) {
            boolean loader = contains(node, "load", "upload", "input") || contains(title, "加载", "上传", "输入");
            if (contains(title, "参考图", "首帧", "尾帧", "角色图", "人物图", "商品图")
                    || loader && (contains(node, "image") || contains(title, "图片", "图像")))
                return new Hint(RunningHubDefinition.FieldType.IMAGE, PromptRole.NONE);
            if (contains(title, "参考音频", "参考声音", "音色参考")
                    || loader && (contains(node, "audio") || contains(title, "音频", "声音")))
                return new Hint(RunningHubDefinition.FieldType.AUDIO, PromptRole.NONE);
            if (contains(title, "参考视频") || loader && (contains(node, "video") || contains(title, "视频")))
                return new Hint(RunningHubDefinition.FieldType.VIDEO, PromptRole.NONE);
        }
        if (negative(name) || negative(title) || negative(node)) return new Hint(declaredType, PromptRole.NONE);
        if (TEXT_INPUTS.contains(name)) {
            if (contains(name, "prompt", "positive") || contains(title, "提示词", "正向", "正面", "prompt", "positive", "画面描述", "视频描述", "动作描述", "场景描述"))
                return new Hint(declaredType, PromptRole.EXPLICIT);
            if (contains(node, "text", "string") || contains(title, "文本", "文字")) return new Hint(declaredType, PromptRole.TEXT);
        }
        return new Hint(declaredType, PromptRole.NONE);
    }

    /** Recommend creator-facing controls without exposing sampler/model internals by default.
     * A generic value input needs an explicit purpose in its label or description. */
    static boolean recommended(RunningHubDefinition.Field field) {
        if (field.media() || field.effectiveSource() == RunningHubDefinition.Source.PROMPT) return true;
        String name = inputName(field.fieldName());
        if (INTERNAL_INPUTS.contains(name)) return false;
        String label = field.label();
        String suffix = " · " + field.fieldName();
        if (label.endsWith(suffix)) label = label.substring(0, label.length() - suffix.length());
        String purpose = normalize(label) + normalize(field.description());
        if (contains(purpose, "preview", "预览")) return false;
        if (CREATIVE_INPUTS.contains(name)) return true;
        if (field.type() == RunningHubDefinition.FieldType.STRING && (negative(name) || negative(purpose))) return true;
        return contains(purpose, "时长", "秒数", "分辨率", "画幅", "宽高比", "宽度", "高度", "帧率", "清晰度", "画质", "质量",
                "duration", "aspectratio", "resolution", "megapixel", "framerate", "quality");
    }

    /** Trace conditioning connections only to distinguish positive from negative text.
     * Connections stay out of editable fields and are never submitted as a local graph. */
    static Set<String> conditioningAncestors(JsonNode graph, boolean negative) {
        Set<String> nodes = new HashSet<>();
        var pending = new ArrayDeque<String>();
        for (var node : graph.properties()) for (var input : node.getValue().path("inputs").properties()) {
            String name = normalize(input.getKey());
            if (negative ? negative(name) : name.equals("positive") || name.equals("positiveprompt")) {
                String upstream = connection(input.getValue());
                if (upstream != null) pending.add(upstream);
            }
        }
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!nodes.add(id)) continue;
            for (var input : graph.path(id).path("inputs").properties()) {
                String upstream = connection(input.getValue());
                if (upstream != null) pending.add(upstream);
            }
        }
        return nodes;
    }

    /** Only an unambiguous primary text input gets the canvas prompt source.
     * Multiple equally plausible text inputs stay manual instead of violating the singleton source contract. */
    static int primaryPrompt(List<PromptRole> priorities) {
        PromptRole best = PromptRole.NONE;
        int chosen = -1;
        for (int i = 0; i < priorities.size(); i++) {
            PromptRole score = priorities.get(i);
            if (score.compareTo(best) > 0) { best = score; chosen = i; }
            else if (score == best) chosen = -1;
        }
        return chosen;
    }

    static boolean urlInput(String name) { return inputName(name).endsWith("url"); }

    private static String connection(JsonNode value) {
        return value.isArray() && value.size() == 2 && value.get(0).isTextual() && value.get(1).isIntegralNumber()
                ? value.get(0).asText() : null;
    }
    private static boolean negative(String value) { return contains(value, "negative", "负面", "负向", "反向"); }
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static String inputName(String value) {
        String segment = value == null ? "" : value.substring(value.lastIndexOf('.') + 1);
        return normalize(segment).replaceAll("[0-9]+$", "");
    }
    private static boolean contains(String value, String... hints) {
        for (String hint : hints) if (value.contains(hint)) return true;
        return false;
    }
}
