package dev.agenvas.provider.infrastructure;

import static dev.agenvas.db.Tables.COMFYUI_CONFIG_VERSION;
import static dev.agenvas.db.Tables.MEDIA_LEGACY_IMPORT_MARKER;

import dev.agenvas.plan.application.PlanProviderProperties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 保存历史端点身份供原请求只读恢复；新任务始终使用当前活动客户端。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiClientRegistry implements ApplicationRunner {

    /** media_legacy_import_marker 是单行标记表，固定主键为 1。 */
    private static final short IMPORT_MARKER_ID = 1;

    /** 读取并登记 Provider 配置版本对应的规范化来源。 */
    private final DSLContext dsl;
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
    public ComfyUiClientRegistry(DSLContext dsl, ObjectMapper mapper,
            PlanProviderProperties provider, ComfyUiProperties properties, ComfyUiClient active,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) {
        this.dsl = dsl;
        this.mapper = mapper;
        this.provider = provider;
        this.active = active;
        this.activeOrigin = ComfyUiClient.checkedOrigin(properties.endpoint()).toString();
        this.recoveryMode = recoveryMode;
    }

    /** 应用启动时登记配置版本；同一版本不能被复用于不同端点来源。 */
    @Override
    public void run(ApplicationArguments arguments) {
        if (!recoveryMode) {
            // completed_at 非空即表示一次性旧数据导入已完成；此时不再登记当前端点。
            boolean imported = dsl.select(MEDIA_LEGACY_IMPORT_MARKER.COMPLETED_AT)
                    .from(MEDIA_LEGACY_IMPORT_MARKER)
                    .where(MEDIA_LEGACY_IMPORT_MARKER.ID.eq(IMPORT_MARKER_ID))
                    .fetchSingle(MEDIA_LEGACY_IMPORT_MARKER.COMPLETED_AT) != null;
            if (!imported) registerActive();
        }
    }

    /** 写入当前版本的端点摘要；同版本重启保留原记录并核对其身份。 */
    public void registerActive() {
        Field<Integer> latestVersion = DSL.coalesce(
                DSL.max(COMFYUI_CONFIG_VERSION.CONFIG_VERSION), 0);
        int latest = dsl.select(latestVersion)
                .from(COMFYUI_CONFIG_VERSION)
                .fetchSingle(latestVersion);
        if (provider.configVersion() < latest) {
            throw new IllegalStateException("ComfyUI config version cannot decrease");
        }
        dsl.insertInto(COMFYUI_CONFIG_VERSION)
                .set(COMFYUI_CONFIG_VERSION.CONFIG_VERSION, provider.configVersion())
                .set(COMFYUI_CONFIG_VERSION.ORIGIN, activeOrigin)
                .set(COMFYUI_CONFIG_VERSION.ORIGIN_SHA256, active.originSha256())
                .onConflict(COMFYUI_CONFIG_VERSION.CONFIG_VERSION)
                .doNothing()
                .execute();
        RecordedOrigin recorded = dsl.select(COMFYUI_CONFIG_VERSION.ORIGIN,
                        COMFYUI_CONFIG_VERSION.ORIGIN_SHA256)
                .from(COMFYUI_CONFIG_VERSION)
                .where(COMFYUI_CONFIG_VERSION.CONFIG_VERSION.eq(provider.configVersion()))
                .fetchSingle(row -> new RecordedOrigin(row.value1(), row.value2()));
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
        var recorded = dsl.select(COMFYUI_CONFIG_VERSION.ORIGIN,
                        COMFYUI_CONFIG_VERSION.ORIGIN_SHA256)
                .from(COMFYUI_CONFIG_VERSION)
                .where(COMFYUI_CONFIG_VERSION.CONFIG_VERSION.eq(version))
                .fetchOptional(row -> new RecordedOrigin(row.value1(), row.value2()));
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
