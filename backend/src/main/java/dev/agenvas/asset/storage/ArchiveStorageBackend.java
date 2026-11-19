package dev.agenvas.asset.storage;

import java.util.Set;

/** A compiled permanent-archive transport. The router is not a backend and never discovers itself. */
public interface ArchiveStorageBackend extends RemoteStorageClient {
    /** Each persisted provider has exactly one backend; overlaps fail application startup. */
    Set<StorageProfile.Provider> providers();
}
