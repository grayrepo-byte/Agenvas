package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.usage.domain.UsageEntry;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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

/** Real PostgreSQL task/ledger snapshots; no HTTP request or paid generation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class AutoDlResolutionPricingPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired MediaCapabilityService catalog;
    @Autowired DirectMediaTaskService direct;
    @Autowired UsageService usage;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.web.context.WebApplicationContext context;

    @Test void freezesSelectedTierPriceAndDefaultsAndRejectsUnpublishedTiers() throws Exception {
        UUID owner = identities.setup("resolution-admin", "resolution-password-123").userId();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var principal = new dev.agenvas.identity.application.AdminPrincipal(owner, "resolution-admin");
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        var user = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        String url = "/api/v1/settings/autodl-workflows/preview";
        String body = "{\"workflowId\":\"future_video_v1\",\"source\":{\"uuid\":\"future_video_v1\",\"name\":\"Future video\",\"input_rules\":{"
                + "\"duration\":{\"type\":\"integer\",\"min\":1,\"max\":20},\"prompt\":{\"type\":\"string\",\"max_length\":10000},"
                + "\"resolution\":{\"type\":\"enum\",\"default\":\"720p横(1280*720)\",\"options\":[{\"label\":\"720p横(1280*720)\"}]}}}}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/settings/autodl-workflows"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/settings/autodl-workflows").with(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(user)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(admin)
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(admin)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value("future_video_v1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.resolutions[0]").value("720p横(1280*720)"));
        UUID project = projects.create(owner, "Resolution prices", Project.AspectRatio.LANDSCAPE_16_9).id();
        var connection = catalog.createConnection("resolution-connection", "AutoDL", "AUTODL", null, "fake-key");
        var settings = mapper.readTree("""
                {"workflowId":"minimax_h3_z0901","videoResolution":"480p","videoResolutions":["480p","768p"],
                "defaultDurationSeconds":8,"pricing":{"amount":"2","currency":"CNY","unit":"VIDEO"},
                "pricingByResolution":{"768p":{"amount":"0.123456","currency":"USD","unit":"SECOND"}}}
                """);
        var capability = catalog.publishCapability(connection.id(), "Two resolutions", "AUTODL_COMFY_VIDEO", settings);
        assertThat(catalog.settings(catalog.forDraft(capability.id(), Task.Kind.VIDEO_GENERATION)).path("videoResolutions").size()).isEqualTo(2);
        UUID artifact = artifacts.create(owner, project, Artifact.Kind.VIDEO, "Default tier", null).artifact().id();
        UUID card = CanvasMediaFixture.place(canvas, owner, project, artifact);
        var draft = drafts.save(owner, project, card, 0, "Landscape", mapper.createObjectNode(), null,
                capability.id(), MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        Task defaultTask = direct.run(owner, project, artifact, card, draft.version(), "default-tier");
        assertThat(defaultTask.input().at("/mediaInput/parameters/videoResolution").asText()).isEqualTo("480p");
        assertThat(defaultTask.input().at("/mediaInput/providerParameters/resolution").asText()).isEqualTo("480p横(864*480)");
        assertThat(defaultTask.input().at("/mediaPricing/amount").asText()).isEqualTo("2");
        direct.cancelQueued(owner, project, defaultTask.id());
        draft = drafts.get(owner, project, card);
        draft = drafts.save(owner, project, card, draft.version(), "Landscape", mapper.createObjectNode().put("videoResolution", "768p"),
                8, capability.id(), MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        Task selectedTask = direct.run(owner, project, artifact, card, draft.version(), "selected-tier");
        assertThat(selectedTask.input().at("/mediaInput/providerParameters/resolution").asText()).isEqualTo("768p横(1344*768)");
        assertThat(selectedTask.input().at("/mediaPricing/amount").asText()).isEqualTo("0.123456");
        assertThat(selectedTask.input().at("/mediaPricing/currency").asText()).isEqualTo("USD");
        assertThat(usage.listProject(owner, project).stream().filter(entry -> selectedTask.id().equals(entry.taskId())))
                .isNotEmpty().allSatisfy(entry -> assertThat(entry.estimatedCost()).isEqualByComparingTo("0.987648"));

        catalog.updateCapability(connection.id(), capability.id(), capability.version(), "Only 480p", true,
                "AUTODL_COMFY_VIDEO", mapper.readTree("""
                {"workflowId":"minimax_h3_z0901","videoResolution":"480p","videoResolutions":["480p"],
                "pricing":{"amount":"9","currency":"CNY","unit":"VIDEO"}}
                """));
        direct.cancelQueued(owner, project, selectedTask.id());
        assertThat(usage.listProject(owner, project).stream().filter(entry -> selectedTask.id().equals(entry.taskId()) && entry.entryType() == UsageEntry.EntryType.RELEASE))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.estimatedCost()).isEqualByComparingTo("0.987648");
                    assertThat(entry.currency()).isEqualTo("USD");
                    assertThat(entry.actualCost()).isNull();
                });
        var current = drafts.get(owner, project, card);
        assertThat(current.parameters().path("videoResolution").asText()).isEqualTo("768p");
        assertThatThrownBy(() -> direct.run(owner, project, artifact, card, current.version(), "removed-tier"))
                .hasMessageContaining("分辨率");
    }
}
