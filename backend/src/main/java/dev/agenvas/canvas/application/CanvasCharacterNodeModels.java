package dev.agenvas.canvas.application;

import dev.agenvas.character.application.CharacterModels.CharacterBindingPreview;
import dev.agenvas.character.application.CharacterModels.CharacterSnapshot;
import dev.agenvas.character.application.CharacterModels.ClearMode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Fixed project-character selections and logical relationships; never provider subject IDs. */
public final class CanvasCharacterNodeModels {
    private CanvasCharacterNodeModels() {
    }

    public record Selection(
            UUID characterId,
            long characterVersion,
            UUID lookId,
            List<UUID> viewVersionIds,
            boolean useAppearance,
            boolean useVoice) {
        /** Provider subjects are unsupported; never silently accept a fabricated subject ID. */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownField(String field, tools.jackson.databind.JsonNode value) {
            throw new IllegalArgumentException(
                    "Unsupported character reference field; native provider subjects are not"
                            + " supported");
        }
    }

    public record CreateNode(
            Selection selection,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId,
            boolean locked) {
    }

    public record UpdateSelection(
            long expectedItemVersion, long expectedSelectionVersion, Selection selection) {
    }

    public record CharacterNode(
            UUID canvasItemId,
            Selection selection,
            CharacterSnapshot snapshot,
            long version,
            boolean newerVersionAvailable,
            List<CharacterConnection> connections) {
    }

    public record CharacterConnection(
            UUID id, UUID sourceCanvasItemId, UUID targetCanvasItemId, long version) {
    }

    public record Connect(
            UUID connectionId,
            UUID sourceCanvasItemId,
            UUID targetCanvasItemId,
            long expectedSourceItemVersion,
            long expectedSourceSelectionVersion,
            long expectedTargetItemVersion) {
    }

    public record Disconnect(long expectedVersion) {
    }

    public record Preview(
            long expectedBindingVersion, long expectedDraftVersion, ClearMode clearMode) {
    }

    public record Apply(
            UUID commandId,
            long expectedBindingVersion,
            long expectedDraftVersion,
            ClearMode clearMode,
            String previewHash) {
        public Preview previewRequest() {
            return new Preview(expectedBindingVersion, expectedDraftVersion, clearMode);
        }
    }

    public record NodeBindingPreview(
            String previewHash,
            CharacterBindingPreview characterPreview,
            List<CharacterConnection> connections) {
    }
}
