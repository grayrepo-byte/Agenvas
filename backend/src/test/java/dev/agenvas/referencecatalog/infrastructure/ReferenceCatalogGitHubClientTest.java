package dev.agenvas.referencecatalog.infrastructure;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.net.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReferenceCatalogGitHubClientTest {
    Source source(String ref){return new Source("fixture","Synthetic","owner","repo",ref,"reference-pack.json",Format.AGENVAS_REFERENCE_PACK,true,true,0,null,"NEVER",null,null,null);}
    @Test void commitResolutionUsesBoundedShaMediaTypeAndAnExplicitCommitNeedsNoRequest(){
        var client=new ReferenceCatalogGitHubClient();var request=new AtomicReference<Request>();
        var fake=new OkHttpClient.Builder().addInterceptor(chain->{request.set(chain.request());return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("a".repeat(40)+"\n",MediaType.get("application/vnd.github.sha"))).build();}).build();
        ReflectionTestUtils.setField(client,"http",fake);
        assertThat(client.resolve(source("main"))).isEqualTo("a".repeat(40));
        assertThat(request.get().url().toString()).isEqualTo("https://api.github.com/repos/owner/repo/commits/main");
        assertThat(request.get().header("Accept")).isEqualTo("application/vnd.github.sha");
        assertThat(request.get().header("Authorization")).isNull();
        request.set(null);assertThat(client.resolve(source("B".repeat(40)))).isEqualTo("b".repeat(40));assertThat(request.get()).isNull();
    }
    @Test void strictTransportRejectsUnknownHostsAndDisablesProxyRedirectsAndRetries() throws Exception {
        var client=new ReferenceCatalogGitHubClient();var http=(OkHttpClient)ReflectionTestUtils.getField(client,"http");
        assertThat(http.proxy()).isEqualTo(Proxy.NO_PROXY);assertThat(http.followRedirects()).isFalse();assertThat(http.followSslRedirects()).isFalse();assertThat(http.retryOnConnectionFailure()).isFalse();
        assertThatThrownBy(()->http.dns().lookup("metadata.google.internal")).isInstanceOf(java.net.UnknownHostException.class);
    }
    @Test void rateLimitedResponseIsSanitizedAndUnsafeFilePathNeverDispatches(){
        var client=new ReferenceCatalogGitHubClient();var count=new AtomicInteger();
        var fake=new OkHttpClient.Builder().addInterceptor(chain->{count.incrementAndGet();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("secret upstream detail")
                .header("Location","http://127.0.0.1/private").body(ResponseBody.create("private token",MediaType.get("text/plain"))).build();}).build();
        ReflectionTestUtils.setField(client,"http",fake);
        assertThatThrownBy(()->client.resolve(source("main"))).isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("REFERENCE_CATALOG_REMOTE_UNAVAILABLE");
        assertThat(count.get()).isEqualTo(1);
        assertThatThrownBy(()->client.file(source("main"),"a".repeat(40),"../private",100)).isInstanceOf(ApiProblemException.class);
        assertThat(count.get()).isEqualTo(1);
    }
}
