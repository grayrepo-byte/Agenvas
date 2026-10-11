package dev.agenvas.asset.storage;

import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Dispatches permanent archives without extending object-store signing to file protocols. */
@Component
public class ArchiveStorageClient implements RemoteStorageClient {
    private final ObjectStorageClient objects;
    private final SmbStorageClient smb;
    private final WebDavStorageClient webdav;
    public ArchiveStorageClient(ObjectStorageClient objects, SmbStorageClient smb, WebDavStorageClient webdav) {
        this.objects = objects; this.smb = smb; this.webdav = webdav;
    }
    private RemoteStorageClient client(StorageProfile profile) {
        return switch (profile.provider()) {
            case SMB -> smb;
            case WEBDAV -> webdav;
            case ALIYUN_OSS, TENCENT_COS, S3 -> objects;
        };
    }
    @Override public void put(StorageProfile p, String key, Path file, String mime, long size, String hash) { client(p).put(p, key, file, mime, size, hash); }
    @Override public boolean matches(StorageProfile p, String key, long size, String hash) { return client(p).matches(p, key, size, hash); }
    @Override public void verify(StorageProfile p, String key, long size, String hash) { client(p).verify(p, key, size, hash); }
    @Override public InputStream open(StorageProfile p, String key, long start, long length, long total) { return client(p).open(p, key, start, length, total); }
    @Override public void delete(StorageProfile p, String key) { client(p).delete(p, key); }
}
