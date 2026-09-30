package dev.agenvas.settings.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Process-local diagnostic output, deliberately separate from durable business audit records. */
public final class SystemLogBuffer {
    public static final int CAPACITY = 2000;
    public static final int MAX_LINE_BYTES = 8192;
    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 1000;
    public static final int MAX_SEARCH_LENGTH = 200;
    private final Clock clock;
    private final int capacity;
    private final String processId = UUID.randomUUID().toString();
    private final Instant startedAt;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long sequence;

    public SystemLogBuffer(Clock clock) { this(clock, CAPACITY); }

    SystemLogBuffer(Clock clock, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Log capacity must be positive");
        this.clock = clock;
        this.capacity = capacity;
        this.startedAt = clock.instant();
    }

    /** Redact before retaining or searching output; never retain a second raw copy. */
    synchronized void append(Stream stream, String line, boolean truncated) {
        String message = SystemLogRedactor.redact(line);
        entries.addLast(new Entry(++sequence, clock.instant(), stream, message, truncated));
        if (entries.size() > capacity) entries.removeFirst();
    }

    /** Returns the newest matching lines in chronological order, within one consistent snapshot. */
    public synchronized Snapshot snapshot(Stream stream, String search, int limit) {
        if (limit < 1 || limit > MAX_LIMIT || (search != null && search.length() > MAX_SEARCH_LENGTH)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "日志筛选无效", "日志数量或关键词长度超出允许范围。", false);
        }
        String keyword = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        List<Entry> matches = entries.stream()
                .filter(entry -> stream == null || entry.stream() == stream)
                .filter(entry -> entry.message().toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
        return new Snapshot(processId, startedAt, clock.instant(), capacity, entries.size(),
                sequence - entries.size(), matches.size(),
                List.copyOf(matches.subList(Math.max(0, matches.size() - limit), matches.size())));
    }

    public enum Stream { STDOUT, STDERR }
    public record Entry(long sequence, Instant recordedAt, Stream stream, String message,
                        boolean truncated) {}
    public record Snapshot(String processId, Instant startedAt, Instant checkedAt, int capacity,
                           int retainedCount, long droppedCount, int matchedCount, List<Entry> entries) {}
}
