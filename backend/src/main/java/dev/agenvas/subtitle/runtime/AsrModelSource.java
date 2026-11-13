package dev.agenvas.subtitle.runtime;

import java.io.InputStream;
import java.io.IOException;

/** Streaming transport seam; production uses only the compiled official model URL. */
@FunctionalInterface
public interface AsrModelSource {
    InputStream open(ManagedAsrCatalog.Model model) throws IOException;
    default InputStream open(ManagedAsrCatalog.Model model, Runnable check) throws IOException {
        check.run();
        return open(model);
    }
}
