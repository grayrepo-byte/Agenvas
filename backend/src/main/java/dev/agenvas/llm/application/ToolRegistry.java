package dev.agenvas.llm.application;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** 只发布已实现的工具 Schema；回调本身拒绝执行，所有副作用必须经过持久化工具执行器。 */
@Component
public class ToolRegistry {
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.json.JsonMapper();

    /** Legacy Runs keep their complete frozen allowlist; new Runs begin with explicit discovery. */
    public List<ToolCallback> initialDefinitions(tools.jackson.databind.JsonNode policy) {
        var definitions = modelDefinitions(policy);
        return AgentToolset.onDemand(policy)
                ? definitions.stream()
                        .filter(tool -> AgentToolset.LOADER.equals(tool.getToolDefinition().name())).toList()
                : definitions;
    }

    /** Extend only from committed successful replies; already loaded definitions retain their frozen schemas. */
    public List<ToolCallback> continuationDefinitions(tools.jackson.databind.JsonNode policy,
            tools.jackson.databind.JsonNode priorRequest, List<org.springframework.ai.chat.messages.Message> messages,
            LlmProtocolCodec codec) {
        if (!AgentToolset.onDemand(policy))
            return modelDefinitions(policy);
        var allowed = RunToolPolicy.allowed(policy);
        var loaded = new java.util.LinkedHashMap<String, ToolCallback>();
        for (var tool : codec.requestTools(priorRequest, allowed))
            loaded.put(tool.getToolDefinition().name(), tool);
        var requested = new java.util.LinkedHashSet<String>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof org.springframework.ai.chat.messages.ToolResponseMessage replies))
                continue;
            for (var reply : replies.getResponses()) {
                if (!AgentToolset.LOADER.equals(reply.name()))
                    continue;
                var result = mapper.readTree(reply.responseData());
                if (!ToolResultStatus.SUCCEEDED.name().equals(result.path("status").asText()))
                    continue;
                for (var name : result.path("data").path("toolNames")) {
                    if (!name.isTextual() || !allowed.contains(name.asText()))
                        throw new IllegalStateException("Loaded tool is outside the frozen Run policy");
                    requested.add(name.asText());
                }
            }
            break;
        }
        for (var tool : modelDefinitions(policy)) {
            String name = tool.getToolDefinition().name();
            if (requested.contains(name))
                loaded.putIfAbsent(name, tool);
        }
        return List.copyOf(loaded.values());
    }

    public List<ToolCallback> modelDefinitions(tools.jackson.databind.JsonNode policy) {
        var allowed = RunToolPolicy.allowed(policy);
        return AgentTool.modelDefinitions(allowed);
    }
}
