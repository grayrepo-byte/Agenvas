package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The Studio HTTP workflow owns batches and immutable archives without a CanvasItem. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ImageStudioPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    IdentityService identities;
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    MediaExecutionWorker worker;
    @Autowired
    JdbcClient jdbc;

    @Test
    void acceptsAndRestoresACompleteIdempotentBatchWithoutCanvasNodes() throws Exception {
        AdminPrincipal owner = identities.setup("studio-admin", "studio-password-123");
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        JsonNode studio = mapper.readTree(mvc.perform(get("/api/v1/image-studio/context").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertContract(studio, "ImageStudioContext");
        assertThat(studio.path("capabilities")).isNotEmpty();
        String capability = studio.path("defaultCapabilityId").asText();
        JsonNode selected = studio.path("capabilities").valueStream()
                .filter(item -> capability.equals(item.path("capabilityId").asText())).findFirst().orElseThrow();
        String request = "{\"capabilityId\":\"" + capability + "\",\"expectedCapabilityVersion\":"
                + selected.path("capabilityVersion").asInt() + ",\"prompt\":\"A quiet garden\","
                + "\"parameters\":{\"generationCount\":2,\"aspectRatio\":\"AUTO\",\"quality\":\"medium\"},\"referenceVersionIds\":[]}";
        JsonNode batch = mapper.readTree(mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-complete-batch").contentType("application/json").content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertContract(batch, "ImageStudioBatch");
        assertThat(batch.path("tasks")).hasSize(2);
        assertThat(batch.at("/parameters/aspectRatio").asText()).isEqualTo("1:1");
        JsonNode replay = mapper.readTree(mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-complete-batch").contentType("application/json").content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(replay).isEqualTo(batch);
        String reordered = request.replace("\"generationCount\":2,\"aspectRatio\":\"AUTO\",\"quality\":\"medium\"",
                "\"quality\":\"medium\",\"aspectRatio\":\"AUTO\",\"generationCount\":2");
        assertThat(mapper.readTree(mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-complete-batch").contentType("application/json").content(reordered))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())).isEqualTo(batch);
        mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-complete-batch").contentType("application/json")
                .content(request.replace("A quiet garden", "A different image")))
                .andExpect(status().isConflict());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = java.util.stream.IntStream.range(0, 20).mapToObj(ignored -> executor.submit(() -> {
                start.await();
                return mapper.readTree(mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                        .header("Idempotency-Key", "studio-complete-batch")
                        .contentType("application/json").content(request)).andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
            })).toList();
            start.countDown();
            for (var submission : submissions)
                assertThat(submission.get(30, TimeUnit.SECONDS)).isEqualTo(batch);
        }
        assertThat(jdbc.sql("select count(*) from image_studio_batch").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from task").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from usage_ledger where entry_type='RESERVATION'")
                .query(Long.class).single()).isEqualTo(2);
        var injected = (tools.jackson.databind.node.ObjectNode) mapper.readTree(request);
        injected.put("canvasItemId", UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-injected-node").contentType("application/json")
                .content(injected.toString())).andExpect(status().isBadRequest());
        injected.remove("canvasItemId");
        injected.remove("parameters");
        mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-missing-parameters").contentType("application/json")
                .content(injected.toString())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                .header("Idempotency-Key", "studio-invalid-count").contentType("application/json")
                .content(request.replace("generationCount\":2", "generationCount\":3")))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("select count(*) from image_studio_batch").query(Long.class).single()).isEqualTo(1);
        // The shared Worker deliberately claims one request at a time.
        assertThat(worker.submitOnce("studio-complete-worker")).isEqualTo(1);
        assertThat(worker.submitOnce("studio-complete-worker")).isEqualTo(1);
        JsonNode restored = mapper.readTree(mvc.perform(get("/api/v1/image-studio/batches/" + batch.path("id").asText())
                .with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertContract(restored, "ImageStudioBatch");
        assertThat(restored.path("tasks")).allSatisfy(task -> {
            assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(task.path("results")).hasSize(1);
        });
        String projectId = restored.path("projectId").asText();
        String assetId = restored.at("/tasks/0/results/0/assetId").asText();
        assertThat(mvc.perform(get("/api/v1/projects/" + projectId + "/assets/" + assetId + "/content").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).isNotEmpty();
        assertThat(jdbc.sql("select count(*) from canvas_item").query(Long.class).single()).isZero();
        assertThat(jdbc.sql(
                "select count(*) from task where jsonb_exists(input_json, 'canvasItemId') or jsonb_exists(input_json, 'sourceCanvasItemId')")
                .query(Long.class).single()).isZero();
        assertThat(mapper.readTree(mvc.perform(get("/api/v1/projects").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("items")).isEmpty();
        assertThat(mapper.readTree(mvc.perform(get("/api/v1/image-studio/batches").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("items"))
                .singleElement().satisfies(item -> assertThat(item).isEqualTo(restored));
        var deletion = readContract().at("/paths/~1api~1v1~1image-studio~1batches~1{batchId}/delete");
        var fileOption = deletion.path("parameters").valueStream()
                .filter(parameter -> "deleteFiles".equals(parameter.path("name").asText())).findFirst().orElseThrow();
        assertThat(fileOption.path("in").asText()).isEqualTo("query");
        assertThat(fileOption.at("/schema/type").asText()).isEqualTo("boolean");
        assertThat(fileOption.at("/schema/default").isBoolean()).isTrue();
        assertThat(fileOption.at("/schema/default").asBoolean()).isFalse();
        var removed = mvc.perform(delete("/api/v1/image-studio/batches/{batchId}", restored.path("id").asText())
                .with(auth).with(csrf())).andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(deletion.path("responses").has(Integer.toString(removed.getStatus()))).isTrue();
        assertThat(removed.getContentAsByteArray()).isEmpty();
    }

    private void assertContract(JsonNode value, String schemaName) {
        JsonNode schemas = readContract().at("/components/schemas");
        assertSchema(value, schemas.path(schemaName), schemas);
    }

    private JsonNode readContract() {
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("../contracts/openapi.yaml"));
        return mapper.valueToTree(yaml.getObject());
    }

    private void assertSchema(JsonNode value, JsonNode schema, JsonNode schemas) {
        if (schema.has("$ref")) {
            assertSchema(value, schemas.path(schema.path("$ref").asText().substring("#/components/schemas/".length())),
                    schemas);
            return;
        }
        JsonNode type = schema.path("type");
        String actualType = value.isNull()
                ? "null"
                : value.isObject()
                        ? "object"
                        : value.isArray()
                                ? "array"
                                : value.isString()
                                        ? "string"
                                        : value.isBoolean()
                                                ? "boolean"
                                                : value.isIntegralNumber() ? "integer" : "number";
        if (type.isArray())
            assertThat(type.valueStream().map(JsonNode::asText).toList()).contains(actualType);
        else if (type.isString())
            assertThat(actualType).isEqualTo(type.asText());
        if (schema.has("enum"))
            assertThat(schema.path("enum")).contains(value);
        if (value.isObject()) {
            assertThat(value.propertyNames())
                    .containsAll(schema.path("required").valueStream().map(JsonNode::asText).toList());
            if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean())
                assertThat(schema.path("properties").propertyNames()).containsAll(value.propertyNames());
            for (String key : value.propertyNames())
                assertSchema(value.path(key), schema.path("properties").path(key), schemas);
        } else if (value.isArray()) {
            for (JsonNode item : value)
                assertSchema(item, schema.path("items"), schemas);
        }
    }
}
