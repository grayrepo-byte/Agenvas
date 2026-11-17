package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic task snapshots exercise the shared public approval/model-outcome boundary. */
class AgentMediaResultProjectionTest {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID taskId = UUID.randomUUID();
    private final UUID canvasItemId = UUID.randomUUID();

    @Test
    void projectsOnlyValidatedDiagnosticFieldsAndNormalizesUntrustedModerationValues() {
        var output = mapper.createObjectNode();
        output.set("providerFailure", mapper.readTree("""
                {"stage":"SUBMIT","category":"MODERATION","httpStatus":400,"upstreamCode":null,
                  "nodeId":null,"fieldName":null,"message":"synthetic-private-prose",
                  "url":"https://example.invalid/private?token=synthetic-private-token",
                  "moderation":{"code":"moderation_blocked","stage":"synthetic-private-stage",
                    "categories":["sexual","synthetic-private-category","sexual"],
                    "reason":"synthetic-private-reason","input":"synthetic-private-prompt"}}
                """));
        output.put("providerReceipt", "synthetic-private-receipt");

        var projected = AgentMediaResultProjection.task(mapper, approval(), task(output));

        assertThat(projected.path("providerFailure")).isEqualTo(mapper.readTree("""
                {"stage":"SUBMIT","category":"MODERATION","httpStatus":400,"upstreamCode":null,
                  "nodeId":null,"fieldName":null,"moderation":{"code":"moderation_blocked",
                    "stage":"unknown","categories":["sexual","other"]}}
                """));
        assertThat(projected.path("errorCode").asText()).isEqualTo("OPENAI_IMAGE_MODERATION_BLOCKED");
        assertThat(projected.path("status").asText()).isEqualTo("FAILED");
        assertThat(projected.path("possibleExternalCost").asBoolean()).isFalse();
        assertThat(projected.toString()).doesNotContain("synthetic-private", "https://", "providerReceipt", "reason",
                "message");
    }

    @Test
    void archivedOutputProjectionKeepsTheCurrentTaskDiagnosticAndTheExactArchivedVersion() {
        var output = mapper.createObjectNode();
        output.set("providerFailure", mapper.readTree("""
                {"stage":"SUBMIT","category":"MODERATION","httpStatus":400,"upstreamCode":null,
                  "nodeId":null,"fieldName":null,"moderation":{"code":"moderation_blocked",
                    "stage":"output","categories":["sexual"]}}
                """));
        UUID archivedVersion = UUID.randomUUID();
        var archived = mapper.createObjectNode().put("artifactVersionId", archivedVersion.toString());
        archived.put("providerFailure", "synthetic-private-archive-value");

        var projected = AgentMediaResultProjection.task(mapper, approval(), task(output), archived);

        assertThat(projected.at("/providerFailure/moderation/stage").asText()).isEqualTo("output");
        assertThat(projected.at("/providerFailure/moderation/categories/0").asText()).isEqualTo("sexual");
        assertThat(projected.path("artifactVersionId").asText()).isEqualTo(archivedVersion.toString());
        assertThat(projected.toString()).doesNotContain("synthetic-private");
    }

    @Test
    void existingRunningHubDiagnosticsKeepTheirBoundedNodeAndFieldDetails() {
        var output = mapper.createObjectNode();
        output.set("providerFailure", mapper.readTree("""
                {"stage":"SUBMIT","category":"PARAMETER","httpStatus":200,"upstreamCode":803,
                  "nodeId":"27","fieldName":"aspect_ratio"}
                """));

        var projected = AgentMediaResultProjection.task(mapper, approval(), task(output));

        assertThat(projected.at("/providerFailure/category").asText()).isEqualTo("PARAMETER");
        assertThat(projected.at("/providerFailure/nodeId").asText()).isEqualTo("27");
        assertThat(projected.at("/providerFailure/fieldName").asText()).isEqualTo("aspect_ratio");
        assertThat(projected.at("/providerFailure/upstreamCode").asLong()).isEqualTo(803L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"synthetic-private-prose\"", "{}",
            "{\"stage\":\"SUBMIT\",\"category\":\"MODERATION\",\"httpStatus\":999}",
            "{\"stage\":\"SUBMIT\",\"category\":\"PARAMETER\",\"fieldName\":\"authorization\"}",
            "{\"stage\":\"SUBMIT\",\"category\":\"MODERATION\",\"moderation\":{\"code\":\"synthetic-private-code\"}}"})
    void malformedOlderDiagnosticsAreOmittedWithoutHidingTheTaskFailure(String rawDiagnostic) {
        var output = mapper.createObjectNode();
        output.set("providerFailure", mapper.readTree(rawDiagnostic));

        var projected = AgentMediaResultProjection.task(mapper, approval(), task(output));

        assertThat(projected.has("providerFailure")).isFalse();
        assertThat(projected.path("taskId").asText()).isEqualTo(taskId.toString());
        assertThat(projected.path("errorCode").asText()).isEqualTo("OPENAI_IMAGE_MODERATION_BLOCKED");
        assertThat(projected.toString()).doesNotContain("synthetic-private");
    }

    private AgentMediaApproval approval() {
        ObjectNode targets = mapper.createObjectNode();
        targets.putArray("outputs").addObject().put("canvasItemId", canvasItemId.toString());
        return new AgentMediaApproval(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "synthetic-media-call", UUID.randomUUID(), mapper.createObjectNode(), targets,
                List.of(taskId), null, AgentMediaApproval.Status.FAILED, 1, NOW, NOW, NOW,
                "synthetic-decision", "synthetic-hash");
    }

    private Task task(JsonNode output) {
        return new Task(taskId, UUID.randomUUID(), UUID.randomUUID(), "synthetic-media-step",
                Task.Kind.IMAGE_GENERATION,
                Task.Status.FAILED, false, mapper.createObjectNode(), "synthetic-hash", output, null,
                1, null, null, null, 0, 1, "OPENAI_IMAGE_MODERATION_BLOCKED", NOW, NOW, NOW);
    }
}
