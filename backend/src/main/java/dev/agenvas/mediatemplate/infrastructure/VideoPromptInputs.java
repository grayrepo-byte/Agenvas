package dev.agenvas.mediatemplate.infrastructure;

import static dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Input markers describe missing files; generated covers and clips never satisfy them. */
final class VideoPromptInputs {
    private static final Pattern NUMBERED = Pattern.compile(
            "(?iu)(?:@\\s*|<\\s*)?(image|img|picture|video|audio|图片|图像|视频|音频|图)\\s*(\\d+)(?![\\p{L}\\p{N}])(?:\\s*>)?");
    private static final Pattern REQUIRED = Pattern.compile(
            "(?iu)(?:(?:reference|input|uploaded|provided|attached)\\s+(image|picture|video|audio)"
            + "|(image|picture|video|audio)\\s+reference|(?:参考|输入|上传|提供)(?:的)?(图片|图像|视频|音频))");
    record Inputs(VideoMode mode, List<MissingReference> missing) {}

    static VideoMode mode(String mode) {
        return switch (mode.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "", "text-to-video" -> VideoMode.text_to_video;
            case "image-to-video", "first-last-frame", "start-end" -> VideoMode.image_to_video;
            case "video-to-video", "video-reference" -> VideoMode.video_reference;
            case "audio-driven", "reference-to-video", "multimodal", "omni-reference" -> VideoMode.omni_reference;
            default -> throw ThirdPartyPromptValidation.invalid();
        };
    }

    static Inputs resolve(String prompt, VideoMode declared, List<Reference> references, List<String> ingredients, boolean audioDriven) {
        var required = new LinkedHashMap<String, MissingReference>();
        var numbered = NUMBERED.matcher(prompt);
        while (numbered.find()) {
            var kind = kind(numbered.group(1));
            required.putIfAbsent(kind + ":" + numbered.group(2), new MissingReference(kind, kind + " " + numbered.group(2)));
        }
        for (String text : java.util.stream.Stream.concat(java.util.stream.Stream.of(prompt), ingredients.stream()).toList()) {
            var generic = REQUIRED.matcher(text);
            while (generic.find()) {
                String token = generic.group(1) != null ? generic.group(1) : generic.group(2) != null ? generic.group(2) : generic.group(3);
                requireKind(required, kind(token));
            }
        }
        if (audioDriven) requireKind(required, MediaKind.AUDIO);
        if (declared == VideoMode.image_to_video) requireKind(required, MediaKind.IMAGE);
        if (declared == VideoMode.video_reference) requireKind(required, MediaKind.VIDEO);
        if (declared == VideoMode.omni_reference && required.isEmpty() && references.isEmpty()) requireKind(required, MediaKind.IMAGE);

        var kinds = new java.util.HashSet<MediaKind>();
        references.forEach(r -> kinds.add(r.kind())); required.values().forEach(r -> kinds.add(r.kind()));
        VideoMode actual = declared;
        if (kinds.contains(MediaKind.AUDIO) || kinds.size() > 1) actual = VideoMode.omni_reference;
        else if (declared == VideoMode.text_to_video && !kinds.isEmpty()) actual = kinds.contains(MediaKind.VIDEO)
                ? VideoMode.video_reference : VideoMode.image_to_video;
        var remaining = new java.util.EnumMap<MediaKind, Integer>(MediaKind.class);
        references.forEach(r -> remaining.merge(r.kind(), 1, Integer::sum));
        var missing = new ArrayList<MissingReference>();
        for (var requirement : required.values()) {
            int count = remaining.getOrDefault(requirement.kind(), 0);
            if (count == 0) missing.add(requirement);
            else remaining.put(requirement.kind(), count - 1);
        }
        return new Inputs(actual, List.copyOf(missing));
    }
    private static void requireKind(LinkedHashMap<String, MissingReference> required, MediaKind kind) {
        if (required.values().stream().noneMatch(r -> r.kind() == kind))
            required.put(kind.name(), new MissingReference(kind, kind.name()));
    }
    private static MediaKind kind(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "video", "视频" -> MediaKind.VIDEO;
            case "audio", "音频" -> MediaKind.AUDIO;
            default -> MediaKind.IMAGE;
        };
    }
}
