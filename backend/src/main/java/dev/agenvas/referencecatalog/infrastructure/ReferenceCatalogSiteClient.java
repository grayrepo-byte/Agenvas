package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.shared.http.PublicDownloadException;
import dev.agenvas.shared.http.PublicDownloadHttpClients;
import java.io.IOException;
import java.time.Duration;
import okhttp3.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Public JSON and image reads use the same bounded, credential-free transport as GitHub. */
@Component
public class ReferenceCatalogSiteClient {
    public static final String ORIGIN = "https://omilic.com/";
    private final OkHttpClient http;
    public ReferenceCatalogSiteClient(PublicDownloadHttpClients clients) {
        http = clients.create(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));
    }
    public byte[] catalog(String file) {
        return read(ORIGIN + path(file), ReferenceCatalogParser.MAX_METADATA_BYTES);
    }
    public byte[] image(String url) {
        return read(url, MAX_IMAGE_BYTES);
    }
    private byte[] read(String url, int limit) {
        try (var response = http.newCall(
                new Request.Builder().url(url).header("User-Agent", "Agenvas-Reference-Catalog/1").get().build())
                .execute()) {
            if (response.code() == 429
                    || response.code() == 403 && "0".equals(response.header("X-RateLimit-Remaining")))
                throw problem(HttpStatus.BAD_GATEWAY, "REFERENCE_CATALOG_REMOTE_RATE_LIMITED",
                        "api.reference-catalog.remoteUnavailable");
            if (!response.isSuccessful() || response.body() == null)
                throw problem(HttpStatus.BAD_GATEWAY, "REFERENCE_CATALOG_REMOTE_UNAVAILABLE",
                        "api.reference-catalog.remoteUnavailable");
            if (response.body().contentLength() > limit)
                throw invalid();
            try (var input = response.body().byteStream()) {
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit)
                    throw invalid();
                return bytes;
            }
        } catch (IOException failure) {
            throw problem(HttpStatus.BAD_GATEWAY, "REFERENCE_CATALOG_" + PublicDownloadException.reason(failure).name(),
                    "api.reference-catalog.remoteUnavailable");
        }
    }
}
