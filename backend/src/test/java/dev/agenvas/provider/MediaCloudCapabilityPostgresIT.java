package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Fixed cloud mappings expose only declared parameters and remain untested after publishing. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=cloud-media-catalog-secret")
class MediaCloudCapabilityPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private MediaCapabilityService catalog;
    @Autowired private CredentialCipher cipher;
    @Autowired private ObjectMapper mapper;

    @Test
    void fixedMappingsRejectModelOverridesAndUnsafeEndpointsAndFilterSeedanceSteps() {
        var openAi = catalog.createConnection("cloud-openai-1", "OpenAI", "OPENAI",
                null, "openai-test-secret");
        var ark = catalog.createConnection("cloud-ark-1", "Ark", "ARK",
                null, "ark-test-secret");
        var google = catalog.createConnection("cloud-google-1", "Google", "GOOGLE",
                null, "google-test-secret");
        var image = catalog.publishCapability(openAi.id(), "GPT Image 2",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"quality\":\"high\"}"));
        var nanoBanana = catalog.publishCapability(google.id(), "Nano Banana 2",
                "GOOGLE_NANO_BANANA_2");
        var video = catalog.publishCapability(ark.id(), "Seedance",
                "ARK_SEEDANCE_2_I2V");

        var imageSpec = mapper.readTree(catalog.capabilitySnapshot(image.id()).specJson());
        var videoSpec = mapper.readTree(catalog.capabilitySnapshot(video.id()).specJson());
        var googleSpec = mapper.readTree(catalog.capabilitySnapshot(nanoBanana.id()).specJson());
        assertThat(imageSpec.path("modelId").asText()).isEqualTo("gpt-image-2");
        assertThat(imageSpec.path("settings").path("quality").asText()).isEqualTo("high");
        assertThat(videoSpec.path("modelId").asText()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(videoSpec.path("generateAudio").booleanValue()).isFalse();
        assertThat(googleSpec.path("modelId").asText()).isEqualTo("gemini-3.1-flash-image");
        assertThat(googleSpec.path("imageSize").asText()).isEqualTo("1K");
        assertThat(catalog.candidates(Task.Kind.IMAGE_GENERATION, 0))
                .anyMatch(candidate -> candidate.binding().capabilityId().equals(nanoBanana.id())
                        && !candidate.realGenerationTested());
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 3, true))
                .noneMatch(candidate -> candidate.binding().capabilityId().equals(video.id()));
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 4, false))
                .noneMatch(candidate -> candidate.binding().capabilityId().equals(video.id()));
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 4, true))
                .anyMatch(candidate -> candidate.binding().capabilityId().equals(video.id())
                        && !candidate.realGenerationTested());
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 15, true))
                .anyMatch(candidate -> candidate.binding().capabilityId().equals(video.id()));
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 16, true))
                .noneMatch(candidate -> candidate.binding().capabilityId().equals(video.id()));
        assertThatThrownBy(() -> catalog.resolve(video.id(), Task.Kind.VIDEO_GENERATION, 3))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.publishCapability(openAi.id(), "Bad quality",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"quality\":\"ultra\"}")))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.publishCapability(openAi.id(), "Bad model",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"modelId\":\"custom\"}")))
                .isInstanceOf(ApiProblemException.class);
        var custom = catalog.createConnection("cloud-custom-origin", "Custom",
                "OPENAI", "https://images.example.com/proxy/v1", "secret");
        assertThat(catalog.getConnectionVersion(custom.id(), custom.currentVersion())
                .orElseThrow().origin()).isEqualTo("https://images.example.com/proxy/v1");
        var changed = catalog.updateConnection(custom.id(), custom.version(), "Custom",
                true, "https://images.example.com/next/v1", null);
        assertThat(changed.currentVersion()).isEqualTo(custom.currentVersion() + 1);
        assertThat(catalog.getConnectionVersion(custom.id(), custom.currentVersion())
                .orElseThrow().origin()).isEqualTo("https://images.example.com/proxy/v1");
        var changedVersion = catalog.getConnectionVersion(changed.id(), changed.currentVersion())
                .orElseThrow();
        assertThat(changedVersion.keyMask()).isEqualTo("••••cret");
        assertThat(cipher.decryptMedia(changed.id(), changedVersion.version(),
                new CredentialCipher.Encrypted(changedVersion.credentialCiphertext(),
                        changedVersion.credentialNonce(), changedVersion.credentialKeyVersion())))
                .isEqualTo("secret");
        assertThatThrownBy(() -> catalog.createConnection("cloud-bad-origin", "Bad",
                "OPENAI", "http://127.0.0.1:8080/v1", "secret"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.createConnection("cloud-bad-origin-2", "Bad",
                "OPENAI", "https://user:pass@images.example.com/v1", "secret"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.publishCapability(google.id(), "Bad model",
                "GOOGLE_NANO_BANANA_2", mapper.readTree("{\"modelId\":\"custom\"}")))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.createConnection("cloud-google-origin", "Bad",
                "GOOGLE", "https://example.com", "secret"))
                .isInstanceOf(ApiProblemException.class);
    }
}
