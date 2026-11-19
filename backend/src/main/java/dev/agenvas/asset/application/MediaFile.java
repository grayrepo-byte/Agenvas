package dev.agenvas.asset.application;

import java.nio.file.Path;

/** Internal file metadata for streaming or reading after the application service authorizes access. */
public record MediaFile(Path path, String contentType, long size) {
}
