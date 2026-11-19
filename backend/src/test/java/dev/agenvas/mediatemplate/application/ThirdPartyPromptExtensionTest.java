package dev.agenvas.mediatemplate.application;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import dev.agenvas.mediatemplate.infrastructure.NativeJsonPromptAdapter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ThirdPartyPromptExtensionTest {
    @Test
    void installedFormatsAreUniqueAndMissingFormatsFailExplicitly() {
        var adapter = new NativeJsonPromptAdapter(new JsonMapper());
        var registry = new ThirdPartyPromptRegistry(List.of(adapter));
        assertThat(registry.require(Format.NATIVE_JSON)).isSameAs(adapter);
        assertThatThrownBy(() -> new ThirdPartyPromptRegistry(List.of(adapter, adapter)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NATIVE_JSON");
        assertThatThrownBy(() -> registry.require(Format.GITHUB_MARKDOWN))
                .isInstanceOf(ThirdPartyPromptRegistry.UnsupportedFormat.class);
    }
    @Test
    void formatAdapterNeedsOnlyABoundedFeedPortRatherThanAConcreteHttpClient() {
        var source = new ThirdPartyPromptSource("synthetic", "Synthetic", TargetKind.IMAGE, Format.NATIVE_JSON,
                "http://feed.example.test/items", "", true, 0, Instant.EPOCH, null, null, 0, false);
        var called = new AtomicReference<String>();
        ThirdPartyPromptFeed feed = url -> {
            called.set(url);
            return "[{\"id\":\"sample\",\"title\":\"Synthetic\",\"prompt\":\"A tree\"}]";
        };
        var prompts = new NativeJsonPromptAdapter(new JsonMapper()).fetch(source, feed);
        assertThat(called).hasValue("http://feed.example.test/items");
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt.prompt()).isEqualTo("A tree"));
    }
}
