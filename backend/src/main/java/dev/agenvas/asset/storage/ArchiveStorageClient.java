package dev.agenvas.asset.storage;

import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Dispatches permanent archives without extending object-store signing to file protocols. */
@Component
public class ArchiveStorageClient implements RemoteStorageClient {
    private final java.util.Map<StorageProfile.Provider, ArchiveStorageBackend> backends;
    public ArchiveStorageClient(java.util.List<ArchiveStorageBackend> installed) {
        var indexed = new java.util.EnumMap<StorageProfile.Provider, ArchiveStorageBackend>(
                StorageProfile.Provider.class);
        for (var backend : java.util.List.copyOf(installed)) {
            var providers = java.util.Set.copyOf(backend.providers());
            if (providers.isEmpty())
                throw new IllegalStateException("Archive backend declares no providers");
            for (var provider : providers)
                if (indexed.putIfAbsent(provider, backend) != null)
                    throw new IllegalStateException("Duplicate archive backend for " + provider);
        }
        var missing = java.util.EnumSet.allOf(StorageProfile.Provider.class);
        missing.removeAll(indexed.keySet());
        if (!missing.isEmpty())
            throw new IllegalStateException("Missing archive backends: " + missing);
        backends = java.util.Map.copyOf(indexed);
    }
    private RemoteStorageClient client(StorageProfile profile) {
        return backends.get(java.util.Objects.requireNonNull(profile.provider(), "Storage provider"));
    }
    @Override
    public void put(StorageProfile p, String key, Path file, String mime, long size, String hash) {
        client(p).put(p, key, file, mime, size, hash);
    }
    @Override
    public boolean matches(StorageProfile p, String key, long size, String hash) {
        return client(p).matches(p, key, size, hash);
    }
    @Override
    public void verify(StorageProfile p, String key, long size, String hash) {
        client(p).verify(p, key, size, hash);
    }
    @Override
    public InputStream open(StorageProfile p, String key, long start, long length, long total) {
        return client(p).open(p, key, start, length, total);
    }
    @Override
    public void delete(StorageProfile p, String key) {
        client(p).delete(p, key);
    }
}
