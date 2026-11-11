package dev.agenvas.subtitle.runtime;

import java.nio.file.*;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** Linux cgroup admission is conservative, not a promise about future parallel workloads. */
@Component
public class AsrMemoryBudget {
    public static final long PROCESS_BYTES = 512L * 1024 * 1024;
    public static final long MARGIN_BYTES = 128L * 1024 * 1024;
    public record Status(boolean admitted, Long limitBytes, Long usedBytes) {}
    public Status status() { return read(Path.of("/sys/fs/cgroup")); }
    static Status read(Path root) {
        try {
            Path max = root.resolve("memory.max"), current = root.resolve("memory.current");
            if (!Files.isRegularFile(max)) { max = root.resolve("memory/memory.limit_in_bytes"); current = root.resolve("memory/memory.usage_in_bytes"); }
            long limit = Long.parseLong(Files.readString(max).strip()), used = Long.parseLong(Files.readString(current).strip());
            if (limit <= 0 || limit > 64L * 1024 * 1024 * 1024 || used < 0) return new Status(false, null, null);
            return new Status(used <= limit - PROCESS_BYTES - MARGIN_BYTES, limit, used);
        } catch (IOException | NumberFormatException unknown) { return new Status(false, null, null); }
    }
}
