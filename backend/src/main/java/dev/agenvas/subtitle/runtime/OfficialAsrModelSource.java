package dev.agenvas.subtitle.runtime;

import java.io.*;
import java.net.*;
import java.time.Duration;
import dev.agenvas.shared.http.PublicDownloadHttpClients;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Fixed catalogue entry and checksum; credential-free redirects follow the installation's ADR 0079 policy. */
@Component
public class OfficialAsrModelSource implements AsrModelSource {
    private final OkHttpClient client;
    public OfficialAsrModelSource(PublicDownloadHttpClients clients) {
        // Reuse deployment-owned DIRECT/HTTP/HTTPS/SOCKS5 and DoH policy, including Clash.
        // The catalogue fixes identity. Redirect addresses use the same URL-format policy as other downloads.
        client = clients.create(Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofMinutes(30));
    }
    @Override
    public InputStream open(ManagedAsrCatalog.Model model) throws IOException {
        return open(model, () -> {
        });
    }
    @Override
    public InputStream open(ManagedAsrCatalog.Model model, Runnable check) throws IOException {
        if (!model.equals(ManagedAsrCatalog.MODEL))
            throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
        URI uri = model.source();
        for (int redirect = 0; redirect <= 5; redirect++) {
            check.run();
            validate(uri);
            Response response = client
                    .newCall(new Request.Builder().url(uri.toString()).header("Accept-Encoding", "identity").build())
                    .execute();
            try {
                check.run();
            } catch (RuntimeException canceled) {
                response.close();
                throw canceled;
            }
            if (response.isRedirect()) {
                String location = response.header("Location");
                response.close();
                if (location == null)
                    throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
                try {
                    uri = uri.resolve(location);
                } catch (IllegalArgumentException malformed) {
                    throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
                }
                continue;
            }
            if (response.code() != 200 || response.body() == null) {
                response.close();
                throw new AsrDownloadException(AsrDownloadException.Code.DOWNLOAD_FAILED);
            }
            long length = response.body().contentLength();
            if (length != -1 && length != model.bytes()) {
                response.close();
                throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
            }
            return new FilterInputStream(response.body().byteStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
        }
        throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
    }
    static void validate(URI uri) {
        if (uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawUserInfo() != null || uri.getFragment() != null || uri.getPort() == 0
                || uri.getPort() > 65535)
            throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
    }
}
