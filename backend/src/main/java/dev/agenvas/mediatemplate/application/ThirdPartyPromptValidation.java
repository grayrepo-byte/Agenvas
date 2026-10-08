package dev.agenvas.mediatemplate.application;

import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Reject malformed feeds atomically, before any cached entry or last-success state changes. */
public final class ThirdPartyPromptValidation {
    public static final int MAX_ENTRIES = 30_000;
    public static final int MAX_VIDEO_PROMPT_LENGTH = 64_000;
    private ThirdPartyPromptValidation() {}
    public static List<ThirdPartyPrompt> validate(ThirdPartyPromptSource source, List<ThirdPartyPrompt> prompts) {
        if (prompts.isEmpty() || prompts.size() > MAX_ENTRIES) throw invalid();
        var ids = new HashSet<String>();
        for (ThirdPartyPrompt p : prompts) {
            if (p == null || !source.id().equals(p.sourceId()) || p.id() == null
                    || !p.id().startsWith(source.id() + ":") || p.id().length() > 512 || p.id().length() <= source.id().length() + 1
                    || !ids.add(p.id()) || blank(p.title(), 500) || blank(p.prompt(), p instanceof Video ? MAX_VIDEO_PROMPT_LENGTH : MediaTemplateService.MAX_PROMPT_LENGTH)
                    || p.description() == null || p.description().length() > 20_000
                    || p.tags() == null || p.tags().size() > 40
                    || p.tags().stream().anyMatch(tag -> blank(tag, 160))
                    || p.author() == null || p.author().length() > 500
                    || p.createdAt() == null || p.createdAt().length() > 80) throw invalid();
            optionalUrl(p.coverUrl()); optionalUrl(p.sourceUrl());
            if (p instanceof Image image) {
                if (source.targetKind() != TargetKind.IMAGE || image.imageMode() == null || image.imageModel() == null || image.imageModel().length() > 160) throw invalid();
                urls(image.referenceImageUrls());
            } else if (p instanceof Video video) {
                if (source.targetKind() != TargetKind.VIDEO || video.videoMode() == null || video.videoModel() == null || video.videoModel().length() > 160
                        || video.references() == null || video.references().size() > 16) throw invalid();
                optionalUrl(video.previewVideoUrl());
                if (video.missingReferences().size() > 16 || video.missingReferences().stream()
                        .anyMatch(r -> r == null || r.kind() == null || blank(r.label(), 160))
                        || new HashSet<>(video.missingReferences()).size() != video.missingReferences().size()) throw invalid();
                var refs = new HashSet<String>();
                for (Reference ref : video.references()) {
                    if (ref == null || ref.kind() == null || ref.role() == null || !refs.add(ref.url())) throw invalid();
                    url(ref.url());
                    boolean validRole = switch (ref.kind()) {
                        case IMAGE -> ref.role() == Role.REFERENCE || ref.role() == Role.START_FRAME || ref.role() == Role.END_FRAME;
                        case VIDEO -> ref.role() == Role.VIDEO_REFERENCE;
                        case AUDIO -> ref.role() == Role.AUDIO_REFERENCE;
                    };
                    if (!validRole) throw invalid();
                }
                long starts = video.references().stream().filter(r -> r.role() == Role.START_FRAME).count();
                long ends = video.references().stream().filter(r -> r.role() == Role.END_FRAME).count();
                if (starts > 1 || ends > 1 || ends > starts) throw invalid();
                switch (video.videoMode()) {
                    case text_to_video -> { if (!video.references().isEmpty() || !video.missingReferences().isEmpty()) throw invalid(); }
                    case image_to_video -> {
                        if (video.references().isEmpty() && video.missingReferences().isEmpty()
                                || video.references().stream().anyMatch(r -> r.kind() != MediaKind.IMAGE)
                                || video.missingReferences().stream().anyMatch(r -> r.kind() != MediaKind.IMAGE)) throw invalid();
                        if (starts == 1 && video.references().stream().anyMatch(r -> r.role() == Role.REFERENCE)) throw invalid();
                    }
                    case video_reference -> { if (video.references().stream().noneMatch(r -> r.kind() == MediaKind.VIDEO)
                            && video.missingReferences().stream().noneMatch(r -> r.kind() == MediaKind.VIDEO)) throw invalid(); }
                    case omni_reference -> { if (video.references().isEmpty() && video.missingReferences().isEmpty()) throw invalid(); }
                    case text_to_image_to_video -> { if (video.imageGeneration() == null) throw invalid(); }
                }
                if ((video.videoMode() == VideoMode.video_reference || video.videoMode() == VideoMode.omni_reference)
                        && (starts != 0 || ends != 0)) throw invalid();
                if (video.imageGeneration() != null) {
                    if (blank(video.imageGeneration().prompt(), 20_000) || video.imageGeneration().imageModel() == null) throw invalid();
                    urls(video.imageGeneration().referenceImageUrls());
                }
            }
        }
        return List.copyOf(prompts);
    }
    private static boolean blank(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
    private static void urls(List<String> urls) {
        if (urls == null || urls.size() > 8 || new HashSet<>(urls).size() != urls.size()) throw invalid();
        urls.forEach(ThirdPartyPromptValidation::url);
    }
    private static void optionalUrl(String value) { if (value == null) throw invalid(); if (!value.isEmpty()) url(value); }
    /** Only public HTTPS destinations can be used; the transport also pins checked DNS answers. */
    public static URI url(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 4096 || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) throw invalid();
            return uri;
        } catch (IllegalArgumentException | NullPointerException e) { throw invalid(); }
    }
    public static ApiProblemException invalid() { return problem(HttpStatus.BAD_REQUEST, "THIRD_PARTY_PROMPT_INVALID", ApiMessage.of("api.third-party.invalid")); }
    /** Callers use literal message keys so catalog coverage can be verified statically. */
    public static ApiProblemException problem(HttpStatus status, String code, ApiMessage message) {
        return new ApiProblemException(status, code, message, message, false);
    }
}
