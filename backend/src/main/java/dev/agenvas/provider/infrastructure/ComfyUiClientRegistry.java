package dev.agenvas.provider.infrastructure;

import dev.agenvas.plan.application.PlanProviderProperties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Retains exact historical origins for lookup only; new submissions use the active client. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiClientRegistry implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final PlanProviderProperties provider;
    private final ComfyUiClient active;
    private final String activeOrigin;
    private final boolean recoveryMode;
    private final Map<Integer, ComfyUiClient> historical = new ConcurrentHashMap<>();

    public ComfyUiClientRegistry(JdbcClient jdbc, ObjectMapper mapper,
            PlanProviderProperties provider, ComfyUiProperties properties, ComfyUiClient active,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.provider = provider;
        this.active = active;
        this.activeOrigin = ComfyUiClient.checkedOrigin(properties.endpoint()).toString();
        this.recoveryMode = recoveryMode;
    }

    /** Startup refuses to reuse one numeric version for a different exact origin. */
    @Override
    public void run(ApplicationArguments arguments) {
        if (!recoveryMode) registerActive();
    }

    /** Register one startup version; exact same-version restarts preserve its original row. */
    public void registerActive() {
        int latest = jdbc.sql("select coalesce(max(config_version), 0) "
                        + "from comfyui_config_version")
                .query(Integer.class).single();
        if (provider.configVersion() < latest) {
            throw new IllegalStateException("ComfyUI config version cannot decrease");
        }
        jdbc.sql("""
                        insert into comfyui_config_version
                            (config_version, origin, origin_sha256)
                        values (:version, :origin, :sha256)
                        on conflict (config_version) do nothing
                        """)
                .param("version", provider.configVersion())
                .param("origin", activeOrigin)
                .param("sha256", active.originSha256()).update();
        var recorded = jdbc.sql("""
                        select origin, origin_sha256 from comfyui_config_version
                        where config_version = :version
                        """)
                .param("version", provider.configVersion())
                .query((row, index) -> new RecordedOrigin(row.getString("origin"),
                        row.getString("origin_sha256")))
                .single();
        if (!activeOrigin.equals(recorded.origin())
                || !active.originSha256().equals(recorded.sha256())) {
            throw new IllegalStateException(
                    "ComfyUI config version was reused for a different origin");
        }
    }

    /** Resolves only an exact version/fingerprint pair saved with the original attempt. */
    public Optional<ComfyUiClient> forOriginal(int version, String originSha256) {
        if (recoveryMode || version < 1 || originSha256 == null
                || !originSha256.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        var recorded = jdbc.sql("""
                        select origin, origin_sha256 from comfyui_config_version
                        where config_version = :version
                        """)
                .param("version", version)
                .query((row, index) -> new RecordedOrigin(row.getString("origin"),
                        row.getString("origin_sha256")))
                .optional();
        if (recorded.isEmpty() || !originSha256.equals(recorded.get().sha256())) {
            return Optional.empty();
        }
        // Revalidate the persisted endpoint before any historical network request.
        String exactOrigin;
        try {
            exactOrigin = ComfyUiClient.checkedOrigin(recorded.get().origin()).toString();
        } catch (IllegalArgumentException invalidStoredOrigin) {
            return Optional.empty();
        }
        if (version == provider.configVersion()) {
            return exactOrigin.equals(activeOrigin)
                    && originSha256.equals(active.originSha256())
                    ? Optional.of(active) : Optional.empty();
        }
        ComfyUiClient client = historical.computeIfAbsent(version, ignored ->
                new ComfyUiClient(new ComfyUiProperties(exactOrigin), mapper));
        return originSha256.equals(client.originSha256())
                ? Optional.of(client) : Optional.empty();
    }

    private record RecordedOrigin(String origin, String sha256) {}
}
