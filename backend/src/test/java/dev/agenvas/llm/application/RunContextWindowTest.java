package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.json.JsonMapper;

class RunContextWindowTest {
    private final JsonMapper mapper = new JsonMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);

    @Test void oneHundredActivatedSkillsKeepFullInstructionsWithoutAccumulatingProtocolMessages() {
        List<Message> history = new ArrayList<>(List.of(new SystemMessage("Protocol"), new UserMessage("Task")));
        for (int index = 0; index < 100; index++) {
            String id = "skill-" + index;
            var assistant = AssistantMessage.builder().content("Activate Skill " + index).toolCalls(List.of(
                    new AssistantMessage.ToolCall(id, "function", "read_skill", "{}"))).build();
            history.add(assistant);
            history.add(codec.toolResults(assistant, Map.of(id, mapper.createObjectNode().put("status", "SUCCEEDED")
                    .set("data", mapper.createObjectNode().put("skillVersionId", id).put("content", "Complete instructions " + index)))));
            history = new ArrayList<>(RunContextWindow.project(history, mapper));
            assertThat(history.size()).isLessThan(80);
            history = new ArrayList<>(codec.requestMessages(codec.request(history, List.of())));
        }
        String saved = codec.request(history, List.of()).toString();
        for (int index = 0; index < 100; index++) assertThat(saved).contains("Complete instructions " + index);
        assertThat(history.stream().filter(message -> message.getMetadata().containsKey(RunContextWindow.SKILLS_KEY)))
                .singleElement();
        assertThat(RunContextWindow.project(history, mapper)).isEqualTo(history);
    }

    @Test void longHistoryKeepsProtocolPairsInstructionsSkillsAndObservableReferencesWithoutRawOldArguments() {
        List<Message> history = new ArrayList<>(List.of(new SystemMessage("Frozen protocol"), new UserMessage("Original request")));
        var skill = AssistantMessage.builder().content("Activate selected Skill").toolCalls(List.of(
                new AssistantMessage.ToolCall("skill", "function", "read_skill", "{}"))).build();
        history.add(skill);
        history.add(codec.toolResults(skill, Map.of("skill", mapper.createObjectNode().put("status", "SUCCEEDED")
                .set("data", mapper.createObjectNode().put("skillVersionId", "synthetic-skill-version").put("content", "Pinned Skill instructions")))));
        for (int index = 0; index < 300; index++) {
            String id = "read-" + index;
            var assistant = AssistantMessage.builder().content("Observed public fact " + index).toolCalls(List.of(
                    new AssistantMessage.ToolCall(id, "function", "read_artifacts", "{\"rawOldArgument\":\"do not copy\"}"))).build();
            history.add(assistant);
            history.add(codec.toolResults(assistant, Map.of(id, mapper.createObjectNode().put("status", "SUCCEEDED")
                    .set("data", mapper.createObjectNode().put("versionId", "version-" + index).put("content", "Old full text")))));
            var frozen = codec.request(history, List.of());
            history = new ArrayList<>(RunContextWindow.project(codec.requestMessages(frozen), mapper));
            assertThat(history.size()).isLessThan(80);
            // Every retained call still has its entire original reply, with no orphan IDs.
            for (int position = 0; position < history.size(); position++) if (history.get(position) instanceof AssistantMessage call
                    && !call.getToolCalls().isEmpty()) {
                assertThat(history.get(position + 1)).isInstanceOf(ToolResponseMessage.class);
                assertThat(((ToolResponseMessage) history.get(position + 1)).getResponses().getFirst().id())
                        .isEqualTo(call.getToolCalls().getFirst().id());
            }
            assertThat(frozen.path("messages").size()).isGreaterThanOrEqualTo(history.size());
        }
        assertThat(history.getFirst().getText()).isEqualTo("Frozen protocol");
        assertThat(codec.request(history, List.of()).toString()).contains("Original request", "Pinned Skill instructions");
        Message memory = history.stream().filter(message -> message.getMetadata().containsKey(RunContextWindow.MEMORY_KEY))
                .findFirst().orElseThrow();
        assertThat(memory.getText()).contains("Observed public fact", "versionId=version-")
                .doesNotContain("rawOldArgument", "Old full text");
        assertThat(memory.getText().length()).isLessThan(17_000);
        assertThat(RunContextWindow.project(history, mapper)).isEqualTo(history);
    }
}
