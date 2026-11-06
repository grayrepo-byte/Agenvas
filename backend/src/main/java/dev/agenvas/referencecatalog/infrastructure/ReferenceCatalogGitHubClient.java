package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.Source;
import dev.agenvas.shared.http.PinnedHttpClients;
import dev.agenvas.shared.security.EndpointAddressRules;
import java.io.IOException;
import java.time.Duration;
import okhttp3.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Credential-free fixed-host HTTPS reads. No redirects, proxies, git processes or archives. */
@Component
public class ReferenceCatalogGitHubClient {
    private final OkHttpClient http;
    public ReferenceCatalogGitHubClient() {
        this.http=PinnedHttpClients.pinnedUncaptured(host -> {
            if (!host.equals("api.github.com") && !host.equals("raw.githubusercontent.com")) throw new java.net.UnknownHostException("Blocked catalog host");
            var addresses=Dns.SYSTEM.lookup(host);
            if(addresses.isEmpty() || addresses.stream().anyMatch(a -> !EndpointAddressRules.allowsPublicDownload(a)))
                throw new java.net.UnknownHostException("Blocked catalog address");
            return addresses;
        },Duration.ofSeconds(10),Duration.ofSeconds(30),Duration.ofSeconds(60));
    }
    public String resolve(Source source) {
        if (source.ref().matches("[a-fA-F0-9]{40}")) return source.ref().toLowerCase(java.util.Locale.ROOT);
        var url=new HttpUrl.Builder().scheme("https").host("api.github.com").addPathSegment("repos").addPathSegment(slug(source.owner()))
                .addPathSegment(slug(source.repository())).addPathSegment("commits").addPathSegment(ref(source.ref())).build();
        try {
            String sha=new String(read(url,128,"application/vnd.github.sha"),java.nio.charset.StandardCharsets.US_ASCII).strip(); if(!sha.matches("[a-f0-9]{40}")) throw unavailable(); return sha;
        } catch(dev.agenvas.shared.error.ApiProblemException failure){throw failure;}
        catch(RuntimeException failure){throw unavailable();}
    }
    public byte[] file(Source source,String commit,String file,int limit) {
        if(!commit.matches("[a-f0-9]{40}")) throw invalid();
        var url=new HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com").addPathSegment(slug(source.owner()))
                .addPathSegment(slug(source.repository())).addPathSegment(commit);
        for(String part:path(file).split("/")) url.addPathSegment(part);
        return read(url.build(),limit,"application/octet-stream");
    }
    private byte[] read(HttpUrl url,int limit,String accept) {
        try(var response=http.newCall(new Request.Builder().url(url).header("User-Agent","Agenvas-Reference-Catalog/1")
                .header("Accept",accept).get().build()).execute()) {
            if(!response.isSuccessful() || response.body()==null) throw unavailable();
            if(response.body().contentLength()>limit) throw invalid();
            try(var input=response.body().byteStream()) {
                byte[] bytes=input.readNBytes(limit+1); if(bytes.length>limit) throw invalid(); return bytes;
            }
        } catch(IOException failure){throw unavailable();}
    }
    private static dev.agenvas.shared.error.ApiProblemException unavailable() {
        return problem(HttpStatus.BAD_GATEWAY,"REFERENCE_CATALOG_REMOTE_UNAVAILABLE","api.reference-catalog.remoteUnavailable");
    }
}
