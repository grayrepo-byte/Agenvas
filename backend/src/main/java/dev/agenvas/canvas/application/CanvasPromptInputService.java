package dev.agenvas.canvas.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.domain.CanvasConnection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves live text-node bodies only at preflight/acceptance; workers use the frozen snapshot. */
@Service
public class CanvasPromptInputService {
    private final CanvasConnectionRepository connections;
    private final CanvasItemQueryService items;
    private final ArtifactService artifacts;

    public CanvasPromptInputService(CanvasConnectionRepository connections,
            CanvasItemQueryService items, ArtifactService artifacts) {
        this.connections = connections;
        this.items = items;
        this.artifacts = artifacts;
    }

    @Transactional(readOnly = true)
    public List<PromptInput> resolve(UUID ownerId, UUID projectId, UUID targetItemId) {
        items.requireArtifactItem(ownerId, projectId, targetItemId);
        return connections.list(ownerId, projectId).stream()
                .filter(edge -> edge.relationType() == CanvasConnection.RelationType.PROMPT_INPUT
                        && edge.targetCanvasItemId().equals(targetItemId))
                .sorted(Comparator.comparing(CanvasConnection::createdAt)
                        .thenComparing(edge -> edge.id().toString()))
                .map(edge -> {
                    var source = items.requireArtifactItem(ownerId, projectId, edge.sourceCanvasItemId());
                    var view = artifacts.get(ownerId, projectId, source.subjectId());
                    if (view.artifact().kind() != Artifact.Kind.TEXT || view.resourceDefaultVersion() == null) {
                        throw new IllegalStateException("Prompt connection must resolve to saved text");
                    }
                    var version = view.resourceDefaultVersion();
                    return new PromptInput(edge.id(), source.id(), source.subjectId(), version.id(),
                            source.title(), version.content().path("text").asText(""));
                }).toList();
    }

    /** Sources precede the local scene, separated by blank lines. Empty sources add no separators.
     * With no nonempty sources preserve the original local rendering for existing workflows.
     */
    public static String compose(List<PromptInput> inputs, String localPrompt) {
        List<String> texts = inputs.stream().map(PromptInput::text).map(String::strip)
                .filter(text -> !text.isEmpty()).toList();
        if (texts.isEmpty()) return localPrompt;
        return Stream.concat(texts.stream(), Stream.of(localPrompt.strip()))
                .filter(text -> !text.isEmpty()).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    public record PromptInput(UUID connectionId, UUID canvasItemId, UUID artifactId,
            UUID versionId, String title, String text) {}
}
