package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Legacy receipts are projected only into new requests, preserving protocol pairs and immutable checkpoints. */
class LlmConversationServiceTest {
    private final JsonMapper mapper = new JsonMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID skillVersion = UUID.randomUUID();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final LlmTurnRepository turns = mock(LlmTurnRepository.class);
    private final ToolExecutionRepository tools = mock(ToolExecutionRepository.class);
    private final AgentMediaOutcomeService outcomes = mock(AgentMediaOutcomeService.class);
    private final AgentImageInputService images = mock(AgentImageInputService.class);

    @Test
    void newContinuationCompactsAllLegacyMediaReceiptsAndKeepsAnExplicitSkillRead() {
        var turn = priorTurn();
        JsonNode frozen = turn.request().deepCopy();
        ObjectNode result = mapper.createObjectNode().put("status", "SUCCEEDED");
        when(tools.find(project, runId, 4, "current-call")).thenReturn(Optional.of(new ToolExecution(
                UUID.randomUUID(), project, runId, 4, "current-call", "read_project_summary", "hash",
                ToolExecution.Status.COMPLETED, result)));
        when(outcomes.finalToolResult(project, runId, 4, "current-call", result)).thenReturn(result);

        var service = new LlmConversationService(runs, turns, tools, codec, outcomes, images, mapper);
        List<Message> continuation = service.afterToolRound(owner, project, runId, 4);

        assertCompactHistory(continuation, turn);
        assertThat(turn.request()).isEqualTo(frozen);
        var last = (ToolResponseMessage) continuation.getLast();
        assertThat(last.getResponses().getFirst().id()).isEqualTo("current-call");
        assertThat(mapper.readTree(last.getResponses().getFirst().responseData())).isEqualTo(result);
        // Once frozen, retries continue to restore their actual original payload, even after an upgrade.
        assertThat(codec.request(codec.requestMessages(turn.request()), List.of())).isEqualTo(frozen);
    }

    @Test
    void newRepairUsesTheSameProjectionWithoutChangingTheFailedTurnOrRunningTools() {
        var turn = priorTurn();
        JsonNode frozen = turn.request().deepCopy();
        Task task = mock(Task.class);
        when(task.projectId()).thenReturn(project);
        when(task.runId()).thenReturn(runId);
        when(task.input()).thenReturn(mapper.createObjectNode().put("repairFromStep", 4).put("stepIndex", 5)
                .put("repairErrorCode", "TOOL_ARGUMENT_INVALID").put("repairErrorDetail", "Synthetic error"));

        List<Message> repair = new RepairModelContextService(runs, turns, codec, mapper).assemble(owner, task);

        assertCompactHistory(repair, turn);
        assertThat(turn.request()).isEqualTo(frozen);
        ToolResponseMessage rejected = (ToolResponseMessage) repair.get(repair.size() - 2);
        assertThat(rejected.getResponses().getFirst().id()).isEqualTo("current-call");
        assertThat(rejected.getResponses().getFirst().responseData()).contains("TOOL_ARGUMENT_INVALID", "\"businessEffect\":false");
        org.mockito.Mockito.verifyNoInteractions(tools, outcomes, images);
    }

    @Test
    void multiSkillReferencesAreDeduplicatedAndFinalFailureDetailsRemainExact() {
        ObjectNode receipt = receipt();
        var second = mapper.createObjectNode().put("skillVersionId", UUID.randomUUID().toString())
                .put("name", "Second method").put("skillMd", "SECOND_BODY");
        for (JsonNode output : receipt.at("/data/outputs")) {
            var source = mapper.createObjectNode().put("schemaVersion", 2);
            source.putArray("skills").add(output.at("/preview/creativeSkill")).add(second);
            ((ObjectNode) output.path("preview")).set("creativeSkill", source);
        }
        var outcome = receipt.putObject("mediaApproval").put("status", "FAILED");
        outcome.putArray("tasks").addObject().put("status", "UNKNOWN").put("errorCode", "PROVIDER_SUBMISSION_UNKNOWN")
                .put("possibleExternalCost", true).put("artifactVersionId", UUID.randomUUID().toString());
        JsonNode original = receipt.deepCopy();

        JsonNode projected = AgentMediaToolResult.forModel(receipt);

        assertThat(projected.at("/data/creativeSkills")).hasSize(2);
        assertThat(projected.path("mediaApproval")).isEqualTo(outcome);
        assertThat(projected.at("/data/outputs/0/preview/parameters")).isEqualTo(receipt.at("/data/outputs/0/preview/parameters"));
        assertThat(projected.toString()).doesNotContain("skillMd", "SECOND_BODY", "RESOURCE_BODY", "DUPLICATE_PROMPT");
        assertThat(AgentMediaToolResult.forModel(projected)).isEqualTo(projected);
        assertThat(receipt).isEqualTo(original);
    }

    private LlmTurn priorTurn() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("Synthetic creative request"));
        var skillCall = assistant("skill-read", "read_skill");
        history.add(skillCall);
        history.add(codec.toolResults(skillCall, Map.of("skill-read", mapper.createObjectNode().put("content", "x".repeat(32 * 1024)))));
        for (int index = 0; index < 3; index++) {
            var call = assistant("old-media-" + index, "propose_media_generation");
            history.add(call);
            var reply = codec.toolResults(call, Map.of("old-media-" + index, receipt()));
            history.add(ToolResponseMessage.builder().responses(reply.getResponses()).metadata(Map.of("protocol_marker", "keep")).build());
        }
        ObjectNode request = codec.request(history, List.of());
        var turn = new LlmTurn(project, runId, 4, LlmTurn.Status.RESPONDED, 1, request,
                codec.response(new ChatResponse(List.of(new Generation(assistant("current-call", "read_project_summary"))))),
                Instant.now(), Instant.now());
        AgentRun run = mock(AgentRun.class);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode());
        when(runs.find(owner, project, runId)).thenReturn(Optional.of(run));
        when(turns.find(project, runId, 4)).thenReturn(Optional.of(turn));
        return turn;
    }

    private void assertCompactHistory(List<Message> history, LlmTurn turn) {
        assertThat(mapper.writeValueAsBytes(turn.request()).length).isGreaterThan(512 * 1024);
        assertThat(mapper.writeValueAsBytes(codec.request(history, List.of())).length).isLessThan(60 * 1024);
        var original = codec.requestMessages(turn.request());
        for (int index = 0; index < original.size(); index++) {
            if (!(original.get(index) instanceof ToolResponseMessage before)) {
                assertThat(history.get(index)).isEqualTo(original.get(index));
                continue;
            }
            var after = (ToolResponseMessage) history.get(index);
            assertThat(after.getMetadata()).isEqualTo(before.getMetadata());
            assertThat(after.getResponses().getFirst().id()).isEqualTo(before.getResponses().getFirst().id());
            assertThat(after.getResponses().getFirst().name()).isEqualTo(before.getResponses().getFirst().name());
            if ("read_skill".equals(before.getResponses().getFirst().name())) assertThat(after).isEqualTo(before);
            else assertThat(after.getResponses().getFirst().responseData()).doesNotContain("skillMd", "RESOURCE_BODY");
        }
    }

    private AssistantMessage assistant(String id, String name) {
        return AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(id, "function", name, "{}"))).build();
    }

    private ObjectNode receipt() {
        var receipt = mapper.createObjectNode().put("status", "SUCCEEDED").put("approvalId", UUID.randomUUID().toString());
        var data = receipt.putObject("data").put("status", "PENDING");
        var skill = mapper.createObjectNode().put("skillVersionId", skillVersion.toString()).put("name", "Synthetic method")
                .put("skillMd", "x".repeat(32 * 1024));
        skill.putArray("resources").addObject().put("content", "RESOURCE_BODY");
        var outputs = data.putArray("outputs");
        for (int index = 0; index < 6; index++) {
            var preview = outputs.addObject().put("kind", "IMAGE").put("title", "Synthetic image")
                    .put("artifactId", UUID.randomUUID().toString()).putObject("preview").put("prompt", "DUPLICATE_PROMPT");
            preview.putObject("parameters").put("aspectRatio", "16:9");
            preview.set("creativeSkill", skill.deepCopy());
        }
        return receipt;
    }
}
