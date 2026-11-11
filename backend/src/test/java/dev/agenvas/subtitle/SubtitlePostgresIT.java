package dev.agenvas.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Real PostgreSQL and FFmpeg; speech recognition is deliberately unconfigured in this HTTP test. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {"agenvas.subtitles.worker-enabled=false",
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class SubtitlePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", () -> storage.toString());
        r.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner tools;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    @Test void correctedSourceSubtitlesSurviveReopenAndRejectStaleWrites() throws Exception {
        var owner = identities.setup("subtitle-admin", "synthetic-subtitle-password-123");
        var project = projects.create(owner.userId(), "Subtitles", Project.AspectRatio.LANDSCAPE_16_9);
        Path audio = temp.resolve("synthetic.wav");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-y", audio.toString()));
        var asset = assets.archiveTaskAudio(owner.userId(), project.id(), UUID.randomUUID(), () -> {
            try { return Files.newInputStream(audio); } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        var version = artifacts.create(owner.userId(), project.id(), Artifact.Kind.AUDIO, "Synthetic",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString())).resourceDefaultVersion();
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id() + "/subtitles";
        var created = mapper.readTree(mvc.perform(post(base).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("sourceVersionId", version.id().toString()).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id = created.path("id").asText();
        String correction = "{\"expectedVersion\":0,\"cues\":[{\"text\":\"校正草稿\",\"startMs\":100,\"endMs\":1900}]}";
        mvc.perform(put(base + "/" + id).with(auth).with(csrf()).contentType("application/json").content(correction))
                .andExpect(status().isOk());
        mvc.perform(put(base + "/" + id).with(auth).with(csrf()).contentType("application/json").content(correction))
                .andExpect(status().isConflict());
        var reopened = mapper.readTree(mvc.perform(get(base + "/" + id).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(reopened.path("sourceVersionId").asText()).isEqualTo(version.id().toString());
        assertThat(reopened.at("/cues/0/text").asText()).isEqualTo("校正草稿");
        assertThat(reopened.path("version").asLong()).isEqualTo(1);
        mvc.perform(post(base + "/" + id + "/recognition").with(auth).with(csrf()).header("Idempotency-Key", "unconfigured")
                .contentType("application/json").content("{\"expectedVersion\":1,\"language\":\"auto\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get(base + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(put(base + "/" + id).with(auth).contentType("application/json").content(correction))
                .andExpect(status().isForbidden());
        var other = projects.create(owner.userId(), "Other scope", Project.AspectRatio.LANDSCAPE_16_9);
        mvc.perform(get("/api/v1/projects/" + other.id() + "/subtitles/" + id).with(auth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/projects/" + other.id() + "/subtitles").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("sourceVersionId", version.id().toString()).toString())).andExpect(status().isNotFound());
        var list = mapper.readTree(mvc.perform(get(base).with(auth).param("sourceVersionId", version.id().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        String exchange = "1\n00:00:00,250 --> 00:00:01,850\n导入校正 &lt;b&gt;\n\n";
        var imported = mapper.readTree(mvc.perform(post(base + "/" + id + "/import").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("expectedVersion", 1).put("format", "SRT").put("contents", exchange).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(imported.at("/cues/0/text").asText()).isEqualTo("导入校正 <b>");
        assertThat(imported.path("version").asLong()).isEqualTo(2);
        String exported = mvc.perform(get(base + "/" + id + "/export").with(auth).param("format", "VTT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(exported).isEqualTo("WEBVTT\n\n00:00:00.250 --> 00:00:01.850\n导入校正 &lt;b&gt;\n\n");
        mvc.perform(post(base + "/" + id + "/import").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("expectedVersion", 2).put("format", "VTT")
                        .put("contents", "WEBVTT\n\n00:00.000 --> 00:03.000\noutside source\n\n").toString()))
                .andExpect(status().isBadRequest());
        var retained = mapper.readTree(mvc.perform(get(base + "/" + id).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(retained).isEqualTo(imported);
        assertContract(retained);
        mvc.perform(post(base + "/" + id + "/import").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("expectedVersion", 2).put("format", "VTT")
                        .put("contents", "WEBVTT\n\n00:00.300 --> 00:01.600\nVTT 校正\n\n").toString()))
                .andExpect(status().isOk());
        String srt = mvc.perform(get(base + "/" + id + "/export").with(auth).param("format", "SRT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(srt).isEqualTo("1\n00:00:00,300 --> 00:00:01,600\nVTT 校正\n\n");
        // A legal, complete maximum-size source remains readable. Two such sources exceed the
        // aggregate byte budget and must fail atomically, rather than silently omit a draft.
        var secondVersion = artifacts.create(owner.userId(), project.id(), Artifact.Kind.AUDIO, "Synthetic second",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString())).resourceDefaultVersion();
        var second = mapper.readTree(mvc.perform(post(base).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("sourceVersionId", secondVersion.id().toString()).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var large = mapper.createArrayNode();
        for (int i = 0; i < 5000; i++) large.addObject().put("text", "字".repeat(500)).put("startMs", 0).put("endMs", 1000);
        for (var target : List.of(mapper.createObjectNode().put("id", id).put("version", 3), second)) {
            mvc.perform(put(base + "/" + target.path("id").asText()).with(auth).with(csrf()).contentType("application/json")
                    .content(mapper.createObjectNode().put("expectedVersion", target.path("version").asLong()).set("cues", large).toString()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(base).with(auth).param("sourceVersionId", version.id().toString(), secondVersion.id().toString()))
                .andExpect(status().isPayloadTooLarge());
        var complete = mapper.readTree(mvc.perform(get(base).with(auth).param("sourceVersionId", version.id().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(complete.get(0).path("cues")).hasSize(5000);
        assertThat(complete.get(0).at("/cues/4999/text").asText()).hasSize(500);
        // A separate count limit also catches small text that fits comfortably in the byte budget.
        var small = mapper.createArrayNode();
        for (int i = 0; i < 5000; i++) small.addObject().put("text", "x").put("startMs", 0).put("endMs", 1000);
        for (var target : List.of(mapper.createObjectNode().put("id", id).put("version", 4),
                mapper.createObjectNode().put("id", second.path("id").asText()).put("version", 1))) {
            mvc.perform(put(base + "/" + target.path("id").asText()).with(auth).with(csrf()).contentType("application/json")
                    .content(mapper.createObjectNode().put("expectedVersion", target.path("version").asLong()).set("cues", small).toString()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(base).with(auth).param("sourceVersionId", version.id().toString(), secondVersion.id().toString()))
                .andExpect(status().isOk());
        var thirdVersion = artifacts.create(owner.userId(), project.id(), Artifact.Kind.AUDIO, "Synthetic third",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString())).resourceDefaultVersion();
        var third = mapper.readTree(mvc.perform(post(base).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("sourceVersionId", thirdVersion.id().toString()).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(put(base + "/" + third.path("id").asText()).with(auth).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"cues\":[{\"text\":\"x\",\"startMs\":0,\"endMs\":1}]}"))
                .andExpect(status().isOk());
        mvc.perform(get(base).with(auth).param("sourceVersionId", version.id().toString(), secondVersion.id().toString(), thirdVersion.id().toString()))
                .andExpect(status().isPayloadTooLarge());


    }

    private void assertContract(JsonNode response) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/openapi.yaml"))) root = root.getParent();
        assertThat(root).isNotNull();
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource(root.resolve("contracts/openapi.yaml")));
        var schema = mapper.valueToTree(yaml.getObject()).at("/components/schemas/SubtitleDraft");
        assertThat(schema.path("required").valueStream().map(JsonNode::asText).toList())
                .allMatch(response::has);
        assertThat(response.propertyNames()).allMatch(schema.path("properties")::has);
        assertThat(response.at("/cues/0/startMs").asInt()).isEqualTo(250);
        assertThat(response.at("/cues/0/endMs").asInt()).isEqualTo(1850);
    }
}
