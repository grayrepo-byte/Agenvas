package dev.agenvas.asset.application;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local archive root; deployment must mount it on a persistent private volume. */
@ConfigurationProperties(prefix = "agenvas.storage")
public record AssetProperties(Path root) {

    /** Uses a writable development directory when no volume is configured. */
    public AssetProperties {
        root = root == null ? Path.of("data", "assets") : root;
    }
}
