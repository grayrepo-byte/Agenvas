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
    /** Validate pinned credentials and media before creating a billable submission checkpoint. */
    default String preflightFailure(AttemptContext context) {
        return null;
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
