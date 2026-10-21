package dev.agenvas.asset.storage;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import org.springframework.stereotype.Component;

/** Signed SMB2/3, pinned checked address, no DFS referrals, and non-overwriting final rename. */
@Component
public class SmbStorageClient implements RemoteStorageClient {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SmbStorageClient.class);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private final StorageSettingsService settings;
    private final Connector connector;
    @FunctionalInterface interface Connector { Handle connect(StorageProfile profile) throws IOException; }
    @org.springframework.beans.factory.annotation.Autowired
    public SmbStorageClient(StorageSettingsService settings) { this.settings = settings; this.connector = this::connect; }
    SmbStorageClient(StorageSettingsService settings, Connector connector) { this.settings = settings; this.connector = connector; }
    record Handle(SMBClient client, Connection connection, Session session, DiskShare share) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { share.close(); }
            finally { try { session.close(); } finally { try { connection.close(); } finally { client.close(); } } }
        }
    }
    private Handle connect(StorageProfile profile) throws IOException {
        URI endpoint = URI.create(profile.endpoint());
        // Resolve once and connect to that literal address, preventing a second unvalidated DNS lookup.
        var addresses = ObjectStorageClient.checkedDns(Dns.SYSTEM).lookup(endpoint.getHost());
        var client = new SMBClient(SmbConfig.builder().withSigningRequired(true).withDfsEnabled(false)
                .withTimeout(60, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build());
        Connection connection = null;
        Session session = null;
        try {
            connection = client.connect(addresses.getFirst().getHostAddress(), endpoint.getPort() < 0 ? 445 : endpoint.getPort());
            String[] credentials = settings.credentials(profile);
            String[] principal = principal(credentials[0]);
            session = connection.authenticate(new AuthenticationContext(principal[1], credentials[1].toCharArray(), principal[0]));
            var share = session.connectShare(endpoint.getPath().split("/", 3)[1]);
            if (!(share instanceof DiskShare disk)) { share.close(); throw new IOException("Storage share is not a disk"); }
            return new Handle(client, connection, session, disk);
        } catch (IOException | RuntimeException failure) {
            try { if (session != null) session.close(); }
            finally { try { if (connection != null) connection.close(); } finally { client.close(); } }
            throw RemoteFileSupport.unavailable();
        }
    }
    static String[] principal(String username) {
        int delimiter = username.indexOf('\\');
        return delimiter < 0 ? new String[] { "", username } : new String[] { username.substring(0, delimiter), username.substring(delimiter + 1) };
    }
    static String path(StorageProfile p, String key) {
        RemoteFileSupport.key(key);
        String[] parts = URI.create(p.endpoint()).getPath().split("/", 3);
        String base = parts.length == 3 ? parts[2] + "/" : "";
        return (base + key).replace('/', '\\');
    }
    private File readFile(DiskShare share, String path) {
        return share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }
    private boolean matches(DiskShare share, String path, long size, String hash) throws IOException {
        if (!share.fileExists(path)) return false;
        try (var file = readFile(share, path); var input = file.getInputStream()) {
            if (file.getFileInformation().getStandardInformation().getEndOfFile() != size
                    || !RemoteFileSupport.digestMatches(input, size, hash)) throw RemoteFileSupport.conflict();
            return true;
        }
    }
    @Override public boolean matches(StorageProfile p, String key, long size, String hash) {
        try (var handle = connector.connect(p)) { return matches(handle.share(), path(p, key), size, hash); }
        catch (ApiProblemException failure) { throw failure; }
        catch (IOException | RuntimeException failure) { throw RemoteFileSupport.unavailable(); }
    }
    @Override public void verify(StorageProfile p, String key, long size, String hash) {
        try (var handle = connector.connect(p); var file = readFile(handle.share(), path(p, key))) {
            if (file.getFileInformation().getStandardInformation().getEndOfFile() != size) throw RemoteFileSupport.unavailable();
        } catch (IOException | RuntimeException failure) { throw RemoteFileSupport.unavailable(); }
    }
    private void directories(DiskShare share, String path) {
        int index = path.indexOf('\\');
        while (index >= 0) {
            String directory = path.substring(0, index);
            // OPEN_IF is idempotent across concurrent archive writers.
            try (var ignored = share.openDirectory(directory, EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY), SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN_IF, EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE))) { /* ensure directory */ }
            index = path.indexOf('\\', index + 1);
        }
    }
    @Override public void put(StorageProfile p, String key, Path source, String mime, long size, String hash) {
        String target = path(p, key);
        String temporary = target + ".upload-" + UUID.randomUUID();
        try (var handle = connector.connect(p)) {
            var share = handle.share();
            if (matches(share, target, size, hash)) return;
            directories(share, target);
            try {
                try (var file = share.openFile(temporary, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.noneOf(SMB2ShareAccess.class),
                        SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                        var input = Files.newInputStream(source)) {
                    byte[] buffer = new byte[COPY_BUFFER_BYTES];
                    int read; long offset = 0;
                    while ((read = input.read(buffer)) != -1) {
                        long written = file.write(buffer, offset, 0, read);
                        if (written != read) throw new IOException("Incomplete archive write");
                        offset += written;
                    }
                    file.flush();
                    try (var remote = file.getInputStream()) {
                        if (offset != size || !RemoteFileSupport.digestMatches(remote, size, hash)) throw RemoteFileSupport.conflict();
                    }
                    file.rename(target, false);
                }
                if (!matches(share, target, size, hash)) throw RemoteFileSupport.unavailable();
            } finally {
                try { if (share.fileExists(temporary)) share.rm(temporary); }
                catch (RuntimeException failure) { LOGGER.warn("Temporary SMB archive cleanup failed"); }
            }
        } catch (ApiProblemException failure) { throw failure; }
        catch (IOException | RuntimeException failure) { throw RemoteFileSupport.unavailable(); }
    }
    @Override public InputStream open(StorageProfile p, String key, long start, long length, long total) {
        if (start < 0 || length <= 0 || start > total - length) throw new IllegalArgumentException("Invalid archive range");
        Handle handle = null;
        File file = null;
        try {
            handle = connector.connect(p);
            file = readFile(handle.share(), path(p, key));
            if (file.getFileInformation().getStandardInformation().getEndOfFile() != total) throw RemoteFileSupport.unavailable();
            Handle owned = handle; File opened = file;
            return new InputStream() {
                private long position = start;
                private long remaining = length;
                @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff; }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (count == 0) return 0;
                    if (remaining == 0) return -1;
                    try {
                        int read = opened.read(bytes, position, offset, (int) Math.min(Math.min(count, remaining), COPY_BUFFER_BYTES));
                        if (read <= 0) throw new IOException("Archive stream ended before recorded size");
                        position += read; remaining -= read; return read;
                    } catch (RuntimeException failure) { throw new IOException("Remote storage read failed"); }
                }
                @Override public long skip(long count) { long skipped = Math.min(Math.max(0, count), remaining); position += skipped; remaining -= skipped; return skipped; }
                @Override public void close() throws IOException {
                    try { opened.close(); } catch (RuntimeException failure) { throw new IOException("Remote storage close failed"); }
                    finally { try { owned.close(); } catch (IOException | RuntimeException failure) { throw new IOException("Remote storage close failed"); } }
                }
            };
        } catch (IOException | RuntimeException failure) {
            try { if (file != null) file.close(); }
            finally { try { if (handle != null) handle.close(); } catch (IOException | RuntimeException ignored) { /* sanitized failure below */ } }
            throw RemoteFileSupport.unavailable();
        }
    }
    @Override public void delete(StorageProfile p, String key) {
        try (var handle = connector.connect(p)) {
            String target = path(p, key);
            if (handle.share().fileExists(target)) handle.share().rm(target);
        } catch (IOException | RuntimeException failure) { throw RemoteFileSupport.unavailable(); }
    }
}
