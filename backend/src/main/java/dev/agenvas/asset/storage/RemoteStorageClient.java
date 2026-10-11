package dev.agenvas.asset.storage;

import java.io.InputStream;
import java.nio.file.Path;

/** Private immutable archives; callers finish database transactions before transport operations. */
public interface RemoteStorageClient {
    void put(StorageProfile profile, String key, Path file, String mime, long size, String hash);
    boolean matches(StorageProfile profile, String key, long size, String hash);
    /** Metadata-only availability check for GET/HEAD; recovery uses the stronger matches check. */
    default void verify(StorageProfile profile, String key, long size, String hash) {
        if (!matches(profile, key, size, hash)) throw new IllegalStateException("READY remote object missing");
    }
    InputStream open(StorageProfile profile, String key, long start, long length, long total);
    void delete(StorageProfile profile, String key);
}
