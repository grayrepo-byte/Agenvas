package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogGitHubClient;
import dev.agenvas.shared.error.ApiProblemException;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Synthetic response/exception classification only; no claim of live source synchronization. */
class PublicDownloadSourceFailureTest {
    private Source source(){return new Source("synthetic","Synthetic","owner","repo","a".repeat(40),"catalog.json",Format.OPEN_POSE_COSTUME,true,false,0,null,"NEVER",null,null,null);}
    @Test void sourceRetainsTypedDnsAndProxyReasonsWithoutPrivateDetails() {
        for(var reason:PublicDownloadException.Reason.values()) {
            var client=new ReferenceCatalogGitHubClient();
            var fake=new OkHttpClient.Builder().addInterceptor(chain->{throw new PublicDownloadException(reason);}).build();
            ReflectionTestUtils.setField(client,"http",fake);
            assertThatThrownBy(()->client.file(source(),source().ref(),"catalog.json",100))
                .isInstanceOfSatisfying(ApiProblemException.class,failure->assertThat(failure.code()).isEqualTo("REFERENCE_CATALOG_"+reason.name()));
        }
    }
    @Test void rateLimitHasItsOwnCategoryAndNoResponseBodyOrLocationIsRetained() {
        var client=new ReferenceCatalogGitHubClient();
        ReflectionTestUtils.setField(client,"http",new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("private upstream")
                .header("Location","http://127.0.0.1/private").body(ResponseBody.create("private token",MediaType.get("text/plain"))).build()).build());
        assertThatThrownBy(()->client.file(source(),source().ref(),"catalog.json",100)).isInstanceOfSatisfying(ApiProblemException.class,failure->{
            assertThat(failure.code()).isEqualTo("REFERENCE_CATALOG_REMOTE_RATE_LIMITED");assertThat(failure.getMessage()).doesNotContain("private","127.0.0.1","token");assertThat(failure.getCause()).isNull();
        });
    }
}
