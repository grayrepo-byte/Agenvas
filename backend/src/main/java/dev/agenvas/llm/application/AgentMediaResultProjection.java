package dev.agenvas.llm.application;

import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.provider.domain.ProviderFailure;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.application.ArchivedTaskResults;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Safe exact-output mapping shared by immutable model outcomes and the live archive projection. */
final class AgentMediaResultProjection {
    private AgentMediaResultProjection() {
    }

    static ObjectNode task(ObjectMapper mapper, AgentMediaApproval approval, Task task) {
        return task(mapper, approval, task, task.output());
    }

    static ObjectNode task(ObjectMapper mapper, AgentMediaApproval approval, Task task, JsonNode archivedOutput) {
        int outputIndex = approval.taskIds().indexOf(task.id());
        if (outputIndex < 0 || outputIndex >= approval.targets().path("outputs").size())
            throw new IllegalStateException("Media task is not a frozen approval output");
        JsonNode target = approval.targets().path("outputs").get(outputIndex);
        var item = mapper.createObjectNode().put("outputIndex", outputIndex)
                .put("canvasItemId", target.path("canvasItemId").asText())
                .put("taskId", task.id().toString()).put("kind", task.kind().name())
                .put("status", task.status().name());
        if (target.path("storyboard").isObject())
            item.set("storyboard", target.path("storyboard").deepCopy());
        if (task.errorCode() != null)
            item.put("errorCode", task.errorCode());
        if (task.output() != null && task.output().path("providerFailure").isObject()) {
            try {
                // Revalidate the public domain type; never copy arbitrary historical receipt fields.
                ProviderFailure failure = mapper.readerFor(ProviderFailure.class)
                        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(task.output().path("providerFailure"));
                item.set("providerFailure", mapper.valueToTree(failure));
            } catch (JacksonException malformedDiagnostic) {
                // Older malformed diagnostics are optional; task status and its stable code remain visible.
            }
        }
        // Provider receipts, credentials, URLs and billing payloads are never exposed here.
        putArchiveIdentity(item, archivedOutput);
        item.put("possibleExternalCost", task.status() == Task.Status.UNKNOWN
                || task.providerRequestId() != null || task.status() == Task.Status.SUBMITTING);
        return item;
    }

    static void putArchiveIdentity(ObjectNode item, JsonNode output) {
        var results = ArchivedTaskResults.read(output);
        if (results.primary() == null)
            return;
        putVersion(item, results.primary());
        if (results.hasAdditionalResults()) {
            var extras = item.putArray("additionalResults");
            for (var identity : results.additional()) {
                var extra = extras.addObject();
                putVersion(extra, identity);
                if (identity.hasCanvasItemId())
                    extra.put("canvasItemId",
                            identity.canvasItemId());
                if (identity.ordinal() != null)
                    extra.put("ordinal", identity.ordinal().intValue());
            }
        }
    }

    private static void putVersion(ObjectNode output, ArchivedTaskResults.Identity identity) {
        if (identity.artifactId() != null)
            output.put("artifactId", identity.artifactId());
        if (identity.artifactVersionId() != null)
            output.put("artifactVersionId", identity.artifactVersionId());
        if (identity.selected() != null)
            output.put("selected", identity.selected().booleanValue());
    }
}
