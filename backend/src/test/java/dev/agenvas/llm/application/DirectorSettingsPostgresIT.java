package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real settings persistence, HTTP authorization and live planning; no external model traffic. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.agent.context-window-tokens=12345", "agenvas.llm.agent.compaction-threshold-percent=99"})
class DirectorSettingsPostgresIT {
    private static final String ENDPOINT = "/api/v1/settings/director";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired DirectorSettingsService settings;
    @Autowired AgentContextService context;
    @Autowired LlmProtocolCodec codec;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext application;
    private MockMvc mvc;

    @BeforeEach void reset() {
        jdbc.sql("""
                update director_settings set context_window_tokens=1000000, compaction_threshold_percent=50,
                response_reserve_tokens=8192, summary_tokens=2048, recent_tool_rounds=4,
                recovery_window_minutes=1440, max_model_retries=288, retry_max_delay_seconds=300,
                max_repair_attempts=4, version=1
                """).update();
        mvc = webAppContextSetup(application).apply(springSecurity()).build();
    }

    @Test void databaseDefaultsIgnoreObsoleteDeploymentPropertiesAndSurviveANewReader() throws Exception {
        assertThat(settings.get()).isEqualTo(DirectorSettings.defaults());
        var saved = settings.update(small(1));
        assertThat(saved.version()).isEqualTo(2);
        try (var database = org.jooq.impl.DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(new DirectorSettingsService(database).get()).isEqualTo(saved);
        }
        mvc.perform(get(ENDPOINT).with(user("administrator").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.contextWindowTokens").value(8192))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test void httpRequiresAdministratorCsrfAndCompleteValidVersionedInput() throws Exception {
        String body = mapper.writeValueAsString(request());
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ENDPOINT).with(user("reader").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(put(ENDPOINT).with(user("reader").roles("USER")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(ENDPOINT).with(user("administrator").roles("ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        var missing = request(); missing.remove("summaryTokens");
        mvc.perform(put(ENDPOINT).with(user("administrator").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(missing))).andExpect(status().isBadRequest());
        for (String field : List.of("contextWindowTokens", "compactionThresholdPercent", "summaryTokens", "expectedVersion")) {
            var invalid = request(); invalid.put(field, 0);
            mvc.perform(put(ENDPOINT).with(user("administrator").roles("ADMIN")).with(csrf())
                    .contentType("application/json").content(mapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        var invalidRelation = request(); invalidRelation.put("responseReserveTokens", 5000);
        mvc.perform(put(ENDPOINT).with(user("administrator").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(invalidRelation))).andExpect(status().isBadRequest());
        assertThat(settings.get()).isEqualTo(DirectorSettings.defaults());
        mvc.perform(put(ENDPOINT).with(user("administrator").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryWindowMinutes").value(60)).andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(ENDPOINT).with(user("administrator").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(settings.get().version()).isEqualTo(2);
    }

    @Test void concurrentAdministratorsHaveExactlyOneWinnerAndNoPartialSave() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try { settings.update(small(1)); return true; }
                catch (ApiProblemException conflict) { assertThat(conflict.code()).isEqualTo("VERSION_CONFLICT"); return false; }
            })).toList();
            start.countDown();
            int winners = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(settings.get()).isEqualTo(small(2));
        assertThatThrownBy(() -> jdbc.sql("update director_settings set summary_tokens=20000").update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(settings.get()).isEqualTo(small(2));
    }

    @Test void savedSettingsAffectNewPlansImmediatelyButNeverReplanFrozenCompaction() {
        var assistant = AssistantMessage.builder().content("Verified public fact ".repeat(1500)).toolCalls(List.of(
                new AssistantMessage.ToolCall("read", "function", "read_project_summary", "{}"))).build();
        List<Message> history = List.of(new SystemMessage("Keep the creative constraint"), new UserMessage("Finish the task"),
                assistant, codec.toolResults(assistant, Map.of("read", mapper.createObjectNode().put("status", "SUCCEEDED"))));
        assertThat(AgentContextService.isCompaction(context.prepare(history, List.of()))).isFalse();
        settings.update(small(1));
        assertThat(context.estimate(history, List.of())).isBetween(4096, 7168);
        var frozen = context.prepare(history, List.of());
        assertThat(AgentContextService.isCompaction(frozen)).isTrue();
        var response = codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"goal":"Finish task","completed":["Read project"],"decisions":[],"pending":["Next step"],
                "nextSteps":["Continue"],"references":[],"blockers":[]}
                """)))));
        var expected = context.afterCompaction(frozen, response);
        var current = DirectorSettings.defaults();
        settings.update(new DirectorSettings(current.contextWindowTokens(), current.compactionThresholdPercent(),
                current.responseReserveTokens(), current.summaryTokens(), current.recentToolRounds(),
                current.recoveryWindowMinutes(), current.maxModelRetries(), current.retryMaxDelaySeconds(), current.maxRepairAttempts(), 2));
        assertThat(context.afterCompaction(frozen, response)).isEqualTo(expected);
        assertThat(AgentContextService.isCompaction(context.prepare(history, List.of()))).isFalse();
        assertThat(jdbc.sql("select count(*) from llm_turn").query(Long.class).single()).isZero();
    }

    @Test void httpPayloadsMatchTheOpenApiRequiredFieldsAndNumericRanges() throws Exception {
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.exists(directory.resolve("contracts/openapi.yaml"))) directory = directory.getParent();
        assertThat(directory).isNotNull();
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource(directory.resolve("contracts/openapi.yaml")));
        Map<?, ?> document = yaml.getObject();
        var components = (Map<?, ?>) document.get("components");
        var schemas = (Map<?, ?>) components.get("schemas");
        var response = mapper.readTree(mvc.perform(get(ENDPOINT).with(user("administrator").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (var entry : Map.of("DirectorSettings", response, "UpdateDirectorSettingsRequest", request()).entrySet()) {
            var schema = (Map<?, ?>) schemas.get(entry.getKey());
            var required = ((List<?>) schema.get("required")).stream().map(String.class::cast).toList();
            assertThat(entry.getValue().propertyNames()).containsExactlyInAnyOrderElementsOf(required);
            var properties = (Map<?, ?>) schema.get("properties");
            for (String key : required) {
                var property = (Map<?, ?>) properties.get(key);
                assertThat(entry.getValue().path(key).isIntegralNumber()).isTrue();
                assertThat(entry.getValue().path(key).asLong()).isGreaterThanOrEqualTo(((Number) property.get("minimum")).longValue());
                if (property.get("maximum") instanceof Number max) assertThat(entry.getValue().path(key).asLong()).isLessThanOrEqualTo(max.longValue());
            }
        }
    }

    private DirectorSettings small(long version) { return new DirectorSettings(8192, 50, 1024, 512, 0, 60, 12, 30, 2, version); }
    private tools.jackson.databind.node.ObjectNode request() {
        var result = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(small(1));
        result.remove("version"); result.put("expectedVersion", 1); return result;
    }
}
