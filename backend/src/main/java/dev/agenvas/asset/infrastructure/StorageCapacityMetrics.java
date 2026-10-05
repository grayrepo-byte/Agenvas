package dev.agenvas.asset.infrastructure;

import dev.agenvas.asset.application.AssetProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 采样私有媒体卷所在文件系统的容量，不读取或扫描任何媒体内容。 */
@Component
public class StorageCapacityMetrics {

    /** 记录容量采集故障及恢复，不附带资产或用户信息。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageCapacityMetrics.class);
    /** 采样失败或容量数据无效时对外暴露的哨兵值。 */
    private static final Space UNAVAILABLE = new Space(-1, -1);

    /** 隔离文件系统访问；定时器只发布最近一次完整采样。 */
    private final SpaceReader reader;
    /** 原子持有总容量和可用容量，避免指标读取到不同时间的半组数据。 */
    private final AtomicReference<Space> current = new AtomicReference<>(UNAVAILABLE);
    /** 控制不可用日志只在健康状态转变时输出。 */
    private final AtomicBoolean unavailable = new AtomicBoolean();

    /** 从资产根目录推导文件系统读取器，并注册磁盘容量指标。 */
    @Autowired
    public StorageCapacityMetrics(AssetProperties properties, MeterRegistry meters) {
        this(() -> readFileStore(properties.root().toAbsolutePath().normalize()), meters);
    }

    /** 注入容量读取器，允许独立验证文件系统故障和恢复状态。 */
    StorageCapacityMetrics(SpaceReader reader, MeterRegistry meters) {
        this.reader = reader;
        Gauge.builder("agenvas.storage.disk.total.bytes", current,
                        value -> value.get().totalBytes())
                .description("Asset-volume filesystem total bytes; -1 when unavailable")
                .register(meters);
        Gauge.builder("agenvas.storage.disk.usable.bytes", current,
                        value -> value.get().usableBytes())
                .description("Asset-volume filesystem usable bytes; -1 when unavailable")
                .register(meters);
        Gauge.builder("agenvas.storage.disk.used.ratio", current, value -> {
                    Space space = value.get();
                    return space.totalBytes() < 1 ? -1.0
                            : 1.0 - (double) space.usableBytes() / space.totalBytes();
                })
                .description("Asset-volume filesystem used ratio; -1 when unavailable")
                .register(meters);
    }

    /** 刷新同一文件系统采样的总量与可用量；失败后立即清除旧健康值。 */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            Space space = reader.read();
            if (space.totalBytes() < 1 || space.usableBytes() < 0
                    || space.usableBytes() > space.totalBytes()) {
                throw new IOException("Invalid storage capacity snapshot");
            }
            current.set(space);
            if (unavailable.getAndSet(false)) {
                LOGGER.info("Asset-volume capacity snapshot recovered");
            }
        } catch (IOException | SecurityException failure) {
            current.set(UNAVAILABLE);
            if (unavailable.compareAndSet(false, true)) {
                LOGGER.warn("Asset-volume capacity snapshot unavailable", failure);
            }
        }
    }

    /** 归档根目录首次写入前可能不存在，因此沿父目录查找最近的真实可写目录。 */
    private static Space readFileStore(Path root) throws IOException {
        Path candidate = root;
        while (candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.getParent();
        }
        if (candidate == null || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(candidate)) {
            throw new IOException("Asset-volume directory unavailable");
        }
        var store = Files.getFileStore(candidate);
        return new Space(store.getTotalSpace(), store.getUsableSpace());
    }

    /** 同一次文件系统采样得到的容量值。
     * @param totalBytes 文件系统总字节数
     * @param usableBytes 当前进程可用字节数
     */
    record Space(long totalBytes, long usableBytes) {}

    /** 抽象单次容量读取，供生产文件系统实现和隔离测试使用。 */
    @FunctionalInterface
    interface SpaceReader {
        /** 读取总容量与当前可用容量；访问失败时抛出 IOException。 */
        Space read() throws IOException;
    }
}
