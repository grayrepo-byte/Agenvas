package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentContextServiceTest {
    private final JsonMapper mapper = new JsonMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);
    private final DirectorSettings properties = new DirectorSettings(8192, 50, 1024, 512, 2,
            1440, 288, 300, 4, 1);
    private final AgentContextService context = new AgentContextService(source(properties), mapper, codec);
    private static final String HANDOFF = """
            {"goal":"Finish all 100 shots","completed":["Shots 1-20 saved"],"decisions":["Keep the blue coat"],
             "pending":["Shots 21-100"],"nextSteps":["Read the current storyboard CAS"],
             "references":["exact-version-1"],"blockers":["Approval PENDING; UNKNOWN must not be resubmitted"]}
            """;

    @Test void shortRequestsRemainByteForByteUnchangedAndToolSchemasConsumeBudget() {
        List<Message> messages = List.of(new SystemMessage("Rules"), new UserMessage("Make a film"));
        assertThat(context.prepare(messages, List.of())).isEqualTo(codec.request(messages, List.of()));
        JsonNode policy = mapper.createObjectNode().put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION)
                .put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION)
                .put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION)
                .set("allowedTools", mapper.valueToTree(RunToolPolicy.CURRENT));
        assertThat(context.estimate(messages, new ToolRegistry().modelDefinitions(policy)))
                .isGreaterThan(context.estimate(messages, List.of()));
    }

    @Test void compactsBeforeMessageLimitWithoutDiscardingSourceAndKeepsCompleteRecentToolPairs() {
        List<Message> history = history(8, "连续性事实与精确镜头引用。".repeat(200));
        JsonNode original = codec.request(history, List.of());
        JsonNode request = context.prepare(history, List.of());
        assertThat(AgentContextService.isCompaction(request)).isTrue();
        assertThat(request.path("tools")).isEmpty();
        assertThat(request.toString()).contains("连续性事实", "exact-version-0", "exact-version-7");
        int chunks = 0;
        while (AgentContextService.isCompaction(request)) {
            assertThat(context.estimate(codec.requestMessages(request), List.of())).isLessThanOrEqualTo(properties.inputBudget());
            request = context.afterCompaction(mapper.readTree(request.toString()), response(HANDOFF));
            assertThat(++chunks).isLessThan(30);
        }
        assertThat(chunks).isGreaterThan(1);
        List<Message> resumed = codec.requestMessages(request);
        assertThat(resumed).contains(history.getFirst(), history.get(1));
        assertThat(request.toString()).contains("Finish all 100 shots", "blue coat", "UNKNOWN", "exact-version-1");
        for (int index = 0; index < resumed.size(); index++) if (resumed.get(index) instanceof ToolResponseMessage) {
            assertThat(resumed.get(index - 1)).isInstanceOf(AssistantMessage.class);
        }
        assertThat(codec.request(history, List.of())).isEqualTo(original);
        assertThat(context.estimate(resumed, List.of())).isLessThanOrEqualTo(properties.inputBudget());
    }

    @Test void resourceMessageGuardStillCompactsBelowTheTokenTriggerAndKeepsPreviousHandoffs() {
        var large = new AgentContextService(source(DirectorSettings.defaults()), mapper, codec);
        var history = history(LlmProtocolCodec.MAX_MESSAGES / 2, "Small public fact");
        history.add(2, UserMessage.builder().text("Earlier summary: keep the blue coat")
                .metadata(Map.of(AgentContextService.SUMMARY, 1)).build());
        assertThat(large.estimate(history, List.of())).isLessThan(500_000);
        JsonNode compact = large.prepare(history, List.of());
        assertThat(compact.toString()).contains("Earlier summary", "blue coat");
        JsonNode next = large.afterCompaction(compact, response(HANDOFF));
        assertThat(AgentContextService.isCompaction(next)).isFalse();
        assertThat(codec.requestMessages(next).size()).isLessThan(20);
        assertThat(codec.requestMessages(next).stream().filter(ToolResponseMessage.class::isInstance).count()).isEqualTo(4);
    }

    @Test void pinsActivatedSkillInstructionsAndCurrentImageReferencesOutsideTheLossySummary() {
        var history = history(1, "old data");
        var activation = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("activate", "function", "read_skill", "{}"))).build();
        history.add(2, activation);
        history.add(3, codec.toolResults(activation, Map.of("activate", mapper.createObjectNode().put("status", "SUCCEEDED")
                .set("data", mapper.createObjectNode().put("skillVersionId", "fixed-skill").put("content", "Full method stays intact")))));
        history.add(UserMessage.builder().text("Current exact image").metadata(Map.of(
                AgentImageInputService.METADATA_KEY, List.of(Map.of("artifactId", "exact-artifact", "versionId", "exact-image")))).build());
        history.addAll(history(40, "later").subList(2, 82));
        JsonNode request = context.prepare(history, List.of());
        do { request = context.afterCompaction(request, response(HANDOFF)); }
        while (AgentContextService.isCompaction(request));
        assertThat(request.toString()).contains("Full method stays intact", "fixed-skill", "exact-image");
    }

    @Test void rejectsMalformedUnboundedOrToolBearingSummariesAndRepairsWithoutChangingSource() {
        JsonNode request = context.prepare(history(80, "Public fact"), List.of());
        for (String invalid : List.of("", "not JSON", "{\"goal\":\"invented\"}", HANDOFF.replace("Shots 21-100", "word ".repeat(1000)))) {
            assertThatThrownBy(() -> context.summary(request, response(invalid))).isInstanceOf(IllegalArgumentException.class);
        }
        var assistant = AssistantMessage.builder().content(HANDOFF).toolCalls(List.of(
                new AssistantMessage.ToolCall("forged", "function", "create_text", "{}"))).build();
        assertThatThrownBy(() -> context.summary(request, codec.response(new ChatResponse(List.of(new Generation(assistant))))))
                .isInstanceOf(IllegalArgumentException.class);
        JsonNode repaired = context.repair(request);
        assertThat(repaired.path(AgentContextService.COMPACTION)).isEqualTo(request.path(AgentContextService.COMPACTION));
        assertThat(codec.requestMessages(repaired)).hasSize(codec.requestMessages(request).size() + 1);
        assertThat(context.repair(repaired)).isEqualTo(repaired);
    }

    @Test void neverTruncatesFixedInstructionsToPretendTheyFit() {
        assertThatThrownBy(() -> context.prepare(List.of(new SystemMessage("hard constraint ".repeat(10_000)),
                new UserMessage("Task")), List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENT_CONTEXT_BUDGET_EXCEEDED");
    }

    @Test void oneMillionWindowTriggersAtHalfTheWindowBeforeTheHardInputBudget() {
        var defaults = DirectorSettings.defaults();
        assertThat(defaults.contextWindowTokens()).isEqualTo(1_000_000);
        assertThat(defaults.compactionThresholdPercent()).isEqualTo(50);
        assertThat(defaults.compactionTriggerTokens()).isEqualTo(500_000);
        assertThat(defaults.inputBudget()).isEqualTo(991_808);
        var below = history(1, "word ".repeat(1600));
        var above = history(1, "word ".repeat(4000));
        assertThat(context.estimate(below, List.of())).isLessThan(properties.compactionTriggerTokens());
        assertThat(context.prepare(below, List.of())).isEqualTo(codec.request(below, List.of()));
        assertThat(context.estimate(above, List.of())).isBetween(properties.compactionTriggerTokens(), properties.inputBudget());
        assertThat(AgentContextService.isCompaction(context.prepare(above, List.of()))).isTrue();
    }

    @Test void smallHistoriesAboveTheFormerEightyMessageLimitRemainIntactInTheOneMillionWindow() {
        var large = new AgentContextService(source(DirectorSettings.defaults()), mapper, codec);
        var history = history(60, "Small public fact");
        assertThat(large.prepare(history, List.of())).isEqualTo(codec.request(history, List.of()));
    }

    @Test void fixedInstructionsOverTheSoftTriggerAreAllowedWhenTheyFitTheHardWindow() {
        List<Message> fixed = List.of(new SystemMessage("word ".repeat(4000)), new UserMessage("Task"));
        assertThat(context.estimate(fixed, List.of())).isBetween(properties.compactionTriggerTokens(), properties.inputBudget());
        assertThat(context.prepare(fixed, List.of())).isEqualTo(codec.request(fixed, List.of()));
    }

    private ArrayList<Message> history(int rounds, String result) {
        var messages = new ArrayList<Message>(List.of(new SystemMessage("Server permissions stay pinned"),
                new UserMessage("Current Run request: Finish all 100 shots")));
        for (int index = 0; index < rounds; index++) {
            String id = "call-" + index;
            var assistant = AssistantMessage.builder().content("Observed shot " + index).toolCalls(List.of(
                    new AssistantMessage.ToolCall(id, "function", "read_project_summary", "{}"))).build();
            messages.add(assistant);
            messages.add(codec.toolResults(assistant, Map.of(id, mapper.createObjectNode().put("status", "SUCCEEDED")
                    .put("artifactVersionId", "exact-version-" + index).put("text", result))));
        }
        return messages;
    }
    private JsonNode response(String text) { return codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
    private static DirectorSettingsService source(DirectorSettings value) {
        var source = org.mockito.Mockito.mock(DirectorSettingsService.class);
        org.mockito.Mockito.when(source.get()).thenReturn(value);
        return source;
    }

}
