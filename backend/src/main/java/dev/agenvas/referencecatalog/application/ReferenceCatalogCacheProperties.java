package dev.agenvas.referencecatalog.application;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Rebuildable image cache; independent of immutable project media archives. */
@ConfigurationProperties(prefix = "agenvas.reference-catalog.cache")
public record ReferenceCatalogCacheProperties(Path root, long quotaBytes) {
    public ReferenceCatalogCacheProperties {
        quotaBytes = quotaBytes == 0 ? 512L * 1024 * 1024 : quotaBytes;
        if (quotaBytes < 1 || quotaBytes > 100L * 1024 * 1024 * 1024)
            throw new IllegalArgumentException("Reference cache quota outside bounds");
    }
}
