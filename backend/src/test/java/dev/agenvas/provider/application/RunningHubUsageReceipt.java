package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.RunningHubResultArchive;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads the producer's saved identity before any original-task query is allowed. */
record RunningHubUsageReceipt(String origin, RunningHubDefinition.TargetType targetType,
        String targetId, List<Attempt> attempts) {
    record Attempt(String name, String providerRequestId) {}

    static RunningHubUsageReceipt parse(JsonNode receipt) {
        String origin = requiredText(receipt, "origin");
        var targetType = RunningHubDefinition.TargetType.valueOf(requiredText(receipt, "targetType"));
        String targetId = requiredText(receipt, "targetId");
        require(targetId.matches("[0-9]{1,32}"), "Invalid receipt targetId");
        JsonNode attempts = receipt.path("attempts");
        require(attempts.isArray() && !attempts.isEmpty(), "Receipt must contain completed attempts");
        var saved = new ArrayList<Attempt>();
        for (JsonNode attempt : attempts) {
            require("SUCCEEDED".equals(requiredText(attempt, "status")), "Receipt task has not succeeded");
            String id = requiredText(attempt, "providerRequestId");
            require(id.matches("[A-Za-z0-9_-]{1,160}"), "Invalid original providerRequestId");
            String name = requiredText(attempt, "name");
            require(name.matches("[A-Za-z0-9_-]{1,160}"), "Invalid receipt attempt name");
            saved.add(new Attempt(name, id));
        }
        return new RunningHubUsageReceipt(origin, targetType, targetId, List.copyOf(saved));
    }

    JsonNode normalizedUsage(ObjectMapper mapper, RunningHubAdapter adapter, JsonNode response) {
        require("SUCCESS".equals(response.path("status").asText()), "Original provider task has not succeeded");
        var videos = mapper.createArrayNode();
        for (JsonNode result : response.path("results")) {
            if (RunningHubResultArchive.outputKind(result.path("outputType").asText()) == RunningHubDefinition.OutputKind.VIDEO)
                videos.add(result);
        }
        // This usage-only check never downloads a companion ZIP. ZIP decoding and
        // archival belong to RunningHubRealResultArchiveIT, which has its own opt-in.
        require(!videos.isEmpty(), "Usage-only verification requires a direct video result");
        var direct = mapper.createObjectNode();
        direct.set("results", videos);
        direct.set("usage", response.path("usage"));
        var definition = new RunningHubDefinition(RunningHubDefinition.SCHEMA_VERSION, RunningHubDefinition.PROTOCOL_VERSION,
                targetType, targetId, List.of(), List.of(),
                List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.VIDEO, true, 1)),
                "default", false, false, null, null, null, null);
        return adapter.manifest(direct, definition, origin).usage();
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode text = value.path(field);
        require(text.isTextual() && !text.asText().isBlank(), "Missing or invalid receipt " + field);
        return text.asText();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
