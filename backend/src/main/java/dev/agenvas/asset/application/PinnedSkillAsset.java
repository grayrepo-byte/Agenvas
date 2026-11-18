package dev.agenvas.asset.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import java.util.UUID;

/** Private source snapshot persisted by Skill publication. Existing field names are part of the checkpoint format. */
public record PinnedSkillAsset(UUID entryId, long entryVersion, String title,
        Artifact.Kind kind, String contentHash, String pinKey, Media media) {
}
