package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;

import dev.agenvas.provider.domain.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaAdapterRegistrationTest {
    private static class Extension implements MediaAdapter {
        public String adapterId() {
            return "SYNTHETIC_IMAGE";
        }
        public MediaAdapterRegistry.Declaration declaration() {
            return new MediaAdapterRegistry(List.of()).declaration("OPENAI_GPT_IMAGE_2");
        }
        public Submission submit(AttemptContext context) {
            return new Submission.Rejected("SYNTHETIC");
        }
        public Submission reconcile(AttemptContext context) {
            return new Submission.Blocked("SYNTHETIC");
        }
    }

    @Test
    void compiledExtensionPublishesItsOwnDeclarationAndExecution() {
        var extension = new Extension();
        var registry = new MediaAdapterRegistry(List.of(extension));
        assertThat(registry.require("SYNTHETIC_IMAGE")).isSameAs(extension);
        assertThat(registry.declaration("SYNTHETIC_IMAGE")).isEqualTo(extension.declaration());
    }

    @Test
    void extensionTimeoutDefaultsArePublishedWithoutChangingHistoricalFallbacks() {
        var timeouts = new dev.agenvas.shared.http.HttpTimeouts(5, 60, 60, 60);
        var extension = new Extension() {
            public dev.agenvas.shared.http.HttpTimeouts publicationHttpTimeouts() {
                return timeouts;
            }
        };
        var registry = new MediaAdapterRegistry(List.of(extension));
        assertThat(registry.httpTimeoutDefaults()).containsEntry("SYNTHETIC_IMAGE", timeouts);
        assertThat(registry.httpTimeoutDefaults()).containsAllEntriesOf(MediaHttpTimeouts.defaults());
        assertThat(MediaHttpTimeouts.configured(new tools.jackson.databind.json.JsonMapper().createObjectNode()))
                .isNull();
    }

    @Test
    void declaredButUninstalledProtocolKeepsThePublicUnsupportedError() {
        var registry = new MediaAdapterRegistry(List.of());
        assertThat(registry.declaration("MOCK_IMAGE").platform()).isEqualTo(MediaPlatform.MOCK);
        assertThatThrownBy(() -> registry.require("MOCK_IMAGE"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_UNSUPPORTED_CAPABILITY"));
    }

    @Test
    void duplicateImplementationsFailBeforeTheyCanHandleRequests() {
        assertThatThrownBy(() -> new MediaAdapterRegistry(List.of(new Extension(), new Extension())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SYNTHETIC_IMAGE");
    }
}
