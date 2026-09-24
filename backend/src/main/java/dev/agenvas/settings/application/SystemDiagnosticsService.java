package dev.agenvas.settings.application;

import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.llm.application.LlmModeProperties;
import dev.agenvas.provider.application.ProviderModeProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoProperties;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Produces safe, read-only installation diagnostics without contacting a paid Provider. */
@Service
public class SystemDiagnosticsService {

    private final JdbcClient jdbc;
    private final Path storageRoot;
    private final LlmModeProperties llmMode;
    private final LlmProviderConfigService llmConfigs;
    private final ProviderModeProperties mediaMode;
    private final ComfyUiProperties comfy;
    private final ComfyUiImageProperties image;
    private final ComfyUiVideoProperties video;
    private final Clock clock;

    public SystemDiagnosticsService(JdbcClient jdbc, AssetProperties storage,
            LlmModeProperties llmMode, LlmProviderConfigService llmConfigs,
            ProviderModeProperties mediaMode, ComfyUiProperties comfy,
            ComfyUiImageProperties image, ComfyUiVideoProperties video, Clock clock) {
        this.jdbc = jdbc;
        this.storageRoot = storage.root().toAbsolutePath().normalize();
        this.llmMode = llmMode;
        this.llmConfigs = llmConfigs;
        this.mediaMode = mediaMode;
        this.comfy = comfy;
        this.image = image;
        this.video = video;
        this.clock = clock;
    }

    /** Reports bounded status values; no endpoint, path, credential or free-text error escapes. */
    public Snapshot snapshot() {
        boolean databaseAvailable;
        List<RecentError> recentErrors;
        try {
            recentErrors = jdbc.sql("""
                            select status, count(*) as total, max(updated_at) as last_at
                            from task
                            where status in ('FAILED', 'UNKNOWN', 'BLOCKED')
                              and updated_at >= now() - interval '7 days'
                            group by status
                            order by last_at desc
                            """)
                    .query((row, ignored) -> new RecentError(row.getString("status"),
                            row.getLong("total"),
                            row.getTimestamp("last_at").toInstant()))
                    .list();
            databaseAvailable = true;
        } catch (DataAccessException unavailable) {
            databaseAvailable = false;
            recentErrors = List.of();
        }
        boolean llmConfigured = llmMode.mode() == LlmModeProperties.Mode.MOCK;
        boolean llmVerified = false;
        if (databaseAvailable && llmMode.mode() == LlmModeProperties.Mode.CONFIGURED) {
            try {
                LlmProviderConfigService.Status status = llmConfigs.status();
                llmConfigured = status.configured();
                llmVerified = status.toolCallingVerified();
            } catch (DataAccessException unavailable) {
                databaseAvailable = false;
                recentErrors = List.of();
            }
        }
        boolean mockMedia = mediaMode.mode() == ProviderModeProperties.Mode.MOCK;
        boolean imageConfigured = mockMedia || (filled(comfy.endpoint())
                && filled(image.checkpoint()));
        boolean videoConfigured = mockMedia || (video.enabled()
                && filled(comfy.endpoint()) && filled(video.diffusionModel())
                && filled(video.textEncoder()) && filled(video.vae())
                && filled(video.clipVision()));
        return new Snapshot(clock.instant(),
                databaseAvailable ? Status.AVAILABLE : Status.UNAVAILABLE,
                storageAvailable(storageRoot) ? Status.AVAILABLE : Status.UNAVAILABLE,
                llmMode.mode().name(), llmConfigured, llmVerified,
                mediaMode.mode().name(), imageConfigured, videoConfigured, recentErrors);
    }

    /** An absent archive root is usable only if its nearest existing parent is writable. */
    static boolean storageAvailable(Path root) {
        Path candidate = root;
        while (candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.getParent();
        }
        return candidate != null && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                && Files.isReadable(candidate) && Files.isWritable(candidate);
    }

    private boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    /** Fixed status literals avoid leaking infrastructure details to the browser. */
    public enum Status { AVAILABLE, UNAVAILABLE }

    /** Recent errors are counts by allowlisted durable Task state, not raw messages. */
    public record RecentError(String status, long count, Instant lastAt) {}

    /** Public administrator view; all Provider fields describe configuration, not connectivity. */
    public record Snapshot(Instant checkedAt, Status database, Status storage,
            String llmMode, boolean llmConfigured, boolean llmToolCallingVerified,
            String mediaMode, boolean imageConfigured, boolean videoConfigured,
            List<RecentError> recentErrors) {}
}
