package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import tools.jackson.databind.json.JsonMapper;

/** Reproducible synthetic input accounting; not a provider usage measurement. */
class InitialContextCostTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void measureFixedInputsWithoutProviderCalls() throws Exception {
        String migration = Files
                .readString(Path.of("src/main/resources/db/migration/V45__english_director_default.sql"));
        String director = migration.split("\\$english_director\\$")[1];
        var policy = mapper.createObjectNode().put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION)
                .put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION)
                .put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION);
        policy.set("allowedTools", mapper.valueToTree(RunToolPolicy.current(true, true, true)));
        AgentRun run = mock(AgentRun.class);
        AgentRunService runs = mock(AgentRunService.class);
        MediaCapabilityService media = mock(MediaCapabilityService.class);
        when(runs.get(any(), any(), any())).thenReturn(run);
        var snapshot = mapper.createObjectNode().put("projectName", "Synthetic project")
                .put("aspectRatio", "LANDSCAPE_16_9").put("agentInstruction", director);
        snapshot.putArray("selection");
        var skills = snapshot.putArray("creativeSkills");
        for (int i = 0; i < 3; i++)
            skills.addObject().put("skillVersionId", new UUID(0, i + 1).toString())
                    .put("name", "Synthetic creative skill " + i)
                    .put("description", "Break down scripts and review visual continuity.");
        when(run.contextSnapshot()).thenReturn(snapshot);
        when(run.policySnapshot()).thenReturn(policy);
        when(run.instruction()).thenReturn("你好");
        var capabilities = mapper.createArrayNode();
        for (int i = 0; i < 8; i++)
            capabilities.addObject()
                    .put("capabilityId", new UUID(1, i + 1).toString()).put("name", "Synthetic video " + i)
                    .put("kind", "VIDEO").put("isDefault", i == 0).put("defaultDurationSeconds", 5)
                    .putObject("defaultParameters").put("aspectRatio", "16:9").put("resolution", "720P");
        when(media.planningSummary()).thenReturn(capabilities);
        int legacy = 0;
        int optimized = 0;
        for (boolean onDemand : List.of(false, true)) {
            policy.set("allowedTools", mapper.valueToTree(RunToolPolicy.current(true, true, true).stream()
                    .filter(name -> onDemand || !"load_toolset".equals(name)).toList()));
            List<Message> messages = new InitialModelContextService(runs, media).assemble(UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID());
            var tools = new ToolRegistry().initialDefinitions(policy);
            var tokenizer = new JTokkitTokenCountEstimator();
            int rawMessages = messages.stream().mapToInt(m -> tokenizer.estimate(m.getText())).sum();
            int rawSchemas = tools.stream().mapToInt(t -> tokenizer.estimate(t.getToolDefinition().inputSchema()))
                    .sum();
            int rawDescriptions = tools.stream().mapToInt(t -> tokenizer.estimate(t.getToolDefinition().name())
                    + tokenizer.estimate(t.getToolDefinition().description())).sum();
            int estimate = LlmTokenEstimator.estimate(messages, tools);
            if (onDemand)
                optimized = estimate;
            else
                legacy = estimate;
            System.out.printf(
                    "CONTEXT_COST onDemand=%s system=%d director=%d skillCatalog=%d mediaCatalog=%d other=%d schema=%d toolDescriptions=%d tools=%d estimatedWithMargin=%d%n",
                    onDemand, tokenizer.estimate(messages.getFirst().getText()), tokenizer.estimate(director),
                    messages.stream().filter(m -> m.getText().startsWith("Available creative Skills"))
                            .mapToInt(m -> tokenizer.estimate(m.getText())).sum(),
                    messages.stream().filter(m -> m.getText().startsWith("Published media capabilities"))
                            .mapToInt(m -> tokenizer.estimate(m.getText())).sum(),
                    rawMessages - messages.stream()
                            .filter(m -> m instanceof SystemMessage
                                    || m.getText().startsWith("Available creative Skills")
                                    || m.getText().startsWith("Published media capabilities"))
                            .mapToInt(m -> tokenizer.estimate(m.getText())).sum(),
                    rawSchemas, rawDescriptions, tools.size(), estimate);
            assertThat(messages).anyMatch(m -> m instanceof SystemMessage && director.equals(m.getText()));
            assertThat(messages.getLast()).isInstanceOf(UserMessage.class);
        }
        // A regression budget, not a promise that arbitrary user instructions or models fit 4K.
        assertThat(optimized).isLessThan(legacy / 2);
        String priorChinese = Files
                .readString(Path.of("src/main/resources/db/migration/V38__director_shot_media_proposals.sql"))
                .split("\\$shot_media_director\\$")[1];
        System.out.printf("DIRECTOR_COST english=%d priorChinese=%d (unchanged)%n",
                new JTokkitTokenCountEstimator().estimate(director),
                new JTokkitTokenCountEstimator().estimate(priorChinese));
        var registry = new ToolRegistry();
        var codec = new LlmProtocolCodec(mapper);
        var first = new InitialModelContextService(runs, media).assemble(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        var initialTools = registry.initialDefinitions(policy);
        for (String groups : List.of("TEXT", "CANVAS,TEXT,STORYBOARD,MEDIA,COMPOSITION,INSPECTION,SKILLS,LAYOUT")) {
            var arguments = mapper.createObjectNode();
            arguments.set("toolsets", mapper.valueToTree(groups.split(",")));
            var result = AgentToolset.load(policy, new UUID(0, 100), arguments.toString(), mapper);
            var reply = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                    new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("load", "load_toolset",
                            result.toString())))
                    .build();
            var tools = registry.continuationDefinitions(policy, codec.request(first, initialTools), List.of(reply),
                    codec);
            var messages = InitialModelContextService.forTools(policy, first, initialTools, tools);
            System.out.printf(
                    "LOADED_CONTEXT groups=%s tools=%d estimatedFixedInputs=%d (excludes tool history and media bytes)%n",
                    groups, tools.size(), LlmTokenEstimator.estimate(messages, tools));
        }

    }
}
