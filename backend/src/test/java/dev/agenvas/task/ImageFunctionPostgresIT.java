package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL/local processor; all assets and remote contracts are synthetic, with no provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class ImageFunctionPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenvas.storage.root", () -> storage.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired DirectMediaTaskService direct;
    @Autowired MediaFunctionService functions;
    @Autowired MediaCapabilityService capabilities;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext webContext;
    @Autowired dev.agenvas.usage.application.UsageService usage;
    private static AdminPrincipal owner;
    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("synthetic-image-admin", "synthetic-image-password-123");
    }

    @Test void keepsImageAndVideoRoutesAndGenerationDefaultsIndependent() {
        assertThat(functions.list()).extracting(setting -> setting.operation())
                .containsExactlyInAnyOrder(MediaFunction.values());
        var video = setting(MediaFunction.VIDEO_DEPTH_MAP);
        var image = setting(MediaFunction.IMAGE_DEPTH_MAP);
        UUID generationDefault = capabilities.defaultCapabilityId(Task.Kind.IMAGE_GENERATION);
        try {
            functions.update(MediaFunction.IMAGE_DEPTH_MAP, image.version(), null);
            assertThat(setting(MediaFunction.VIDEO_DEPTH_MAP)).isEqualTo(video);
            assertThat(capabilities.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isEqualTo(generationDefault);
            var source = source();
            assertThatThrownBy(() -> run(source, ImageOperation.DEPTH_MAP, image.version() + 1, 1, mapper.createObjectNode(), "disabled-depth"))
                    .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_FUNCTION_UNCONFIGURED");
            assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        } finally {
            functions.update(MediaFunction.IMAGE_DEPTH_MAP, image.version() + 1, image.capabilityId());
        }
    }

    @Test void rejectsStaleSettingAndCapabilityBeforeCreatingAnyResult() {
        var source = source();
        var setting = setting(MediaFunction.IMAGE_UPSCALE);
        assertThatThrownBy(() -> run(source, ImageOperation.UPSCALE, setting.version() + 1, 1, mapper.createObjectNode(), "stale-setting"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_FUNCTION_CONFLICT");
        assertThatThrownBy(() -> run(source, ImageOperation.UPSCALE, setting.version(), 2, mapper.createObjectNode(), "stale-capability"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_CAPABILITY_CHANGED");
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
    }

    @Test void freezesWorkflowAndSourceAndReplaysAfterTheRouteIsDisabled() {
        var connection = capabilities.createConnection(UUID.randomUUID().toString(), "Synthetic image workflow", "RUNNINGHUB", null, "synthetic-runninghub-key");
        var capability = capabilities.publishCapability(connection.id(), "Synthetic image upscale", "RUNNINGHUB_IMAGE", mapper.readTree("""
                {"pricing":{"amount":"0.25","currency":"USD","unit":"IMAGE"},"runningHub":{
                "schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
                "fields":[{"key":"source","label":"Source","type":"IMAGE","required":true,"nodeId":"1","fieldName":"image"},
                {"key":"scale","label":"Scale","type":"SELECT","required":true,"nodeId":"2","fieldName":"scale","defaultValue":2,
                 "options":[{"label":"2x","value":2},{"label":"4x","value":4}]}],
                "outputs":[{"nodeId":"3","kind":"IMAGE","primary":true,"maxCount":1}]}}
                """));
        var previous = setting(MediaFunction.IMAGE_UPSCALE);
        functions.update(MediaFunction.IMAGE_UPSCALE, previous.version(), capability.id());
        var source = source();
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("source", UUID.randomUUID().toString()).put("scale", 4);
        long version = previous.version() + 1;
        try {
            Task accepted = run(source, ImageOperation.UPSCALE, version, 1, parameters, "workflow-image");
            assertThat(accepted.input().path("mediaInput").path("parameters").path("dynamicValues").path("source").asText()).isEqualTo(source.version().toString());
            assertThat(accepted.input().path("mediaInput").path("parameters").path("dynamicValues").path("scale").asInt()).isEqualTo(4);
            assertThat(accepted.input().path("mediaInput").path("runningHubContract").path("targetId").asText()).isEqualTo("123");
            assertThat(accepted.input().path("mediaPricing").path("amount").asText()).isEqualTo("0.25");
            assertThat(usage.listProject(owner.userId(), source.project())).filteredOn(entry -> accepted.id().equals(entry.taskId()))
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.costSource()).isEqualTo("ADMIN_CONFIGURED");
                        assertThat(entry.estimatedCost()).isEqualByComparingTo("0.25");
                    });
            assertThat(drafts.get(owner.userId(), source.project(), UUID.fromString(accepted.input().path("canvasItemId").asText())).prompt()).isEmpty();
            direct.cancelQueued(owner.userId(), source.project(), accepted.id());
            functions.update(MediaFunction.IMAGE_UPSCALE, version, null);
            assertThat(run(source, ImageOperation.UPSCALE, version, 1, parameters, "workflow-image").id()).isEqualTo(accepted.id());
            var changed = parameters.deepCopy(); changed.withObject("dynamicValues").put("scale", 2);
            assertThatThrownBy(() -> run(source, ImageOperation.UPSCALE, version, 1, changed, "workflow-image"))
                    .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("DIRECT_MEDIA_CONFLICT");
            assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        } finally {
            var current = setting(MediaFunction.IMAGE_UPSCALE);
            functions.update(MediaFunction.IMAGE_UPSCALE, current.version(), previous.capabilityId());
        }
    }

    @Test void runsConfiguredLocalUpscaleWithoutChangingTheSource() {
        var source = source();
        var setting = setting(MediaFunction.IMAGE_UPSCALE);
        var accepted = run(source, ImageOperation.UPSCALE, setting.version(), 1, mapper.createObjectNode().put("scale", 2), "local-image-upscale");
        assertThat(worker.submitOnce("synthetic-image-local-worker")).isEqualTo(1);
        var result = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(result.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(result.output().hasNonNull("artifactVersionId")).as("successful task result: %s", result.output()).isTrue();
        var original = assets.get(owner.userId(), source.project(), source.asset()).asset();
        var outputVersion = artifacts.requireImageVersionForTask(owner.userId(), source.project(), UUID.fromString(result.output().path("artifactVersionId").asText()));
        assertThat(outputVersion.content().hasNonNull("assetId")).as("archived image content: %s", outputVersion.content()).isTrue();
        var output = assets.get(owner.userId(), source.project(), UUID.fromString(outputVersion.content().path("assetId").asText())).asset();
        assertThat(output.width()).isEqualTo(original.width() * 2);
        assertThat(output.height()).isEqualTo(original.height() * 2);
        assertThat(canvas.list(owner.userId(), source.project()).stream().filter(entry -> entry.item().id().equals(source.item())).findFirst().orElseThrow().item().selectedVersionId())
                .isEqualTo(source.version());
    }

    @Test void imageHttpContractRequiresDisplayedVersionsAndRejectsClientProviderSelection() throws Exception {
        var source = source();
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        String url = "/api/v1/projects/" + source.project() + "/artifacts/" + source.artifact() + "/image-operations";
        var request = mapper.createObjectNode().put("canvasItemId", source.item().toString()).put("sourceVersionId", source.version().toString())
                .put("expectedCanvasItemVersion", 0).put("operation", "UPSCALE"); request.putObject("parameters").put("scale", 2);
        mvc.perform(post(url).with(auth).with(csrf()).header("Idempotency-Key", "missing-route-versions").contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest());
        var setting = setting(MediaFunction.IMAGE_UPSCALE);
        request.put("expectedFunctionVersion", setting.version()).put("expectedCapabilityVersion", 1).put("capabilityId", UUID.randomUUID().toString());
        mvc.perform(post(url).with(auth).with(csrf()).header("Idempotency-Key", "client-provider").contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest());
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
    }

    private dev.agenvas.provider.infrastructure.JooqMediaFunctionRepository.Setting setting(MediaFunction function) {
        return functions.list().stream().filter(entry -> entry.operation() == function).findFirst().orElseThrow();
    }
    private record Source(UUID project, UUID artifact, UUID item, UUID version, UUID asset) {}
    private Source source() {
        var project = projects.create(owner.userId(), "Synthetic image tool", Project.AspectRatio.LANDSCAPE_16_9);
        var asset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Synthetic image",
                mapper.createObjectNode().put("assetId", asset.toString()).put("sourceType", "UPLOAD"));
        UUID item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project.id(), image.artifact().id());
        return new Source(project.id(), image.artifact().id(), item, image.resourceDefaultVersion().id(), asset);
    }
    private Task run(Source source, ImageOperation operation, long functionVersion, int capabilityVersion, ObjectNode parameters, String key) {
        return direct.runImageOperation(owner.userId(), source.project(), source.artifact(), source.item(), source.version(), 0,
                operation, "", functionVersion, capabilityVersion, List.of(), null, parameters, key);
    }
}
