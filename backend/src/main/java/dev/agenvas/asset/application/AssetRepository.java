package dev.agenvas.asset.application;

import dev.agenvas.asset.domain.Asset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Persists only fully archived files and applies a project boundary to reads. */
public interface AssetRepository {

    /** Inserts a READY asset after its file has been atomically installed. */
    void insert(Asset asset);

    /** Finds an asset only within the authenticated project. */
    Optional<Asset> find(UUID projectId, UUID assetId);

    /** Lists private media metadata for a project manifest, never object paths or bytes. */
    List<Asset> listProjectAssets(UUID projectId);
}
