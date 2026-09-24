package dev.agenvas.plan.application;

import java.util.Optional;
import java.util.UUID;

/** Project-scoped persistence boundary for explicit keyframe choices. */
public interface ShotKeyframeSelectionRepository {

    /** Reads the selected exact image version for one shot. */
    Optional<ShotKeyframeSelection> find(UUID projectId, UUID shotArtifactId);

    /** Inserts a first selection; returns false when one already exists. */
    boolean insert(ShotKeyframeSelection selection);

    /** Replaces one selection only at the observed version. */
    boolean update(ShotKeyframeSelection selection, long expectedVersion);
}
