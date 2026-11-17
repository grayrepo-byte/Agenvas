package dev.agenvas.subtitle.runtime;

import static dev.agenvas.db.Tables.LOCAL_ASR_INSTALLATION;
import dev.agenvas.db.tables.records.LocalAsrInstallationRecord;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.subtitle.application.LocalAsrProperties;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Installation state is durable. Network I/O never holds a database transaction or an HTTP thread. */
@Service
public class ManagedAsrService {
    public enum State {
        MISSING, QUEUED, DOWNLOADING, VERIFYING, READY, FAILED, CANCELED
    }
    public enum Readiness {
        DISABLED, ENGINE_UNAVAILABLE, MODEL_MISSING, RESOURCE_LIMITED, AVAILABLE
    }
    public record Snapshot(boolean enabled, long version, State state, UUID operationId, long downloadedBytes,
            long totalBytes, String errorCode, Readiness readiness, String modelId, String engineVersion,
            String source, String sha256, String license, AsrMemoryBudget.Status memory, String mode) {
    }
    private final DSLContext db;
    private final TransactionTemplate transactions;
    private final ManagedAsrFiles files;
    private final AsrModelSource source;
    private final LocalAsrProperties engine;
    private final AsrMemoryBudget memory;
    private final Clock clock;
    private final boolean recovery;
    private final ManagedAsrProperties properties;
    public ManagedAsrService(DSLContext db, TransactionTemplate transactions, ManagedAsrFiles files,
            AsrModelSource source,
            LocalAsrProperties engine, AsrMemoryBudget memory, Clock clock,
            @Value("${agenvas.recovery-mode:false}") boolean recovery,
            ManagedAsrProperties properties) {
        this.db = db;
        this.transactions = transactions;
        this.files = files;
        this.source = source;
        this.engine = engine;
        this.memory = memory;
        this.clock = clock;
        this.recovery = recovery;
        this.properties = properties;
    }
    public Snapshot get() {
        return snapshot(row());
    }
    public Snapshot update(boolean enabled, long expected) {
        writable();
        return transactions.execute(status -> {
            var row = locked();
            requireVersion(row, expected);
            var update = db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.ENABLED, enabled)
                    .set(LOCAL_ASR_INSTALLATION.VERSION, row.getVersion() + 1)
                    .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now());
            if (!enabled && active(row))
                update.set(LOCAL_ASR_INSTALLATION.STATE, State.CANCELED.name())
                        .setNull(LOCAL_ASR_INSTALLATION.LEASE_UNTIL);
            update.where(LOCAL_ASR_INSTALLATION.ID.eq(1)).execute();
            return get();
        });
    }
    public Snapshot download(long expected) {
        writable();
        return transactions.execute(status -> {
            var row = locked();
            requireVersion(row, expected);
            if (!row.getEnabled() || active(row))
                throw problem(HttpStatus.CONFLICT, "LOCAL_ASR_STATE_CONFLICT");
            if (!isManaged() || !managedModel())
                throw problem(HttpStatus.CONFLICT, "LOCAL_ASR_MANUAL_MODEL");
            db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.STATE, State.QUEUED.name())
                    .set(LOCAL_ASR_INSTALLATION.OPERATION_ID, UUID.randomUUID())
                    .set(LOCAL_ASR_INSTALLATION.DOWNLOADED_BYTES, 0L)
                    .setNull(LOCAL_ASR_INSTALLATION.ERROR_CODE).setNull(LOCAL_ASR_INSTALLATION.LEASE_OWNER)
                    .setNull(LOCAL_ASR_INSTALLATION.LEASE_UNTIL)
                    .set(LOCAL_ASR_INSTALLATION.VERSION, row.getVersion() + 1)
                    .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now())
                    .where(LOCAL_ASR_INSTALLATION.ID.eq(1)).execute();
            return get();
        });
    }
    public Snapshot cancel(UUID operation) {
        writable();
        return transactions.execute(status -> {
            var row = locked();
            if (!Objects.equals(operation, row.getOperationId()))
                throw problem(HttpStatus.CONFLICT, "LOCAL_ASR_STATE_CONFLICT");
            if (active(row))
                db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.STATE, State.CANCELED.name())
                        .set(LOCAL_ASR_INSTALLATION.VERSION, row.getVersion() + 1)
                        .setNull(LOCAL_ASR_INSTALLATION.LEASE_UNTIL)
                        .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now()).where(LOCAL_ASR_INSTALLATION.ID.eq(1)).execute();
            return get();
        });
    }
    /** Explicit local checksum check; no model download or native inference is initiated. */
    public Snapshot check(long expected) {
        writable();
        var before = row();
        requireVersion(before, expected);
        if (active(before))
            throw problem(HttpStatus.CONFLICT, "LOCAL_ASR_STATE_CONFLICT");
        boolean verified = isManaged()
                ? managedModel() && files.verified(ManagedAsrCatalog.MODEL)
                : manualModelPresent();
        var changed = db.update(LOCAL_ASR_INSTALLATION)
                .set(LOCAL_ASR_INSTALLATION.STATE, verified ? State.READY.name() : State.MISSING.name())
                .setNull(LOCAL_ASR_INSTALLATION.ERROR_CODE).set(LOCAL_ASR_INSTALLATION.VERSION, expected + 1)
                .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now()).where(LOCAL_ASR_INSTALLATION.ID.eq(1))
                .and(LOCAL_ASR_INSTALLATION.VERSION.eq(expected)).execute();
        if (changed != 1)
            throw problem(HttpStatus.CONFLICT, "VERSION_CONFLICT");
        return get();
    }
    public void requireReady() {
        var snapshot = get();
        if (snapshot.readiness() != Readiness.AVAILABLE)
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_ASR_" + snapshot.readiness().name());
        if (isManaged() && !files.verified(ManagedAsrCatalog.MODEL))
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_ASR_MODEL_MISSING");
    }
    public boolean shouldStopRecognition() {
        if (!row().getEnabled())
            return true;
        if (!isManaged())
            return false;
        var budget = memory.status();
        return budget.limitBytes() == null || budget.usedBytes() == null
                || budget.usedBytes() > budget.limitBytes() - 64L * 1024 * 1024;
    }
    /** Expiry fails closed: no automatic HTTP retry or partial-file resume after a process death. */
    public void recoverExpired() {
        int changed = db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.STATE, State.FAILED.name())
                .set(LOCAL_ASR_INSTALLATION.ERROR_CODE, "INTERRUPTED")
                .set(LOCAL_ASR_INSTALLATION.VERSION, LOCAL_ASR_INSTALLATION.VERSION.plus(1))
                .setNull(LOCAL_ASR_INSTALLATION.LEASE_UNTIL).set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now())
                .where(LOCAL_ASR_INSTALLATION.ID.eq(1))
                .and(LOCAL_ASR_INSTALLATION.STATE.in(State.DOWNLOADING.name(), State.VERIFYING.name()))
                .and(LOCAL_ASR_INSTALLATION.LEASE_UNTIL.lt(now())).execute();
        if (changed == 1)
            files.cleanInterrupted();
    }
    public void runNext(String worker) {
        if (recovery || !isManaged())
            return;
        recoverExpired();
        var job = db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.STATE, State.DOWNLOADING.name())
                .set(LOCAL_ASR_INSTALLATION.LEASE_OWNER, worker)
                .set(LOCAL_ASR_INSTALLATION.LEASE_UNTIL, now().plusMinutes(2))
                .where(LOCAL_ASR_INSTALLATION.ID.eq(1)).and(LOCAL_ASR_INSTALLATION.ENABLED.isTrue())
                .and(LOCAL_ASR_INSTALLATION.STATE.eq(State.QUEUED.name())).returning().fetchOne();
        if (job == null)
            return;
        UUID operation = job.getOperationId();
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        try {
            files.download(ManagedAsrCatalog.MODEL, source, () -> {
                if (Thread.currentThread().isInterrupted() || !owns(operation, worker))
                    throw new CancellationException();
                if (System.nanoTime() > deadline)
                    throw new AsrDownloadException(AsrDownloadException.Code.DOWNLOAD_TIMEOUT);
            }, bytes -> {
                if (progress(operation, worker, bytes) != 1)
                    throw new CancellationException();
            }, (stage, target) -> transactions.execute(status -> {
                var current = locked();
                if (!current.getEnabled() || !Objects.equals(current.getOperationId(), operation)
                        || !Objects.equals(current.getLeaseOwner(), worker)
                        || !active(current) || current.getLeaseUntil() == null
                        || current.getLeaseUntil().isBefore(now()))
                    throw new CancellationException();
                try {
                    files.publish(stage, target);
                } catch (java.io.IOException failed) {
                    throw new AsrDownloadException(AsrDownloadException.Code.VOLUME_UNAVAILABLE);
                }
                db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.STATE, State.READY.name())
                        .set(LOCAL_ASR_INSTALLATION.DOWNLOADED_BYTES, ManagedAsrCatalog.MODEL.bytes())
                        .setNull(LOCAL_ASR_INSTALLATION.ERROR_CODE)
                        .setNull(LOCAL_ASR_INSTALLATION.LEASE_UNTIL)
                        .set(LOCAL_ASR_INSTALLATION.VERSION, current.getVersion() + 1)
                        .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now()).where(LOCAL_ASR_INSTALLATION.ID.eq(1)).execute();
                return null;
            }));
        } catch (CancellationException canceled) {
            finishFailure(operation, worker, Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "CANCELED");
        } catch (AsrDownloadException failed) {
            finishFailure(operation, worker, failed.code().name());
        }
    }
    private int progress(UUID operation, String worker, long bytes) {
        return db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.DOWNLOADED_BYTES, bytes)
                .set(LOCAL_ASR_INSTALLATION.STATE,
                        bytes == ManagedAsrCatalog.MODEL.bytes() ? State.VERIFYING.name() : State.DOWNLOADING.name())
                .set(LOCAL_ASR_INSTALLATION.LEASE_UNTIL, now().plusMinutes(2))
                .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now())
                .where(fence(operation, worker)).execute();
    }
    private void finishFailure(UUID operation, String worker, String code) {
        db.update(LOCAL_ASR_INSTALLATION).set(LOCAL_ASR_INSTALLATION.STATE, State.FAILED.name())
                .set(LOCAL_ASR_INSTALLATION.ERROR_CODE, code)
                .setNull(LOCAL_ASR_INSTALLATION.LEASE_UNTIL)
                .set(LOCAL_ASR_INSTALLATION.VERSION, LOCAL_ASR_INSTALLATION.VERSION.plus(1))
                .set(LOCAL_ASR_INSTALLATION.UPDATED_AT, now()).where(fence(operation, worker)).execute();
    }
    private org.jooq.Condition fence(UUID operation, String worker) {
        return LOCAL_ASR_INSTALLATION.ID.eq(1).and(LOCAL_ASR_INSTALLATION.ENABLED.isTrue())
                .and(LOCAL_ASR_INSTALLATION.OPERATION_ID.eq(operation))
                .and(LOCAL_ASR_INSTALLATION.LEASE_OWNER.eq(worker))
                .and(LOCAL_ASR_INSTALLATION.STATE.in(State.DOWNLOADING.name(), State.VERIFYING.name()))
                .and(LOCAL_ASR_INSTALLATION.LEASE_UNTIL.ge(now()));
    }
    private boolean owns(UUID operation, String worker) {
        return db.fetchExists(db.selectOne().from(LOCAL_ASR_INSTALLATION).where(fence(operation, worker)));
    }
    private Snapshot snapshot(LocalAsrInstallationRecord row) {
        var budget = memory.status();
        boolean managed = isManaged();
        Readiness ready = !row.getEnabled()
                ? Readiness.DISABLED
                : !engineAvailable()
                        ? Readiness.ENGINE_UNAVAILABLE
                        : (managed
                                ? !managedModel() || !State.READY.name().equals(row.getState())
                                : !manualModelPresent())
                                        ? Readiness.MODEL_MISSING
                                        : managed && !budget.admitted()
                                                ? Readiness.RESOURCE_LIMITED
                                                : Readiness.AVAILABLE;
        return new Snapshot(row.getEnabled(), row.getVersion(),
                managed ? State.valueOf(row.getState()) : manualModelPresent() ? State.READY : State.MISSING,
                managed ? row.getOperationId() : null, managed ? row.getDownloadedBytes() : 0L,
                managed ? ManagedAsrCatalog.MODEL.bytes() : 0L, row.getErrorCode(), ready,
                managed ? ManagedAsrCatalog.MODEL.id() : "manual",
                managed ? ManagedAsrCatalog.ENGINE_VERSION : "operator-provided",
                "https://huggingface.co/ggerganov/whisper.cpp", managed ? ManagedAsrCatalog.MODEL.sha256() : null,
                managed ? "MIT" : "OPERATOR_PROVIDED", budget, managed ? "MANAGED" : "MANUAL");
    }
    private boolean isManaged() {
        return properties.mode() == ManagedAsrProperties.Mode.MANAGED
                || properties.mode() == ManagedAsrProperties.Mode.AUTO && managedModel();
    }
    private boolean manualModelPresent() {
        try {
            return engine.model() != null && Files.isRegularFile(engine.model()) && Files.isReadable(engine.model())
                    && Files.size(engine.model()) > 0;
        } catch (java.io.IOException unavailable) {
            return false;
        }
    }
    private boolean managedModel() {
        return engine.model() != null && engine.model().normalize().equals(files.modelPath());
    }
    private boolean engineAvailable() {
        if (engine.executable() == null || !Files.isRegularFile(engine.executable())
                || !Files.isExecutable(engine.executable()))
            return false;
        if (!isManaged())
            return true;
        if (!System.getProperty("os.name").equals("Linux"))
            return false;
        try (var input = Files.newInputStream(engine.executable())) {
            byte[] header = input.readNBytes(20);
            int machine = header.length == 20
                    ? Byte.toUnsignedInt(header[18]) | Byte.toUnsignedInt(header[19]) << 8
                    : -1;
            String arch = System.getProperty("os.arch");
            return header.length == 20 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                    && header[4] == 2 && header[5] == 1
                    && ((arch.equals("amd64") || arch.equals("x86_64")) && machine == 62
                            || (arch.equals("aarch64") || arch.equals("arm64")) && machine == 183);
        } catch (java.io.IOException failure) {
            return false;
        }
    }
    private LocalAsrInstallationRecord row() {
        return db.selectFrom(LOCAL_ASR_INSTALLATION).where(LOCAL_ASR_INSTALLATION.ID.eq(1)).fetchSingle();
    }
    private LocalAsrInstallationRecord locked() {
        return db.selectFrom(LOCAL_ASR_INSTALLATION).where(LOCAL_ASR_INSTALLATION.ID.eq(1)).forUpdate().fetchSingle();
    }
    private static boolean active(LocalAsrInstallationRecord row) {
        return Set.of("QUEUED", "DOWNLOADING", "VERIFYING").contains(row.getState());
    }
    private static void requireVersion(LocalAsrInstallationRecord row, long version) {
        if (row.getVersion() != version)
            throw problem(HttpStatus.CONFLICT, "VERSION_CONFLICT");
    }
    private void writable() {
        if (recovery)
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_MODE_READ_ONLY");
    }
    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
    private static ApiProblemException problem(HttpStatus status, String code) {
        return new ApiProblemException(status, code, ApiMessage.of("api.subtitle.runtime"),
                ApiMessage.of("api.subtitle.runtime"), false);
    }
}
