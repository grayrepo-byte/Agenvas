package dev.agenvas.mediatemplate.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.MediaKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Reference;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Role;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Video;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.VideoMode;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic fixtures for the compact public list response observed in November 2026. */
class ImagePromptGalleryAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ImagePromptGalleryAdapter adapter = new ImagePromptGalleryAdapter(mapper);

    @Test
    void compactCataloguePreservesPublicPrefillPromptInputsPreviewAndSourceModel() {
        var source = source("seedance-2-5");
        var item = compact("one", source.model(), "Animate @Image1.");
        item.put("inputMode", "reference_to_video");
        item.putArray("referenceImageUrls").add("https://example.com/input.png");
        var video = (Video) ThirdPartyPromptValidation.validate(source,
                adapter.parse(source, mapper.writeValueAsString(mapper.createArrayNode().add(item)))).getFirst();

        assertThat(video.prompt()).isEqualTo("Animate @Image1.");
        assertThat(video.videoModel()).isEqualTo("seedance-2-5");
        assertThat(video.videoMode()).isEqualTo(VideoMode.omni_reference);
        assertThat(video.references())
                .containsExactly(new Reference(MediaKind.IMAGE, Role.REFERENCE, "https://example.com/input.png"));
        assertThat(video.missingReferences()).isEmpty();
        assertThat(video.coverUrl()).isEqualTo("https://example.com/poster.png");
        assertThat(video.previewVideoUrl()).isEqualTo("https://example.com/output.mp4");
        assertThat(video.sourceUrl()).isEqualTo("https://imagepromptgallery.com/cases/synthetic-one");
        assertThat(video.tags()).contains("Cinematic", "Synthetic category");
    }

    @Test
    void compactCatalogueTextPromptDoesNotTreatItsPreviewAsAnInput() {
        var source = source("minimax-h3");
        var video = (Video) ThirdPartyPromptValidation.validate(source,
                adapter.parse(source, mapper.writeValueAsString(mapper.createArrayNode()
                        .add(compact("one", source.model(), "Camera pans slowly.")))))
                .getFirst();
        assertThat(video.videoMode()).isEqualTo(VideoMode.text_to_video);
        assertThat(video.references()).isEmpty();
        assertThat(video.previewVideoUrl()).isEqualTo("https://example.com/output.mp4");
    }

    @Test
    void offsetLimitTotalPaginationReadsEveryPageWithoutChangingTheEndpointOrFilters() {
        var source = source("seedance-2-0");
        var http = mock(ThirdPartyPromptHttpClient.class);
        String first = page(compact("one", source.model(), "Camera pans.").put("prompt", "Camera pans."), 0);
        String second = page(compact("two", source.model(), "Camera tilts.").put("prompt", "Camera tilts."), 1);
        when(http.feed(source.url())).thenReturn(first);
        when(http.feed(source.url() + "&offset=1")).thenReturn(second);

        var videos = ThirdPartyPromptValidation.validate(source, adapter.fetch(source, http));
        assertThat(videos).extracting(value -> value.id()).containsExactly("fixture-gallery:one",
                "fixture-gallery:two");
        verify(http).feed(source.url());
        verify(http).feed(source.url() + "&offset=1");
        verifyNoMoreInteractions(http);
    }

    @Test
    void malformedOrMismatchedPrefillDoesNotPublishAnInventedPrompt() {
        var source = source("seedance-2-0");
        var item = compact("one", source.model(), "Camera pans.");
        item.put("tryHref", "https://dovoo.ai/text-to-video/seedance-2-0?s=not-json");
        var malformed = mapper.writeValueAsString(mapper.createArrayNode().add(item));
        assertThatThrownBy(() -> adapter.parse(source, malformed))
                .isInstanceOf(RuntimeException.class);
        item = compact("one", source.model(), "Camera pans.");
        item.put("slug", "another-case");
        var mismatched = mapper.writeValueAsString(mapper.createArrayNode().add(item));
        assertThatThrownBy(() -> adapter.parse(source, mismatched)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void incompleteOffsetPageFailsBeforeFetchingAnotherPage() {
        var source = source("seedance-2-0");
        var http = mock(ThirdPartyPromptHttpClient.class);
        var page = mapper.createObjectNode().put("total", 3).put("offset", 0).put("limit", 2);
        page.putArray("items").add(compact("one", source.model(), "Camera pans."));
        when(http.feed(source.url())).thenReturn(mapper.writeValueAsString(page));
        assertThatThrownBy(() -> adapter.fetch(source, http)).isInstanceOf(RuntimeException.class);
        verify(http).feed(source.url());
        verifyNoMoreInteractions(http);
    }

    private ThirdPartyPromptSource source(String model) {
        return new ThirdPartyPromptSource("fixture-gallery", "Synthetic gallery", TargetKind.VIDEO,
                Format.IMAGE_PROMPT_GALLERY_JSON,
                "https://imagepromptgallery.com/api/public/prompts?domain=video&model=" + model + "&mediaType=video",
                model,
                true, 0, Instant.EPOCH, null, null, 0, false);
    }

    private ObjectNode compact(String id, String model, String prompt) {
        var prefill = mapper.createObjectNode().put("source", "image-prompt-gallery").put("case", "synthetic-" + id)
                .put("model", "seedance-2-0").put("prompt", prompt);
        // A public launch link may offer a fallback model; it does not change the catalogue's model identity.
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(prefill));
        var item = mapper.createObjectNode().put("id", id).put("slug", "synthetic-" + id)
                .put("title", "Synthetic " + id)
                .put("domain", "video").put("model", model).put("category", "Synthetic category")
                .put("inputMode", "text_to_video")
                .put("caseHref", "/cases/synthetic-" + id)
                .put("tryHref", "https://dovoo.ai/text-to-video/seedance-2-0?prefill=ipg&s=" + encoded);
        item.putArray("referenceImageUrls");
        item.putArray("styleTags").add("Cinematic");
        item.putObject("media").put("type", "video").put("url", "https://example.com/output.mp4")
                .put("thumbnailUrl", "https://example.com/poster.png");
        return item;
    }

    private String page(ObjectNode item, int offset) {
        var page = mapper.createObjectNode().put("total", 2).put("offset", offset).put("limit", 1);
        page.putArray("items").add(item);
        return mapper.writeValueAsString(page);
    }
}
