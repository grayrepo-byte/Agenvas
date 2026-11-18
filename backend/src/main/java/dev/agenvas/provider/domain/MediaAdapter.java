package dev.agenvas.provider.domain;

/** A compiled provider protocol. Implementations may not write business tables. */
public interface MediaAdapter {
    String adapterId();
    /** Compiled extensions declare their contract here; built-ins retain their historical catalog values. */
    default MediaAdapterRegistry.Declaration declaration() {
        return BuiltinMediaAdapters.require(adapterId());
    }
    /** Defaults for newly published capabilities only; null preserves protocol-owned historical fallbacks. */
    default dev.agenvas.shared.http.HttpTimeouts publicationHttpTimeouts() {
        return MediaHttpTimeouts.defaults().get(adapterId());
    }
    /** Return a public, stable code (1–120 uppercase ASCII letters, digits or underscores, starting with a letter)
     * or null before submission. Never return request data, provider messages or credentials. */
    default String preflightFailure(AttemptContext context) {
        return null;
    }
    /** Classify failures from querying or archiving an already accepted request; never resubmit from this path. */
    default PollFailure classifyPollFailure(RuntimeException failure) {
        return new PollFailure(failure instanceof NativeMediaFailure nativeFailure ? nativeFailure.code() : null,
                null, false);
    }
    /** Null permanentCode requests a technical retry; refreshLocations permits one original-request query per pass. */
    record PollFailure(String permanentCode, ProviderFailure diagnostic, boolean refreshLocations) {
        public PollFailure {
            if (permanentCode != null && !permanentCode.matches("[A-Z][A-Z0-9_]{0,119}"))
                throw new IllegalArgumentException("Invalid public provider failure code");
        }
    }
    Submission submit(AttemptContext context);
    Submission reconcile(AttemptContext context);
    default MediaPayload downloadResult(AttemptContext context, ProviderResultManifest.Result result) {
        throw new UnsupportedOperationException("Adapter has no result download protocol");
    }
    /** One archive pass may share temporary downloads; closing it releases all private scratch files. */
    default ResultDownloads openResultDownloads(AttemptContext context) {
        return result -> downloadResult(context, result);
    }
    /** Refresh only locations of already frozen results by querying the original accepted request. */
    default ProviderResultManifest refreshResults(AttemptContext context, ProviderResultManifest frozen) {
        throw new UnsupportedOperationException("Adapter has no result refresh protocol");
    }
    interface ResultDownloads extends AutoCloseable {
        MediaPayload download(ProviderResultManifest.Result result);
        @Override
        default void close() {
        }
    }
}
