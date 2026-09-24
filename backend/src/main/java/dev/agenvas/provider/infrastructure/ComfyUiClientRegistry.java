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

/** 保存历史端点身份供原请求只读恢复；新任务始终使用当前活动客户端。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiClientRegistry implements ApplicationRunner {

    /** 读取并登记 Provider 配置版本对应的规范化来源。 */
    private final JdbcClient jdbc;
    /** 为历史端点创建仅供查询的客户端实例。 */
    private final ObjectMapper mapper;
    /** 当前 ComfyUI 配置版本，用于拒绝版本回退或身份复用。 */
    private final PlanProviderProperties provider;
    /** 当前活动端点客户端，只供新请求使用。 */
    private final ComfyUiClient active;
    /** 当前端点的规范化 origin，用于和持久记录精确比较。 */
    private final String activeOrigin;
    /** 灾备恢复期间禁止进行任何历史网络调用。 */
    private final boolean recoveryMode;
    /** 按配置版本缓存已核验的历史客户端，不缓存未经核验的来源。 */
    private final Map<Integer, ComfyUiClient> historical = new ConcurrentHashMap<>();

    /** 记录当前端点身份，并根据恢复模式限制历史客户端查询。 */
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

    /** 应用启动时登记配置版本；同一版本不能被复用于不同端点来源。 */
    @Override
    public void run(ApplicationArguments arguments) {
        if (!recoveryMode) registerActive();
    }

    /** 写入当前版本的端点摘要；同版本重启保留原记录并核对其身份。 */
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

    /** 只解析与原尝试记录的版本和摘要完全匹配的历史客户端。 */
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
        // 在发起任何历史网络请求前，重新校验数据库中保存的端点。
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

    /** 数据库中保存的端点身份。
     * @param origin 规范化后的 HTTP 来源，不包含任务输入中的路径
     * @param sha256 该来源的固定摘要
     */
    private record RecordedOrigin(String origin, String sha256) {}
}
