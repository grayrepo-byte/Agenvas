package dev.agenvas.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.agenvas.audit.infrastructure.CallLogWriterConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncCallLogWriterTest {
    private static final long TIMEOUT_SECONDS = 5;
    private final CallLogRepository repository = mock(CallLogRepository.class);

    @Test void blockedDatabaseAndFullQueueNeverBlockCallerOrRunJdbcOnCaller() throws Exception {
        var executor = new CallLogWriterConfiguration().llmStreamLogExecutor(new CallLogWriterProperties(1, 1, 1));
        executor.initialize();
        var writer = new AsyncCallLogWriter(repository, executor);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> writeThread = new AtomicReference<>();
        doAnswer(call -> {
            writeThread.set(Thread.currentThread().getName()); entered.countDown();
            assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue(); return null;
        }).when(repository).finishStream(any(), any(), any(), anyLong(), any(), anyList(), anyBoolean());
        UUID first = UUID.randomUUID(), queued = UUID.randomUUID(), rejected = UUID.randomUUID();
        try {
            submit(writer, first);
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            submit(writer, queued);
            submit(writer, rejected); // Must return even though the JDBC worker and queue are occupied.
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            assertThat(writeThread.get()).startsWith("llm-stream-log-").isNotEqualTo(Thread.currentThread().getName());
            verify(repository, never()).finishStream(eq(rejected), any(), any(), anyLong(), any(), anyList(), anyBoolean());
        } finally { release.countDown(); executor.shutdown(); }
        verify(repository, times(2)).finishStream(any(), any(), any(), anyLong(), any(), anyList(), anyBoolean());
    }

    @Test void failedWriteIsContainedAndSubsequentSnapshotStillWritesWithoutRetry() {
        var writer = new AsyncCallLogWriter(repository, Runnable::run);
        UUID failure = UUID.randomUUID(), next = UUID.randomUUID();
        doThrow(new IllegalStateException("synthetic database failure")).when(repository)
                .finishStream(eq(failure), any(), any(), anyLong(), any(), anyList(), anyBoolean());
        submit(writer, failure); submit(writer, next);
        verify(repository, times(1)).finishStream(eq(failure), any(), any(), anyLong(), any(), anyList(), anyBoolean());
        verify(repository, times(1)).finishStream(eq(next), any(), any(), anyLong(), any(), anyList(), anyBoolean());
    }

    @Test void closedExecutorRejectsSnapshotWithoutPropagatingIntoModelResult() {
        var executor = new CallLogWriterConfiguration().llmStreamLogExecutor(new CallLogWriterProperties(1, 1, 1));
        executor.initialize(); executor.shutdown();
        submit(new AsyncCallLogWriter(repository, executor), UUID.randomUUID());
        verifyNoInteractions(repository);
    }

    private void submit(AsyncCallLogWriter writer, UUID id) {
        writer.submit(id, CallLogService.CallOutcome.succeeded(null), Instant.EPOCH, 5, null, List.of(), false);
    }
}
