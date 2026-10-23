package dev.agenvas.character.application;

import dev.agenvas.artifact.domain.MediaDraft;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static dev.agenvas.character.application.CharacterModels.*;

/** Applies explicit character input to the existing draft, tracking only contributions we added. */
public interface CharacterDraftGateway {
    record DraftState(Map<UUID, List<UUID>> ownedReferenceSources, String promptBlock, String speakerId) {
        public static DraftState empty() { return new DraftState(Map.of(), "", null); }
    }
    record Plan(MediaDraft candidateDraft, DraftState state, List<String> warnings, List<String> changes,
            Map<UUID, List<UUID>> sourceIdsToRelease) {}
    record Applied(MediaDraft draft, DraftState state) {}
    Plan preview(UUID owner, UUID project, UUID item, long expectedDraftVersion,
            DraftState before, List<CharacterSnapshot> after, ClearMode clearMode);
    Applied apply(UUID owner, UUID project, UUID item, long expectedDraftVersion, Plan plan);
    String describeSnapshots(List<CharacterSnapshot> snapshots);
}
