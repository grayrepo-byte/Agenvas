package dev.agenvas.canvas.application;

import dev.agenvas.canvas.domain.CanvasItem;
import tools.jackson.databind.node.ObjectNode;

/** Bounded public character identity; media IDs and profile bodies never grant read authority. */
public final class CharacterNodeSummary {
    private CharacterNodeSummary() {
    }

    public static ObjectNode append(ObjectNode target, CanvasItem item,
            CanvasCharacterNodeModels.CharacterNode node) {
        target.put("kind", CanvasItem.SubjectType.CHARACTER.name())
                .put("characterId", item.subjectId().toString());
        if (node == null)
            return target;
        var snapshot = node.snapshot();
        target.put("characterVersion", snapshot.characterVersion()).put("characterNodeVersion", node.version())
                .put("name", snapshot.name()).put("useAppearance", snapshot.useAppearance())
                .put("useVoice", snapshot.useVoice()).put("hasVoice", snapshot.voice() != null)
                .put("newerVersionAvailable", node.newerVersionAvailable())
                .put("imageReferenceCount", snapshot.look() == null ? 0 : snapshot.look().views().size());
        if (snapshot.look() != null)
            target.put("lookId", snapshot.look().id().toString())
                    .put("lookName", snapshot.look().name());
        return target;
    }
}
