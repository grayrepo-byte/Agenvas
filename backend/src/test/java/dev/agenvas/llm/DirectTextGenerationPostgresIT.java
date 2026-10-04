package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.DirectTextGenerationWorker;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.task.api.TaskController;
import dev.agenvas.task.application.DirectTextTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real-PostgreSQL proof for pinned direct text tasks and concurrent-edit selection fencing. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, DirectTextGenerationPostgresIT.FakeConfig.class},
        properties = {
                "agenvas.llm.scheduler-enabled=false"
        })
class DirectTextGenerationPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private DirectTextTaskService direct;
    @Autowired private DirectTextGenerationWorker worker;
    @Autowired private TaskService tasks;
    @Autowired private FakeGateway gateway;
    @Autowired private ObjectMapper mapper;
    @Autowired private dev.agenvas.settings.application.PromptService prompts;

    @Test
    void createsNewVersionAndDoesNotSelectLateResultOverManualEdit() {
        AdminPrincipal owner = identities.setup("text-admin", "text-password-123");
        Project project = projects.create(owner.userId(), "Text generation",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView initial = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Notes", text(""));

        Task first = direct.run(owner.userId(), project.id(), initial.artifact().id(),
                "Expand this", initial.artifact().version(), initial.resourceDefaultVersion().id(),
                "direct-text-first");
        assertThat(direct.run(owner.userId(), project.id(), initial.artifact().id(),
                "Expand this", initial.artifact().version(), initial.resourceDefaultVersion().id(),
                "direct-text-first").id()).isEqualTo(first.id());
        assertThat(initial.resourceDefaultVersion().content().path("text").asText()).isEmpty();
        assertThat(first.input().path("currentText").asText()).isEmpty();
        var prompt = prompts.require(dev.agenvas.settings.application.PromptService.TEXT_GENERATION_KEY,
                dev.agenvas.settings.application.PromptService.Kind.FUNCTION);
        prompts.update(prompt.id(), prompt.version(), prompt.name(), prompt.description(), "Changed after task acceptance");
        gateway.expectedSystemPrompt = first.input().path("systemPrompt").asText();
        assertThat(worker.runOnce("text-worker")).isEqualTo(1);

        Task firstDone = tasks.get(owner.userId(), project.id(), first.id());
        ArtifactService.ArtifactView generated = artifacts.get(owner.userId(), project.id(),
                initial.artifact().id());
        assertThat(firstDone.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(firstDone.output().path("result").path("selected").asBoolean()).isTrue();
        assertThat(firstDone.output().path("response").path("generations")).hasSize(1);
        assertThat(generated.resourceDefaultVersion().content().path("text").asText())
                .isEqualTo("Generated text 1");
        assertThat(generated.resourceDefaultVersion().createdByKind())
                .isEqualTo(ArtifactVersion.CreatedByKind.TASK);
        TaskController.TaskResponse publicTask = TaskController.TaskResponse.from(firstDone);
        assertThat(publicTask.input().has("currentText")).isFalse();
        assertThat(publicTask.input().has("systemPrompt")).isFalse();
        assertThat(publicTask.output().has("response")).isFalse();

        Task stale = direct.run(owner.userId(), project.id(), generated.artifact().id(),
                "Rewrite again", generated.artifact().version(), generated.resourceDefaultVersion().id(),
                "direct-text-stale");
        ArtifactService.ArtifactView manual = artifacts.revise(owner.userId(), project.id(),
                generated.artifact().id(), generated.artifact().version(), null,
                text("Manual edit wins"));
        gateway.expectedSystemPrompt = stale.input().path("systemPrompt").asText();
        assertThat(worker.runOnce("text-worker")).isEqualTo(1);

        Task staleDone = tasks.get(owner.userId(), project.id(), stale.id());
        ArtifactService.ArtifactView after = artifacts.get(owner.userId(), project.id(),
                generated.artifact().id());
        assertThat(staleDone.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(staleDone.output().path("result").path("selected").asBoolean()).isFalse();
        assertThat(after.resourceDefaultVersion().id()).isEqualTo(manual.resourceDefaultVersion().id());
        assertThat(artifacts.listVersions(owner.userId(), project.id(), after.artifact().id()))
                .extracting(version -> version.content().path("text").asText())
                .contains("Generated text 2", "Manual edit wins");
        assertThat(gateway.calls.get()).isEqualTo(2);
    }

    private ObjectNode text(String value) {
        ObjectNode content = mapper.createObjectNode();
        content.put("format", "PLAIN_TEXT");
        content.put("text", value);
        return content;
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    static class FakeGateway implements ChatGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private String expectedSystemPrompt;

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools,
                Map<String, Object> toolContext) {
            assertThat(tools).isEmpty();
            assertThat(messages.getFirst().getText()).isEqualTo(expectedSystemPrompt);
            int call = calls.incrementAndGet();
            AssistantMessage output = new AssistantMessage("Generated text " + call);
            return new Exchange(1, new ChatResponse(List.of(new Generation(output)),
                    ChatResponseMetadata.builder().id("direct-text-" + call)
                            .model("fake-text-model").build()));
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(false, false, false);
        }

        @Override
        public ModelDetails modelDetails() {
            return new ModelDetails(true, "TEST", "fake-text-model", false);
        }

        @Override
        public int configVersion() {
            return 1;
        }

        @Override
        public String configSource() {
            return "direct-text-fake";
        }
    }
}
