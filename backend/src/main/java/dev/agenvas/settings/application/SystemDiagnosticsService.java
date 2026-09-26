package dev.agenvas.settings.application;

import static dev.agenvas.db.Tables.TASK;

import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.llm.application.LlmModeProperties;
import dev.agenvas.provider.application.ProviderModeProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoProperties;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 汇总只读安装状态；只报告配置情况与任务计数，不探测付费 Provider 或泄露配置细节。 */
@Service
public class SystemDiagnosticsService {

    /** 查询近期持久化任务状态，并以数据库访问结果判断数据库可用性。 */
    private final DSLContext dsl;
    /** 规范化后的媒体归档根目录，用于检查本地存储权限。 */
    private final Path storageRoot;
    /** 决定 LLM 状态按 Mock 还是已配置 Provider 展示。 */
    private final LlmModeProperties llmMode;
    /** 读取活动 LLM 配置的安全状态，不读取密钥明文。 */
    private final LlmProviderConfigService llmConfigs;
    /** 决定媒体状态采用 Mock 还是 ComfyUI 配置。 */
    private final ProviderModeProperties mediaMode;
    /** ComfyUI 服务地址配置，仅检查是否填写，不向响应暴露。 */
    private final ComfyUiProperties comfy;
    /** ComfyUI 图片模板所需配置。 */
    private final ComfyUiImageProperties image;
    /** ComfyUI 视频模板所需配置及启用状态。 */
    private final ComfyUiVideoProperties video;
    /** 生成诊断快照的检查时间。 */
    private final Clock clock;

    /** 固定本地存储根目录并注入各 Provider 的只读配置状态。 */
    public SystemDiagnosticsService(DSLContext dsl, AssetProperties storage,
            LlmModeProperties llmMode, LlmProviderConfigService llmConfigs,
            ProviderModeProperties mediaMode, ComfyUiProperties comfy,
            ComfyUiImageProperties image, ComfyUiVideoProperties video, Clock clock) {
        this.dsl = dsl;
        this.storageRoot = storage.root().toAbsolutePath().normalize();
        this.llmMode = llmMode;
        this.llmConfigs = llmConfigs;
        this.mediaMode = mediaMode;
        this.comfy = comfy;
        this.image = image;
        this.video = video;
        this.clock = clock;
    }

    /** 只返回受限状态值，不暴露端点、路径、凭证或自由文本错误。 */
    public Snapshot snapshot() {
        boolean databaseAvailable;
        List<RecentError> recentErrors;
        try {
            // 七日窗口写作 PG 的 now() - interval '7 days'：DSL 没有可移植的 interval 字面量表达，
            // 因此只把该时间表达式作为普通 SQL 字段嵌入，其余聚合仍由 jOOQ 构造。
            Field<OffsetDateTime> sevenDaysAgo = DSL.field(
                    "now() - interval '7 days'", OffsetDateTime.class);
            recentErrors = dsl
                    .select(TASK.STATUS, DSL.count(), DSL.max(TASK.UPDATED_AT))
                    .from(TASK)
                    .where(TASK.STATUS.in(
                            Task.Status.FAILED.name(),
                            Task.Status.UNKNOWN.name(),
                            Task.Status.BLOCKED.name()))
                    .and(TASK.UPDATED_AT.ge(sevenDaysAgo))
                    .groupBy(TASK.STATUS)
                    .orderBy(DSL.max(TASK.UPDATED_AT).desc())
                    .fetch(row -> new RecentError(row.value1(),
                            row.value2().longValue(),
                            row.value3().toInstant()));
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

    /** 归档目录尚不存在时，仅当最近的现存父目录可读写才视为可用。 */
    static boolean storageAvailable(Path root) {
        Path candidate = root;
        while (candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.getParent();
        }
        return candidate != null && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                && Files.isReadable(candidate) && Files.isWritable(candidate);
    }

    /** 将非空白字符串视为已配置；不在此处校验远端连通性。 */
    private boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    /** 对外呈现的可用性枚举，不携带底层异常或路径信息。 */
    public enum Status {
        /** 本地检查确认资源可访问或配置已就绪。 */
        AVAILABLE,
        /** 本地检查失败或配置未达到运行条件。 */
        UNAVAILABLE
    }

    /** 七日内失败、未知或阻塞任务的按状态聚合结果。
     * @param status 聚合所用的白名单任务状态
     * @param count 该状态对应的任务数量
     * @param lastAt 该状态任务最近一次更新时间
     */
    public record RecentError(String status, long count, Instant lastAt) {}

    /** 返回给管理员的安装诊断快照；配置字段不代表远端连通性。
     * @param checkedAt 生成快照的时刻
     * @param database 数据库读状态
     * @param storage 归档根目录或最近存在父目录的访问状态
     * @param llmMode 当前 LLM 运行模式
     * @param llmConfigured 是否具备运行所需的 LLM 配置
     * @param llmToolCallingVerified 工具调用协议是否通过诊断验证
     * @param mediaMode 当前媒体 Provider 模式
     * @param imageConfigured 图片模板所需配置是否齐备
     * @param videoConfigured 视频模板所需配置是否齐备
     * @param recentErrors 最近七日内的有限错误计数
     */
    public record Snapshot(Instant checkedAt, Status database, Status storage,
            String llmMode, boolean llmConfigured, boolean llmToolCallingVerified,
            String mediaMode, boolean imageConfigured, boolean videoConfigured,
            List<RecentError> recentErrors) {}
}
