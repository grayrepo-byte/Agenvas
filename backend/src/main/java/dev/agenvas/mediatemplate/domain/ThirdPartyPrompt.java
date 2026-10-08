package dev.agenvas.mediatemplate.domain;

import java.util.List;

/** Media-specific wire formats share identity and attribution, not generation inputs. */
public sealed interface ThirdPartyPrompt permits ThirdPartyPrompt.Image, ThirdPartyPrompt.Video {
    String id();
    String sourceId();
    String title();
    String prompt();
    String description();
    String coverUrl();
    List<String> tags();
    String author();
    String sourceUrl();
    String createdAt();

    enum Format { NATIVE_JSON, GITHUB_MARKDOWN, DAVID_JSON }
    enum ImageMode { generate, edit }
    enum VideoMode { text_to_video, image_to_video, video_reference, omni_reference, text_to_image_to_video }
    enum MediaKind { IMAGE, VIDEO, AUDIO }
    enum Role { REFERENCE, START_FRAME, END_FRAME, VIDEO_REFERENCE, AUDIO_REFERENCE }

    record Image(String id, String sourceId, String title, String prompt, String description,
            String coverUrl, List<String> referenceImageUrls, List<String> tags, String author,
            String sourceUrl, String createdAt, ImageMode imageMode, String imageModel) implements ThirdPartyPrompt {}

    /** A generated preview is never implicitly a generation input. Roles and order are explicit. */
    record Reference(MediaKind kind, Role role, String url) {}
    record ImageGeneration(String prompt, String imageModel, List<String> referenceImageUrls) {}
    record Video(String id, String sourceId, String title, String prompt, String description,
            String coverUrl, List<String> tags, String author, String sourceUrl, String createdAt,
            VideoMode videoMode, String videoModel, List<Reference> references,
            ImageGeneration imageGeneration) implements ThirdPartyPrompt {}
}
