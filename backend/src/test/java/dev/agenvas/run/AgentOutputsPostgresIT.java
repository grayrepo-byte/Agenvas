package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentOutputService;
import dev.agenvas.run.application.AgentRunService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

/** Mock Agent Runs with real PostgreSQL provenance and authenticated REST reads. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class AgentOutputsPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private ArtifactService artifacts;
    @Autowired private CanvasService canvas;
    @Autowired private AgentRunService runs;
    @Autowired private AgentOutputService outputs;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;

    @Test void outputsFollowCurrentVersionAndAgentProvenanceAcrossHumanAndAgentEdits() throws Exception {
        var owner = identities.setup("outputs-admin", "outputs-password-123");
        var project = projects.create(owner.userId(), "Outputs", Project.AspectRatio.LANDSCAPE_16_9);
        var otherProject = projects.create(owner.userId(), "Other outputs", Project.AspectRatio.SQUARE_1_1);
        var agent = agents.create(owner.userId(), project.id(), "Director", "Use canvas");
        var otherAgent = agents.create(owner.userId(), project.id(), "Writer", "Write");
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Draft", "output-first").run();
        var draft = artifacts.createFromAgent(owner.userId(), project.id(), run.id(), Artifact.Kind.TEXT,
                "Draft", mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Generated draft"));
        runs.cancel(owner.userId(), project.id(), run.id());
        UUID card = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(card,
                draft.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(300),
                BigDecimal.valueOf(240), 0, UUID.randomUUID(), false)));
        assertThat(outputs.listCanvasItemIds(owner.userId(), project.id(), agent.id())).containsExactly(card);
        assertThat(outputs.listCanvasItemIds(owner.userId(), project.id(), otherAgent.id())).isEmpty();
        var human = artifacts.revise(owner.userId(), project.id(), draft.artifact().id(), draft.artifact().version(),
                "Edited", mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Human revision"));
        assertThat(outputs.listCanvasItemIds(owner.userId(), project.id(), agent.id())).isEmpty();
        var revisionRun = runs.create(owner.userId(), project.id(), otherAgent.id(), "Revise", "output-revision").run();
        artifacts.reviseFromAgent(owner.userId(), project.id(), revisionRun.id(), revisionRun.contextSnapshot(),
                human.artifact().id(), human.artifact().version(), "Revised", mapper.createObjectNode()
                        .put("format", "PLAIN_TEXT").put("text", "Writer revision"));
        runs.cancel(owner.userId(), project.id(), revisionRun.id());
        assertThat(outputs.listCanvasItemIds(owner.userId(), project.id(), otherAgent.id())).containsExactly(card);
        assertThat(outputs.listCanvasItemIds(owner.userId(), project.id(), agent.id())).isEmpty();
        assertThat(artifacts.listVersions(owner.userId(), project.id(), draft.artifact().id())).hasSize(3);

        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String path = "/api/v1/projects/" + project.id() + "/agents/" + otherAgent.id() + "/outputs";
        var result = mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("canvasItemIds").get(0).asText())
                .isEqualTo(card.toString());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects/" + otherProject.id() + "/agents/" + agent.id() + "/outputs").with(auth))
                .andExpect(status().isNotFound());
    }
}
