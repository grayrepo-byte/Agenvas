package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.json.JsonMapper;

class ToolBatchExecutionServiceTest {
    @Test void separateProjectAndSkillImageCallsCannotBypassSequentialReading() {
        var mapper = new JsonMapper();
        var tools = mock(ToolExecutionService.class);
        var context = new TrustedToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var lease = mock(Task.class);
        var project = mapper.createObjectNode().put("status", "SUCCEEDED");
        project.putArray("data").addObject().put(AgentImageInputService.SEQUENTIAL_PREVIEW_KEY, true);
        var skill = mapper.createObjectNode().put("status", "SUCCEEDED");
        skill.putObject("data").put(AgentImageInputService.SEQUENTIAL_PREVIEW_KEY, true);
        when(tools.executeLeased(context, 0, "project", lease, "worker")).thenReturn(project);
        when(tools.executeLeased(context, 0, "skill", lease, "worker")).thenReturn(skill);
        assertThatThrownBy(() -> new ToolBatchExecutionService(tools).executeLeased(context, 0,
                List.of(new AssistantMessage.ToolCall("project", "function", "read_artifacts", "{}"),
                        new AssistantMessage.ToolCall("skill", "function", "read_skill_asset", "{}")), lease, "worker"))
                .isInstanceOf(ApiProblemException.class).extracting(failure -> ((ApiProblemException) failure).code())
                .isEqualTo("TOOL_ARGUMENT_INVALID");
        verify(tools).executeLeased(context, 0, "skill", lease, "worker");
    }
}
