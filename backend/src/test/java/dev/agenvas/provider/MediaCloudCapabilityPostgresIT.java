package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

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
@SpringBootTest(classes = AgenvasApplication.class)
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
    @Autowired private dev.agenvas.identity.application.IdentityService identities;
    @Autowired private org.springframework.web.context.WebApplicationContext webContext;

    @Test
    void minimaxOfficialConnectionsPublishPinnedH3CapabilitiesAndRejectProtocolOverrides() throws Exception {
        assertThatThrownBy(() -> catalog.createConnection("h3-no-key", "MiniMax", "MINIMAX", null, null))
                .isInstanceOf(ApiProblemException.class);
        var connection = catalog.createConnection("h3-cloud", "MiniMax", "MINIMAX", null, "synthetic-h3-key");
        assertThat(catalog.getConnectionVersion(connection.id(), connection.currentVersion()).orElseThrow().origin()).isEqualTo("https://api.minimax.cn");
        var capability = catalog.publishCapability(connection.id(), "H3", "MINIMAX_H3", mapper.readTree("""
                {"defaultParameters":{"aspectRatio":"AUTO","videoResolution":"1440p"},
                 "defaultDurationSeconds":6,"pricingByResolution":{"1440p":{"amount":"0.3","currency":"CNY","unit":"SECOND"}}}
                """));
        var snapshot = catalog.capabilitySnapshot(capability.id());
        var spec = mapper.readTree(snapshot.specJson());
        assertThat(spec.path("modelId").asText()).isEqualTo("MiniMax-H3");
        assertThat(spec.path("generateAudio").asBoolean()).isTrue();
        assertThat(spec.path("maxReferenceImages").asInt()).isEqualTo(9);
        assertThat(spec.path("maxReferenceVideos").asInt()).isEqualTo(3);
        assertThat(spec.path("settings").path("defaultParameters").path("videoResolution").asText()).isEqualTo("1440p");
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 3)).noneMatch(candidate -> candidate.binding().capabilityId().equals(capability.id()));
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 4)).anyMatch(candidate -> candidate.binding().capabilityId().equals(capability.id()));
        assertThatThrownBy(() -> catalog.publishCapability(connection.id(), "Unsupported", "MINIMAX_H3", mapper.readTree("{\"model\":\"MiniMax-H3-Max\"}")))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.createConnection("h3-proxy", "Proxy", "MINIMAX", "https://proxy.example.com", "synthetic-key"))
                .isInstanceOf(ApiProblemException.class);
        var updated = catalog.updateConnection(connection.id(), connection.version(), "International", true, "https://api.minimax.io", null);
        assertThat(updated.currentVersion()).isEqualTo(connection.currentVersion() + 1);
        assertThat(catalog.getConnectionVersion(connection.id(), connection.currentVersion()).orElseThrow().origin()).isEqualTo("https://api.minimax.cn");
        assertThat(catalog.getConnectionVersion(connection.id(), updated.currentVersion()).orElseThrow().origin()).isEqualTo("https://api.minimax.io");
        assertThat(catalog.capabilitySnapshot(capability.id()).mappingSha256()).isEqualTo(snapshot.mappingSha256());
        var owner = identities.setup("h3-settings-admin", "synthetic-password-123");
        var auth = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        String body = webAppContextSetup(webContext).apply(springSecurity()).build()
                .perform(get("/api/v1/settings/media-connections").with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("synthetic-h3-key", "credentialCiphertext", "credentialNonce");
        var publicConnections = mapper.readTree(body).path("connections");
        assertThat(publicConnections).anySatisfy(publicConnection -> {
            assertThat(publicConnection.path("platform").asText()).isEqualTo("MINIMAX");
            assertThat(publicConnection.path("origin").asText()).isEqualTo("https://api.minimax.io");
            assertThat(publicConnection.path("capabilities").get(0).path("adapterId").asText()).isEqualTo("MINIMAX_H3");
        });
    }

    @Test
    void fixedMappingsRejectModelOverridesAndUnsafeEndpointsAndFilterSeedanceSteps() {
        var openAi = catalog.createConnection("cloud-openai-1", "OpenAI", "OPENAI",
                null, "openai-test-secret");
        var ark = catalog.createConnection("cloud-ark-1", "Ark", "ARK",
                null, "ark-test-secret");
        var google = catalog.createConnection("cloud-google-1", "Google", "GOOGLE",
                null, "google-test-secret");
        var volc = catalog.createConnection("cloud-volc-1", "Volc", "VOLCENGINE",
                null, "synthetic-audio-secret");
        var image = catalog.publishCapability(openAi.id(), "GPT Image 2",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"quality\":\"high\"}"));
        var nanoBanana = catalog.publishCapability(google.id(), "Nano Banana 2",
                "GOOGLE_NANO_BANANA_2");
        var video = catalog.publishCapability(ark.id(), "Seedance",
                "ARK_SEEDANCE_2_I2V");
        var audio = catalog.publishCapability(volc.id(), "Seed Audio", "VOLC_SEED_AUDIO_1");
        var defaultImage = catalog.publishCapability(openAi.id(), "Default quality", "OPENAI_GPT_IMAGE_2");

        var imageSpec = mapper.readTree(catalog.capabilitySnapshot(image.id()).specJson());
        var videoSpec = mapper.readTree(catalog.capabilitySnapshot(video.id()).specJson());
        var googleSpec = mapper.readTree(catalog.capabilitySnapshot(nanoBanana.id()).specJson());
        var audioSpec = mapper.readTree(catalog.capabilitySnapshot(audio.id()).specJson());
        assertThat(imageSpec.path("modelId").asText()).isEqualTo("gpt-image-2");
        assertThat(imageSpec.path("maxReferenceImages").asInt()).isEqualTo(4);
        assertThat(imageSpec.path("settings").path("quality").asText()).isEqualTo("high");
        assertThat(imageSpec.path("defaultVideoInputMode").isNull()).isTrue();
        assertThat(mapper.readTree(catalog.capabilitySnapshot(defaultImage.id()).specJson())
                .path("settings").path("quality").asText()).isEqualTo("medium");
        assertThat(videoSpec.path("modelId").asText()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(videoSpec.path("generateAudio").booleanValue()).isFalse();
        assertThat(googleSpec.path("modelId").asText()).isEqualTo("gemini-3.1-flash-image");
        assertThat(googleSpec.path("maxReferenceImages").asInt()).isEqualTo(14);
        assertThat(googleSpec.path("imageSize").asText()).isEqualTo("1K");
        assertThat(audioSpec.path("modelId").asText()).isEqualTo("seed-audio-1.0");
        assertThat(audioSpec.path("outputFormat").asText()).isEqualTo("mp3");
        assertThat(audioSpec.path("defaultVideoInputMode").isNull()).isTrue();
        assertThat(catalog.candidates(Task.Kind.IMAGE_GENERATION, 0))
                .anyMatch(candidate -> candidate.binding().capabilityId().equals(nanoBanana.id())
                        && !candidate.realGenerationTested());
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 3))
                .noneMatch(candidate -> candidate.binding().capabilityId().equals(video.id()));
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 4))
                .anyMatch(candidate -> candidate.binding().capabilityId().equals(video.id())
                        && !candidate.realGenerationTested());
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 15))
                .anyMatch(candidate -> candidate.binding().capabilityId().equals(video.id()));
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 16))
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
        // 回环端点现在可用（本地假 API 与自托管服务），HTTP 非回环仍被拒。
        assertThatThrownBy(() -> catalog.createConnection("cloud-bad-origin", "Bad",
                "OPENAI", "http://images.example.com/v1", "secret"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.createConnection("cloud-bad-origin-2", "Bad",
                "OPENAI", "https://user:pass@images.example.com/v1", "secret"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.publishCapability(google.id(), "Bad model",
                "GOOGLE_NANO_BANANA_2", mapper.readTree("{\"modelId\":\"custom\"}")))
                .isInstanceOf(ApiProblemException.class);
        // Google 现在允许自定义 HTTPS 端点（中转站）；明文与私网地址仍被拒。
        assertThatThrownBy(() -> catalog.createConnection("cloud-google-origin", "Bad",
                "GOOGLE", "http://192.168.1.10:8080", "secret"))
                .isInstanceOf(ApiProblemException.class);
    }
}
