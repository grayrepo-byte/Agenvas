package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded ZIP ingestion. Remote member names never become local paths or executable inputs. */
public final class RunningHubResultArchive implements AutoCloseable {
    static final long MAX_ARCHIVE_BYTES = 500L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 500L * 1024 * 1024;
    static final int MAX_ENTRIES = 256;
    private static final int MAX_NAME_LENGTH = 1024;
    private static final int BUFFER_BYTES = 8192;
    private static final Limits DEFAULT_LIMITS = new Limits(MAX_ARCHIVE_BYTES, MAX_EXPANDED_BYTES, MAX_ENTRY_BYTES, MAX_ENTRIES);
    private final Path directory;
    private final Limits limits;
    private final List<Path> scratch = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();

    public record Member(RunningHubDefinition.OutputKind kind, ProviderResultManifest.ArchiveEntry entry, Path file) {}
    record Limits(long archiveBytes, long expandedBytes, long entryBytes, int entries) {}

    private RunningHubResultArchive(Path directory, Limits limits) { this.directory = directory; this.limits = limits; }

    public static RunningHubResultArchive open(MediaPayload payload) {
        return open(payload, DEFAULT_LIMITS);
    }

    static RunningHubResultArchive open(MediaPayload payload, Limits limits) {
        RunningHubResultArchive archive = null;
        try (payload) {
            archive = new RunningHubResultArchive(Files.createTempDirectory("agenvas-rh-results-"), limits);
            Path zip = archive.temporary(".zip");
            try (OutputStream output = Files.newOutputStream(zip)) {
                copy(payload.stream(), output, limits.archiveBytes(), null, null);
            }
            archive.extract(zip);
            Files.delete(zip);
            archive.scratch.remove(zip);
            return archive;
        } catch (IOException | RuntimeException failure) {
            if (archive != null) try { archive.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw new RunningHubClient.ProtocolFailure();
        }
    }

    public List<Member> members() { return List.copyOf(members); }

    public MediaPayload download(ProviderResultManifest.ArchiveEntry expected) {
        Member member = members.stream().filter(item -> item.entry().equals(expected)).findFirst()
                .orElseThrow(RunningHubClient.ProtocolFailure::new);
        try { return new MediaPayload(Files.newInputStream(member.file()), "application/octet-stream"); }
        catch (IOException failure) { throw new RunningHubClient.ProtocolFailure(); }
    }

    private void extract(Path source) throws IOException {
        Set<String> names = new HashSet<>();
        long expanded = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(source.toFile())) {
            if (zip.size() > limits.entries()) throw new RunningHubClient.ProtocolFailure();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > limits.entries() || !safeName(entry.getName()) || !names.add(entry.getName()))
                    throw new RunningHubClient.ProtocolFailure();
                if (entry.getSize() < 0 || entry.getSize() > Math.min(limits.entryBytes(), limits.expandedBytes() - expanded))
                    throw new RunningHubClient.ProtocolFailure();
                String extension = extension(entry.getName());
                if ("zip".equals(extension)) throw new RunningHubClient.ProtocolFailure();
                var kind = entry.isDirectory() ? null : outputKind(extension);
                if (kind != null && members.size() >= RunningHubDefinition.MAX_OUTPUTS)
                    throw new RunningHubClient.ProtocolFailure();
                Path file = kind == null ? null : temporary(".media");
                MessageDigest digest = sha256();
                CRC32 crc = new CRC32();
                long size;
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream output = file == null ? OutputStream.nullOutputStream() : Files.newOutputStream(file)) {
                    size = copy(input, output, Math.min(limits.entryBytes(), limits.expandedBytes() - expanded), digest, crc);
                }
                expanded += size;
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new RunningHubClient.ProtocolFailure();
                if (kind != null) members.add(new Member(kind,
                        new ProviderResultManifest.ArchiveEntry(entry.getName(), HexFormat.of().formatHex(digest.digest())), file));
            }
        }
    }

    private Path temporary(String suffix) throws IOException {
        Path file = Files.createTempFile(directory, "output-", suffix);
        scratch.add(file);
        return file;
    }

    private static long copy(InputStream input, OutputStream output, long maximum, MessageDigest digest, CRC32 crc) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            size += count;
            if (size > maximum) throw new RunningHubClient.ProtocolFailure();
            output.write(buffer, 0, count);
            if (digest != null) digest.update(buffer, 0, count);
            if (crc != null) crc.update(buffer, 0, count);
        }
        return size;
    }

    private static boolean safeName(String name) {
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH || name.startsWith("/") || name.contains("\\") || name.contains("//")
                || name.indexOf('\0') >= 0 || name.contains(":")) return false;
        for (String part : name.split("/")) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > name.lastIndexOf('/') ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static RunningHubDefinition.OutputKind outputKind(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "image", "png", "jpg", "jpeg", "webp" -> RunningHubDefinition.OutputKind.IMAGE;
            case "video", "mp4" -> RunningHubDefinition.OutputKind.VIDEO;
            case "audio", "mp3", "wav", "flac" -> RunningHubDefinition.OutputKind.AUDIO;
            default -> null;
        };
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void close() {
        IOException failure = null;
        for (Path file : scratch) {
            try { Files.deleteIfExists(file); }
            catch (IOException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        }
        try { Files.deleteIfExists(directory); }
        catch (IOException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        if (failure != null) throw new RunningHubClient.ProtocolFailure();
    }
}
