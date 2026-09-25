package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=media-catalog-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.comfyui.scheduler-enabled=false"})
class MediaCapabilityPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MediaCapabilityService catalog;
    @Autowired private ObjectMapper mapper;

    @AfterEach
    void restoreMockDefaults() {
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION),
                UUID.fromString("00000000-0000-4000-8000-000000000102"));
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION),
                UUID.fromString("00000000-0000-4000-8000-000000000103"));
    }

    @Test
    void mockBootstrapAndTwoCapabilitiesOnOneConnection() {
        assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).adapterId())
                .isEqualTo("MOCK_IMAGE");
        assertThat(catalog.defaultFor(Task.Kind.VIDEO_GENERATION).adapterId())
                .isEqualTo("MOCK_VIDEO");

        UUID comfyConnection = catalog.createConnection("Local ComfyUI", "http://127.0.0.1:8188").id();
        UUID comfyImage = catalog.publishCapability(comfyConnection, "Image", "COMFY_IMAGE_V1",
                imageSettings()).id();
        UUID comfyVideo = catalog.publishCapability(comfyConnection, "Video", "COMFY_VIDEO_V1",
                videoSettings()).id();
        assertThat(catalog.resolve(comfyImage, Task.Kind.IMAGE_GENERATION, 3).connectionId())
                .isEqualTo(comfyConnection);
        assertThat(catalog.resolve(comfyVideo, Task.Kind.VIDEO_GENERATION, 5).connectionId())
                .isEqualTo(comfyConnection);
        assertThatThrownBy(() -> catalog.resolve(comfyVideo, Task.Kind.VIDEO_GENERATION, 6))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void defaultsUseCasAndDisablingRetainsHistoricalVersions() {
        UUID first = catalog.createConnection("First ComfyUI", "http://127.0.0.1:8288").id();
        UUID second = catalog.createConnection("Second ComfyUI", "http://127.0.0.1:8388").id();
        UUID image = catalog.publishCapability(first, "Image", "COMFY_IMAGE_V1",
                imageSettings()).id();
        UUID video = catalog.publishCapability(second, "Video", "COMFY_VIDEO_V1",
                videoSettings()).id();

        long imageVersion = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, imageVersion, image);
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), video);
        assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).capabilityId()).isEqualTo(image);
        assertThat(catalog.defaultFor(Task.Kind.VIDEO_GENERATION).capabilityId()).isEqualTo(video);
        assertThatThrownBy(() -> catalog.setDefault(Task.Kind.IMAGE_GENERATION, imageVersion, image))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));

        var connection = catalog.getConnection(first);
        catalog.setConnectionEnabled(first, connection.version(), false);
        assertThatThrownBy(() -> catalog.resolve(image, Task.Kind.IMAGE_GENERATION, 3))
                .isInstanceOf(ApiProblemException.class);
        assertThat(catalog.getConnectionVersion(first, 1)).isPresent();
    }

    @Test
    void uninstalledAdapterCannotBePublished() {
        UUID connection = catalog.createConnection("Another ComfyUI", "http://127.0.0.1:8488").id();
        assertThatThrownBy(() -> catalog.publishCapability(connection, "Arbitrary", "DYNAMIC_SCRIPT"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_UNSUPPORTED_CAPABILITY"));
    }

    private tools.jackson.databind.JsonNode imageSettings() {
        return mapper.readTree("{\"checkpoint\":\"image.safetensors\"}");
    }

    private tools.jackson.databind.JsonNode videoSettings() {
        return mapper.readTree("{\"diffusionModel\":\"video.safetensors\","
                + "\"textEncoder\":\"text.safetensors\",\"vae\":\"vae.safetensors\","
                + "\"clipVision\":\"vision.safetensors\"}");
    }
}
