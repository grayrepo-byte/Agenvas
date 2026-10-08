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

    enum Format { NATIVE_JSON, GITHUB_MARKDOWN, DAVID_JSON, BEATAPI_JSON, IMAGE_PROMPT_GALLERY_JSON }
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
    /** An upstream input label whose actual file was not published; previews cannot fill it. */
    record MissingReference(MediaKind kind, String label) {}
    record Video(String id, String sourceId, String title, String prompt, String description,
            String coverUrl, List<String> tags, String author, String sourceUrl, String createdAt,
            VideoMode videoMode, String videoModel, List<Reference> references,
            ImageGeneration imageGeneration, String previewVideoUrl,
            List<MissingReference> missingReferences) implements ThirdPartyPrompt {
        public Video {
            previewVideoUrl = previewVideoUrl == null ? "" : previewVideoUrl;
            missingReferences = missingReferences == null ? List.of() : List.copyOf(missingReferences);
        }
        public Video(String id, String sourceId, String title, String prompt, String description,
                String coverUrl, List<String> tags, String author, String sourceUrl, String createdAt,
                VideoMode videoMode, String videoModel, List<Reference> references, ImageGeneration imageGeneration) {
            this(id, sourceId, title, prompt, description, coverUrl, tags, author, sourceUrl, createdAt,
                    videoMode, videoModel, references, imageGeneration, "", List.of());
        }
    }
}
