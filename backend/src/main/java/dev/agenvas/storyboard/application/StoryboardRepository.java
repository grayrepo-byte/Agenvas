package dev.agenvas.storyboard.application;

import dev.agenvas.storyboard.domain.Storyboard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary called while the project event lock serializes board changes. */
public interface StoryboardRepository {
    Optional<Storyboard> current(UUID projectId);
    boolean append(Storyboard board, long expectedVersion);
    List<Storyboard> revisions(UUID projectId, long afterVersion, int limit);
    java.util.Map<UUID, Integer> maxShotRevisions(UUID projectId, java.util.Set<UUID> shotIds);
}
