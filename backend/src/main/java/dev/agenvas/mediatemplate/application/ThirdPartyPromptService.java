package dev.agenvas.mediatemplate.application;

import static dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation.*;

import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;

@Service
public class ThirdPartyPromptService {
    private static final Logger LOG = LoggerFactory.getLogger(ThirdPartyPromptService.class);
    private final ThirdPartyPromptRepository repository;
    private final ThirdPartyPromptHttpClient http;
    private final Map<Format, ThirdPartyPromptAdapter> adapters;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean scheduled;
    private final boolean recovery;
    private final ThirdPartyPromptSyncSettingsService syncSettings;
    // Isolate slow upstream GETs from the shared scheduler used by media task recovery.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().name("prompt-repository-sync").factory());
    private final AtomicBoolean scanning = new AtomicBoolean();
    public ThirdPartyPromptService(ThirdPartyPromptRepository repository, ThirdPartyPromptHttpClient http,
            List<ThirdPartyPromptAdapter> adapters, Clock clock, PlatformTransactionManager transactions,
            ThirdPartyPromptSyncSettingsService syncSettings,
            @Value("${agenvas.media-templates.sync-enabled:true}") boolean scheduled,
            @Value("${agenvas.recovery-mode:false}") boolean recovery) {
        this.repository = repository; this.http = http; this.clock = clock; this.scheduled = scheduled; this.recovery = recovery;
        this.syncSettings = syncSettings;
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ThirdPartyPromptAdapter::format, Function.identity()));
        tx = new TransactionTemplate(transactions);
    }
    public enum SyncState { SUCCESS, FAILED, RUNNING, DISABLED, SUPERSEDED }
    public record SyncResult(String sourceId, SyncState state, int inserted, int updated, int received) {}
    public List<ThirdPartyPromptSource> sources() { return repository.sources(clock.instant()); }
    public ThirdPartyPromptSource source(String id) {
        return repository.source(id, clock.instant()).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "THIRD_PARTY_SOURCE_NOT_FOUND", ApiMessage.of("api.third-party.notFound")));
    }
    public ThirdPartyPromptSource create(String id, String name, TargetKind kind, String url) {
        writable();
        if (id == null || !id.matches("^[a-z][a-z0-9_-]{0,79}$") || name == null || name.isBlank() || name.length() > 160 || kind == null) throw invalid();
        url(url);
        return tx.execute(ignored -> {
            if (!repository.insertSource(new ThirdPartyPromptSource(id, name.trim(), kind, Format.NATIVE_JSON, url, "", true, 0,
                    clock.instant(), null, null, 0, false), clock.instant())) throw conflict();
            return source(id);
        });
    }
    public ThirdPartyPromptSource enabled(String id, boolean enabled, long expected) {
        writable();
        return tx.execute(ignored -> { source(id); if (!repository.enabled(id, enabled, expected, clock.instant())) throw conflict(); return source(id); });
    }
    public ThirdPartyPromptRepository.Page list(TargetKind kind, String source, String query, int offset, int limit) {
        if (kind == null || offset < 0 || limit < 1 || limit > 100 || query == null || query.length() > 160) throw invalid();
        return repository.list(kind, source, query.trim(), offset, limit);
    }
    public ThirdPartyPromptRepository.Entry get(String id) {
        return repository.entry(id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "THIRD_PARTY_PROMPT_NOT_FOUND", ApiMessage.of("api.third-party.notFound")));
    }
    /** The short lease transaction ends before fetching; publication is fenced by the lease token. */
    public SyncResult sync(String id) { return sync(id, false); }
    SyncResult syncScheduled(String id) { return sync(id, true); }
    private SyncResult sync(String id, boolean scheduledRequest) {
        writable(); source(id);
        UUID token = UUID.randomUUID();
        var claimed = tx.execute(ignored -> {
            // Consent and lease acceptance are atomic with respect to the global switch.
            // Manual synchronization is a separate explicit administrator action.
            if (scheduledRequest && !syncSettings.allowScheduledClaim()) return java.util.Optional.<ThirdPartyPromptSource>empty();
            return repository.claim(id, token, clock.instant());
        });
        if (claimed.isEmpty()) return new SyncResult(id, source(id).enabled() ? SyncState.RUNNING : SyncState.DISABLED, 0, 0, 0);
        ThirdPartyPromptSource source = claimed.get();
        try {
            var prompts = validate(source, adapters.get(source.format()).fetch(source, http));
            return tx.execute(ignored -> {
                if (!repository.ownsLease(id, token, clock.instant())) return new SyncResult(id, SyncState.SUPERSEDED, 0, 0, prompts.size());
                var counts = repository.upsert(source, prompts, clock.instant());
                repository.finish(id, token, clock.instant(), null);
                return new SyncResult(id, SyncState.SUCCESS, counts.inserted(), counts.updated(), prompts.size());
            });
        } catch (RuntimeException failure) {
            // Store only a stable code: upstream content, URLs and exception messages are untrusted.
            String code = failureCode(failure);
            LOG.warn("Third-party prompt sync failed for {} ({}, {})", id, code, failure.getClass().getSimpleName());
            tx.executeWithoutResult(ignored -> repository.finish(id, token, clock.instant(), code));
            return new SyncResult(id, SyncState.FAILED, 0, 0, 0);
        }
    }
    private static String failureCode(RuntimeException failure) {
        if (failure instanceof ThirdPartyPromptHttpClient.DownloadFailure download)
            return "THIRD_PARTY_SOURCE_" + download.reason().name();
        if (failure instanceof JacksonException || (failure instanceof ApiProblemException problem
                && "THIRD_PARTY_PROMPT_INVALID".equals(problem.code()))) return "THIRD_PARTY_SOURCE_INVALID_DATA";
        return "THIRD_PARTY_SYNC_FAILED";
    }
    @Scheduled(fixedDelayString = "${agenvas.media-templates.scan-delay-ms:60000}", initialDelayString = "${agenvas.media-templates.scan-delay-ms:60000}")
    public void syncDue() {
        if (!scheduled || recovery || worker.isShutdown() || !syncSettings.get().enabled()
                || !scanning.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    for (String id : repository.due(clock.instant())) {
                        // An administrator may withdraw consent while a previous source downloads.
                        // Do not interrupt its safe publication, but do not start the next source.
                        if (!syncSettings.get().enabled()) break;
                        syncScheduled(id);
                    }
                }
                catch (RuntimeException failure) { LOG.warn("Third-party prompt scan failed ({})", failure.getClass().getSimpleName()); }
                finally { scanning.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) { scanning.set(false); }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
    private void writable() { if (recovery) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_MODE_READ_ONLY", ApiMessage.of("api.third-party.readOnly")); }
    private RuntimeException conflict() { return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", ApiMessage.of("api.third-party.conflict")); }
}
