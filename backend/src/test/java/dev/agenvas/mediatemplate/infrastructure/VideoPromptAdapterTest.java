package dev.agenvas.mediatemplate.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Synthetic upstream layouts; output previews must never be promoted into input references. */
class VideoPromptAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ThirdPartyPromptSource source(Format format, String model) {
        return new ThirdPartyPromptSource("fixture-video", "Fixture", TargetKind.VIDEO, format,
                "https://example.com/api/prompts?domain=video&model=" + model, model, true, 0, Instant.EPOCH, null, null, 0, false);
    }
    private String ipgItem(String id, String model, String prompt) {
        return mapper.writeValueAsString(java.util.Map.of("id", id, "domain", "video", "model", model,
                "title", "Synthetic video", "prompt", prompt, "generationMode", "text-to-video", "tags", List.of("fixture")));
    }
    @Test void youmindRetainsFullPromptStableGalleryIdentityAndGeneratedVideoOnlyAsPreview() {
        var source = source(Format.GITHUB_MARKDOWN, "seedance-2-0");
        String body = """
                ## Featured
                ### No. 1: Synthetic clip
                #### Description
                Synthetic description.
                #### Prompt
                ```
                Animate @Image1, matching @Video1 and @Audio1.
                ### Preserve this heading in the prompt
                ```
                #### Video
                <a href="https://example.com/output.mp4"><img src="https://example.com/poster.jpg"></a>
                [Watch Video](https://youmind.com/en-US/seedance-2-0-prompts?id=42)
                #### Details
                - **Author:** [Fixture author](https://example.com/author)
                - **Source:** [Post](https://example.com/original)
                - **Published:** October 8, 2026
                """;
        var adapter = new MarkdownPromptAdapter();
        var entries = ThirdPartyPromptValidation.validate(source, adapter.parse(source, body + body));
        assertThat(entries).hasSize(1);
        var video = (Video) entries.getFirst();
        assertThat(video.id()).isEqualTo("fixture-video:42");
        assertThat(video.prompt()).contains("### Preserve this heading");
        assertThat(video.previewVideoUrl()).isEqualTo("https://example.com/output.mp4");
        assertThat(video.coverUrl()).isEqualTo("https://example.com/poster.jpg");
        assertThat(video.references()).isEmpty();
        assertThat(video.videoMode()).isEqualTo(VideoMode.omni_reference);
        assertThat(video.missingReferences()).extracting(MissingReference::kind).containsExactly(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO);
        assertThat(video.createdAt()).isEqualTo("2026-10-08");
        assertThat(((Video) adapter.parse(source, body.replace("Animate", "Transform")).getFirst()).id()).isEqualTo(video.id());
    }
    @Test void explicitlyPublishedYoumindInputIsUsableAndExcludedFromTheCover() {
        var source = source(Format.GITHUB_MARKDOWN, "seedance-2-0");
        var entries = new MarkdownPromptAdapter().parse(source, """
                ### Synthetic input
                #### Prompt
                ```
                Animate image 1.
                ```
                #### Reference Images
                <img src="https://example.com/input.png">
                #### Generated Images
                <img src="https://example.com/output.png">
                """);
        var video = (Video) ThirdPartyPromptValidation.validate(source, entries).getFirst();
        assertThat(video.references()).containsExactly(new Reference(MediaKind.IMAGE, Role.REFERENCE, "https://example.com/input.png"));
        assertThat(video.missingReferences()).isEmpty();
        assertThat(video.coverUrl()).endsWith("output.png");
    }
    @Test void youmindFetchCombinesReadmeWithTheSeparateOutputVideoManifest() {
        var source = new ThirdPartyPromptSource("fixture-video", "Fixture", TargetKind.VIDEO, Format.GITHUB_MARKDOWN,
                "https://raw.githubusercontent.com/YouMind-OpenLab/awesome-seedance-2-prompts/main/README.md", "seedance-2-0",
                true, 0, Instant.EPOCH, null, null, 0, false);
        var http = mock(ThirdPartyPromptHttpClient.class);
        when(http.feed(source.url())).thenReturn("### Synthetic\n#### Prompt\n```\nCamera pans.\n```\n[Watch](https://youmind.com/seedance-2-0-prompts?id=42)\n");
        when(http.feed("https://raw.githubusercontent.com/YouMind-OpenLab/awesome-seedance-2-prompts/main/video-urls.json"))
                .thenReturn("{\"prompts\":{\"42\":\"https://example.com/output.mp4\"}}");
        var video = (Video) ThirdPartyPromptValidation.validate(source, new MarkdownPromptAdapter().fetch(source, http)).getFirst();
        assertThat(video.previewVideoUrl()).isEqualTo("https://example.com/output.mp4");
        assertThat(video.references()).isEmpty(); assertThat(video.missingReferences()).isEmpty();
    }
    @Test void beatApiReadsTheFullLocalizedCatalogAndRecordsEachUnpublishedInput() {
        var source = source(Format.BEATAPI_JSON, "minimax-h3");
        String body = """
                {"version":1,"prompts":[{"slug":"fixture-clip","title":{"zh":"合成短片","en":"Synthetic clip"},
                "description":{"zh":"合成说明"},"category":"product-commercial","mode":"reference-to-video",
                "ingredients":["Text prompt","Image reference"],"source":{"name":"Fixture author","url":"https://example.com/post"},
                "prompt":"Keep <Picture 1> and <Picture 2> consistent.\\nMove the camera slowly.",
                "video":"https://example.com/result.webm","thumbnail":"https://example.com/poster.jpg"}]}
                """;
        var adapter = new BeatApiPromptAdapter(mapper);
        var video = (Video) ThirdPartyPromptValidation.validate(source, adapter.parse(source, body)).getFirst();
        assertThat(video.title()).isEqualTo("合成短片");
        assertThat(video.prompt()).endsWith("Move the camera slowly.");
        assertThat(video.references()).isEmpty();
        assertThat(video.missingReferences()).hasSize(2);
        assertThat(video.videoMode()).isEqualTo(VideoMode.omni_reference);
        assertThat(video.sourceUrl()).isEqualTo("https://example.com/post");
        assertThat(video.previewVideoUrl()).endsWith("result.webm");
        assertThat(((Video) adapter.parse(source, body.replace("Move the camera slowly", "Pan right")).getFirst()).id()).isEqualTo(video.id());
    }
    @Test void gallerySupportsAllThreeModelsAndCorrectsTextModeWhenThePromptNeedsReferences() {
        var adapter = new ImagePromptGalleryAdapter(mapper);
        for (var model : List.of("seedance-2-0", "seedance-2-5", "minimax-h3")) {
            var source = source(Format.IMAGE_PROMPT_GALLERY_JSON, model);
            var video = (Video) ThirdPartyPromptValidation.validate(source,
                    adapter.parse(source, "{\"prompts\":[" + ipgItem("same-id", model, "Use video 1 and image 1 as references.") + "]}")).getFirst();
            assertThat(video.videoModel()).isEqualTo(model);
            assertThat(video.videoMode()).isEqualTo(VideoMode.omni_reference);
            assertThat(video.missingReferences()).hasSize(2);
            assertThat(video.id()).isEqualTo("fixture-video:same-id");
            assertThat(adapter.parse(source, "[" + ipgItem("foreign", "other-model", "Synthetic prompt") + "]")).isEmpty();
        }
    }
    @Test void galleryKeepsExplicitInputsSeparateFromPreviewAndDetectsMissingNumberedImages() {
        var source = source(Format.IMAGE_PROMPT_GALLERY_JSON, "seedance-2-5");
        String body = """
                {"items":[{"id":"fixture","domain":"video","model":"seedance-2-5","title":"Synthetic", "tags":[],
                "prompt":"Animate @Image1 and @Image2.","inputMode":"image_to_video",
                "coverUrl":"https://example.com/poster.png","previewVideoUrl":"https://example.com/output.mp4",
                "references":[{"kind":"IMAGE","role":"START_FRAME","url":"https://example.com/input.png"}]}]}
                """;
        var video = (Video) ThirdPartyPromptValidation.validate(source, new ImagePromptGalleryAdapter(mapper).parse(source, body)).getFirst();
        assertThat(video.references()).hasSize(1);
        assertThat(video.missingReferences()).containsExactly(new MissingReference(MediaKind.IMAGE, "IMAGE 2"));
    }
    @Test void nativeVideoWithoutNewOptionalFieldsRemainsReadable() {
        var source = source(Format.NATIVE_JSON, "model");
        var video = new Video("fixture-video:old", source.id(), "Synthetic", "Camera moves", "", "", List.of(), "", "", "",
                VideoMode.text_to_video, "model", List.of(), null);
        var old = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(video).deepCopy(); old.remove("missingReferences"); old.remove("previewVideoUrl");
        var parsed = (Video) ThirdPartyPromptValidation.validate(source,
                new NativeJsonPromptAdapter(mapper).parse(source, "[" + mapper.writeValueAsString(old) + "]")).getFirst();
        assertThat(parsed.previewVideoUrl()).isEmpty(); assertThat(parsed.missingReferences()).isEmpty();
    }
    @Test void fullLongVideoPromptsCanBeCachedWithoutRelaxingImagePromptLimits() {
        var source = source(Format.NATIVE_JSON, "model");
        var prompt = "Synthetic ".repeat(2500);
        var video = new Video("fixture-video:long", source.id(), "Synthetic long prompt", prompt, "", "", List.of(), "", "", "",
                VideoMode.text_to_video, "model", List.of(), null);
        assertThat(ThirdPartyPromptValidation.validate(source, List.of(video))).containsExactly(video);
        var imageSource = new ThirdPartyPromptSource(source.id(), source.name(), TargetKind.IMAGE, source.format(), source.url(), source.model(),
                true, 0, Instant.EPOCH, null, null, 0, false);
        var image = new Image(video.id(), source.id(), video.title(), prompt, "", "", List.of(), List.of(), "", "", "", ImageMode.generate, "model");
        assertThatThrownBy(() -> ThirdPartyPromptValidation.validate(imageSource, List.of(image))).isInstanceOf(RuntimeException.class);
    }
    @Test void galleryFetchesAllExplicitPagesAndPreservesModelFilters() {
        var source = source(Format.IMAGE_PROMPT_GALLERY_JSON, "seedance-2-0");
        var http = mock(ThirdPartyPromptHttpClient.class);
        when(http.feed(source.url())).thenReturn("{\"items\":[" + ipgItem("one", source.model(), "Camera pans") + "],\"total\":2,\"nextCursor\":\"second+/=\"}");
        when(http.feed(source.url() + "&cursor=second%2B%2F%3D")).thenReturn("{\"items\":[" + ipgItem("two", source.model(), "Camera tilts") + "],\"total\":2}");
        assertThat(ThirdPartyPromptValidation.validate(source, new ImagePromptGalleryAdapter(mapper).fetch(source, http))).hasSize(2);
        verify(http).feed(source.url() + "&cursor=second%2B%2F%3D");
    }
    @Test void incompleteOrRedirectingGalleryPaginationFailsWithoutPublishingAPartialFeed() {
        var source = source(Format.IMAGE_PROMPT_GALLERY_JSON, "seedance-2-0");
        var adapter = new ImagePromptGalleryAdapter(mapper);
        var http = mock(ThirdPartyPromptHttpClient.class);
        String page = "{\"items\":[" + ipgItem("one", source.model(), "Camera pans") + "],\"total\":2}";
        when(http.feed(source.url())).thenReturn(page);
        assertThatThrownBy(() -> adapter.fetch(source, http)).isInstanceOf(RuntimeException.class);
        when(http.feed(source.url())).thenReturn(page.replace("\"total\":2", "\"next\":\"https://other.example.com/feed\""));
        assertThatThrownBy(() -> adapter.fetch(source, http)).isInstanceOf(RuntimeException.class);
        verify(http, times(2)).feed(source.url()); verifyNoMoreInteractions(http);
    }
}
