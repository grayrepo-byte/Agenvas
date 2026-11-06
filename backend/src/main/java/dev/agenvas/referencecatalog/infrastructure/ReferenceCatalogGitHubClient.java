package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.Source;
import dev.agenvas.shared.http.PublicDownloadHttpClients;
import dev.agenvas.shared.http.PublicDownloadException;
import java.io.IOException;
import java.time.Duration;
import okhttp3.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Credential-free fixed-host HTTPS reads. No redirects, git processes or archives; deployment proxy is explicitly opt-in. */
@Component
public class ReferenceCatalogGitHubClient {
    private final OkHttpClient http;
    private final java.util.Map<String,tools.jackson.databind.JsonNode> trees=new java.util.LinkedHashMap<>(16,0.75f,true);
    private final tools.jackson.databind.ObjectMapper treeMapper=new tools.jackson.databind.ObjectMapper();
    public ReferenceCatalogGitHubClient() { this(new PublicDownloadHttpClients()); }
    @org.springframework.beans.factory.annotation.Autowired
    public ReferenceCatalogGitHubClient(PublicDownloadHttpClients clients) {
        this.http=clients.create(java.util.Set.of("api.github.com","raw.githubusercontent.com"),
                Duration.ofSeconds(10),Duration.ofSeconds(30),Duration.ofSeconds(60));
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
    public record GitFile(String blobSha,long byteSize){}
    /** Verify exactly one generated repository path against the immutable commit tree; never use content URLs. */
    public GitFile inspectFile(Source source,String commit,String file){
        if(!commit.matches("[a-f0-9]{40}"))throw invalid();String[] parts=path(file).split("/");
        if(parts.length!=3 || !(parts[0].equals("pose") || parts[0].equals("costume")) || !parts[1].equals("originals") || !parts[2].endsWith(".png"))throw invalid();
        String tree=commit;
        for(int depth=0;depth<parts.length;depth++){
            var root=tree(source,tree);
            if(depth>0 && !tree.equals(root.path("sha").asText()))throw invalid();
            tools.jackson.databind.JsonNode matched=null;
            for(var candidate:root.path("tree"))if(candidate.path("path").asText().equals(parts[depth])){if(matched!=null)throw invalid();matched=candidate;}
            if(matched==null)throw unavailable();String sha=matched.path("sha").asText();if(!sha.matches("[a-f0-9]{40}"))throw invalid();
            if(depth<parts.length-1){if(!matched.path("type").asText().equals("tree") || !matched.path("mode").asText().equals("040000"))throw invalid();tree=sha;}
            else{long size=matched.path("size").asLong(-1);if(!matched.path("type").asText().equals("blob") || !java.util.Set.of("100644","100755").contains(matched.path("mode").asText())
                        || !matched.path("size").isIntegralNumber() || size<1 || size>MAX_IMAGE_BYTES)throw invalid();return new GitFile(sha,size);}
        }
        throw invalid();
    }
    private tools.jackson.databind.JsonNode tree(Source source,String sha){
        String key=source.owner().toLowerCase(java.util.Locale.ROOT)+"/"+source.repository().toLowerCase(java.util.Locale.ROOT)+"/"+sha;
        synchronized(trees){var cached=trees.get(key);if(cached!=null)return cached;}
        var url=new HttpUrl.Builder().scheme("https").host("api.github.com").addPathSegment("repos").addPathSegment(slug(source.owner())).addPathSegment(slug(source.repository()))
                .addPathSegment("git").addPathSegment("trees").addPathSegment(sha).build();
        tools.jackson.databind.JsonNode root;
        try{root=treeMapper.readTree(read(url,2*1024*1024,"application/vnd.github+json"));}catch(dev.agenvas.shared.error.ApiProblemException failure){throw failure;}catch(RuntimeException failure){throw invalid();}
        if(!root.path("sha").asText().matches("[a-f0-9]{40}") || !root.path("truncated").isBoolean() || root.path("truncated").asBoolean() || !root.path("tree").isArray() || root.path("tree").size()>10_000)throw invalid();
        synchronized(trees){trees.put(key,root);while(trees.size()>16)trees.remove(trees.keySet().iterator().next());}
        return root;
    }
    private byte[] read(HttpUrl url,int limit,String accept) {
        try(var response=http.newCall(new Request.Builder().url(url).header("User-Agent","Agenvas-Reference-Catalog/1")
                .header("Accept",accept).get().build()).execute()) {
            if(response.code()==429 || response.code()==403 && "0".equals(response.header("X-RateLimit-Remaining")))
                throw problem(HttpStatus.BAD_GATEWAY,"REFERENCE_CATALOG_REMOTE_RATE_LIMITED","api.reference-catalog.remoteUnavailable");
            if(!response.isSuccessful() || response.body()==null) throw unavailable();
            if(response.body().contentLength()>limit) throw invalid();
            try(var input=response.body().byteStream()) {
                byte[] bytes=input.readNBytes(limit+1); if(bytes.length>limit) throw invalid(); return bytes;
            }
        } catch(IOException failure){
            throw problem(HttpStatus.BAD_GATEWAY,"REFERENCE_CATALOG_"+PublicDownloadException.reason(failure).name(),"api.reference-catalog.remoteUnavailable");
        }
    }
    private static dev.agenvas.shared.error.ApiProblemException unavailable() {
        return problem(HttpStatus.BAD_GATEWAY,"REFERENCE_CATALOG_REMOTE_UNAVAILABLE","api.reference-catalog.remoteUnavailable");
    }
}
