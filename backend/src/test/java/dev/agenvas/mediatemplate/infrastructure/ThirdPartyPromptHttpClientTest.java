package dev.agenvas.mediatemplate.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient.DownloadFailure;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient.FailureReason;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.jupiter.api.Test;

/** Synthetic application interceptors stop before DNS or sockets; no live source is contacted. */
class ThirdPartyPromptHttpClientTest {
    private static final String URL = "https://example.com/synthetic-feed";

    @Test void boundedSuccessUsesCredentialFreeGetWithoutVisitingAnyOtherAddress() {
        AtomicInteger requests = new AtomicInteger();
        var client = new ThirdPartyPromptHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertThat(chain.request().url().toString()).isEqualTo(URL);
            assertThat(chain.request().method()).isEqualTo("GET");
            assertThat(chain.request().header("Authorization")).isNull();
            assertThat(chain.request().header("User-Agent")).isEqualTo("Agenvas-Prompt-Sync/1");
            return response(chain.request(), 200, ResponseBody.create("[]", MediaType.get("application/json")));
        }).build());
        assertThat(client.feed(URL)).isEqualTo("[]");
        assertThat(requests).hasValue(1);
    }

    @Test void rejectedHttpResponseUsesCategoryWithoutItsBody() {
        assertFailure(503, ResponseBody.create("PRIVATE UPSTREAM ERROR", MediaType.get("text/plain")), 20,
                FailureReason.HTTP_ERROR);
    }

    @Test void declaredAndStreamedLimitsHaveSameSafeCategory() {
        assertFailure(200, ResponseBody.create("12345", MediaType.get("application/json")), 4, FailureReason.TOO_LARGE);
        var streamed = new ResponseBody() {
            private final BufferedSource source = new Buffer().writeUtf8("12345");
            @Override public MediaType contentType() { return MediaType.get("application/json"); }
            @Override public long contentLength() { return -1; }
            @Override public BufferedSource source() { return source; }
        };
        assertFailure(200, streamed, 4, FailureReason.TOO_LARGE);
    }

    @Test void dnsTransportOrPolicyFailureDoesNotClaimAnUnprovedRootCause() {
        for (IOException failure : new IOException[] {new UnknownHostException("PRIVATE DNS DETAILS"), new IOException("PRIVATE TRANSPORT DETAILS")}) {
            var client = new ThirdPartyPromptHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> { throw failure; }).build());
            assertThatThrownBy(() -> client.feed(URL)).isInstanceOfSatisfying(DownloadFailure.class, result -> {
                assertThat(result.reason()).isEqualTo(FailureReason.DOWNLOAD_FAILED);
                assertThat(result.getMessage()).isEqualTo("THIRD_PARTY_SOURCE_DOWNLOAD_FAILED");
                assertThat(result.getMessage()).doesNotContain("PRIVATE");
            });
        }
    }

    private void assertFailure(int status, ResponseBody body, int limit, FailureReason reason) {
        var client = new ThirdPartyPromptHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), status, body)).build());
        assertThatThrownBy(() -> client.download(URL, limit)).isInstanceOfSatisfying(DownloadFailure.class,
                result -> assertThat(result.reason()).isEqualTo(reason));
    }

    private Response response(okhttp3.Request request, int status, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
                .body(body).build();
    }
}
