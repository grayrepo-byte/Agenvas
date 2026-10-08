package dev.agenvas.mediatemplate.infrastructure;

import static org.assertj.core.api.Assertions.*;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ThirdPartyPromptAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ThirdPartyPromptSource source(TargetKind kind, Format format) {
        return new ThirdPartyPromptSource("fixture", "Fixture", kind, format,
                "https://raw.githubusercontent.com/example/prompts/main/README.md", "gpt-image-2", true, 0, Instant.EPOCH, null, null, 0, false);
    }
    @Test void zeroLuKeepsStableIdentityAcrossPromptEditsAndResolvesCoverWithoutUsingItAsInput() {
        String body = """
                ## Photography
                ### Product portrait
                <img src="assets/product.png" />
                **Prompt:**
                ```text
                Create a soft portrait.
                ### A heading inside the prompt
                ```
                **Source:** [@fixture](https://x.com/fixture/status/123)
                """;
        var adapter = new MarkdownPromptAdapter();
        Image first = (Image) adapter.parse(source(TargetKind.IMAGE, Format.GITHUB_MARKDOWN), body).getFirst();
        Image changed = (Image) adapter.parse(source(TargetKind.IMAGE, Format.GITHUB_MARKDOWN), body.replace("soft portrait", "sharp portrait")).getFirst();
        assertThat(first.id()).isEqualTo(changed.id());
        assertThat(first.prompt()).contains("### A heading inside the prompt");
        assertThat(first.referenceImageUrls()).isEmpty();
        assertThat(first.coverUrl()).isEqualTo("https://raw.githubusercontent.com/example/prompts/main/assets/product.png");
        ThirdPartyPromptValidation.validate(source(TargetKind.IMAGE, Format.GITHUB_MARKDOWN), List.of(first));
    }
    @Test void imgedifyReadsInlineMultilinePromptAndAttribution() {
        String body = """
                ### Card
                - **Prompt Text:** `Create a
                watercolor card.`
                - **Example Image:**
                <img src="https://example.com/card.png">
                - **Author:** [Artist](https://x.com/artist/status/321)
                """;
        Image prompt = (Image) new MarkdownPromptAdapter().parse(source(TargetKind.IMAGE, Format.GITHUB_MARKDOWN), body).getFirst();
        assertThat(prompt.prompt()).isEqualTo("Create a\nwatercolor card.");
        assertThat(prompt.author()).isEqualTo("Artist");
        assertThat(prompt.referenceImageUrls()).isEmpty();
    }
    @Test void youmindDeduplicatesFeaturedEntriesByGalleryIdentityAndDistinguishesExplicitInputs() {
        String body = """
                ### No. 1: Portrait
                ![Featured](https://img.shields.io/badge/Featured-gold)
                #### Description
                Synthetic description.
                #### Prompt
                ```
                Preserve the person's identity.
                ```
                #### Reference Images
                <img src="https://example.com/input.png">
                #### Generated Images
                <img src="https://example.com/output.png">
                #### Details
                - **Author:** [Artist](https://x.com/artist)
                - **Source:** [Post](https://x.com/artist/status/42)
                - **Published:** April 19, 2026
                **[Try it now](https://youmind.com/gpt-image-2-prompts?id=42)**
                """;
        var prompts = new MarkdownPromptAdapter().parse(source(TargetKind.IMAGE, Format.GITHUB_MARKDOWN), body + body);
        assertThat(prompts).hasSize(1);
        Image image = (Image) prompts.getFirst();
        assertThat(image.id()).isEqualTo("fixture:42");
        assertThat(image.referenceImageUrls()).containsExactly("https://example.com/input.png");
        assertThat(image.createdAt()).isEqualTo("2026-04-19");
        assertThat(image.description()).isEqualTo("Synthetic description.");
    }
    @Test void davidUsesUpstreamIdAndTreatsExampleImageAsCover() {
        var prompt = (Image) new DavidJsonPromptAdapter(mapper).parse(source(TargetKind.IMAGE, Format.DAVID_JSON), """
                [{"id":17,"title_cn":"合成参考","prompt":"Keep the input identity","needs_ref":true,"image":"images/17.png"}]
                """).getFirst();
        assertThat(prompt.id()).isEqualTo("fixture:17"); assertThat(prompt.imageMode()).isEqualTo(ImageMode.edit);
        assertThat(prompt.referenceImageUrls()).isEmpty(); assertThat(prompt.coverUrl()).endsWith("/main/images/17.png");
    }
    @Test void nativeImageStructureRoundTripsAndRejectsForeignSourceAndDuplicateIds() {
        var source = source(TargetKind.IMAGE, Format.NATIVE_JSON);
        var image = new Image("fixture:17", "fixture", "Fixture", "Synthetic prompt", "", "", List.of(), List.of(), "", "", "", ImageMode.generate, "gpt-image-2");
        var adapter = new NativeJsonPromptAdapter(mapper);
        assertThat(ThirdPartyPromptValidation.validate(source, adapter.parse(source, mapper.writeValueAsString(List.of(image))))).containsExactly(image);
        assertThatThrownBy(() -> ThirdPartyPromptValidation.validate(source, List.of(image, image))).isInstanceOf(RuntimeException.class);
        var other = new Image("foreign:17", "foreign", "Fixture", "Synthetic", "", "", List.of(), List.of(), "", "", "", ImageMode.generate, "");
        assertThatThrownBy(() -> ThirdPartyPromptValidation.validate(source, List.of(other))).isInstanceOf(RuntimeException.class);
    }
    @Test void nativeVideoPreservesStartFrameAndVideoReferencesAndRejectsMissingImageInput() {
        var source = source(TargetKind.VIDEO, Format.NATIVE_JSON);
        var video = new Video("fixture:vid", "fixture", "Video", "Camera moves", "", "", List.of(), "", "", "",
                VideoMode.image_to_video, "video-model", List.of(new Reference(MediaKind.IMAGE, Role.START_FRAME, "https://example.com/start.png")), null);
        var parsed = new NativeJsonPromptAdapter(mapper).parse(source, mapper.writeValueAsString(java.util.Map.of("items", List.of(video))));
        assertThat(ThirdPartyPromptValidation.validate(source, parsed)).containsExactly(video);
        var missing = new Video(video.id(), video.sourceId(), video.title(), video.prompt(), "", "", List.of(), "", "", "", video.videoMode(), "", List.of(), null);
        assertThatThrownBy(() -> ThirdPartyPromptValidation.validate(source, List.of(missing))).isInstanceOf(RuntimeException.class);
    }
    @Test void transportRejectsLocalAndMetadataAddressesBeforeRequests() throws Exception {
        for (String address : List.of("127.0.0.1", "169.254.169.254", "10.0.0.1", "::1", "fc00::1", "198.18.0.1"))
            assertThat(ThirdPartyPromptHttpClient.publicAddress(InetAddress.getByName(address))).isFalse();
        assertThat(ThirdPartyPromptHttpClient.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        for (String url : List.of("http://example.com/feed", "https://user:pass@example.com/feed", "https://127.0.0.1/feed", "https://[::1]/feed"))
            assertThatThrownBy(() -> new ThirdPartyPromptHttpClient().download(url, 10)).isInstanceOf(RuntimeException.class);
    }
    @Test void sharedOriginalPostDoesNotMergeDifferentPromptsAndDocumentationSamplesAreIgnored() {
        String entry = """
                ### Product A
                **Prompt:**
                ```
                Make a product portrait.
                ```
                **Source:** [Artist](https://x.com/artist/status/42)
                """;
        String sample = """
                ### Raycast Integration
                **Example:**
                ```
                A documentation example, not a prompt entry.
                ```
                """;
        var prompts = new MarkdownPromptAdapter().parse(source(TargetKind.IMAGE, Format.GITHUB_MARKDOWN),
                sample + entry + entry.replace("Product A", "Product B"));
        assertThat(prompts).hasSize(2); assertThat(prompts.get(0).id()).isNotEqualTo(prompts.get(1).id());
    }
}
