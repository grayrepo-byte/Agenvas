package dev.agenvas.mediatemplate.infrastructure;

import static dev.agenvas.mediatemplate.infrastructure.VideoPromptJson.*;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptAdapter;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import tools.jackson.databind.JsonNode;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Public video API and repository exports use the same model-specific adapter. */
@Component
public class ImagePromptGalleryAdapter implements ThirdPartyPromptAdapter {
    private static final int MAX_PREFILL_BYTES = 512 * 1024;
    private final ObjectMapper mapper;
    public ImagePromptGalleryAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public Format format() {
        return Format.IMAGE_PROMPT_GALLERY_JSON;
    }
    /** Follow explicit continuation metadata, keeping every request on the configured endpoint. */
    @Override
    public List<ThirdPartyPrompt> fetch(ThirdPartyPromptSource source, ThirdPartyPromptHttpClient http) {
        var prompts = new LinkedHashMap<String, ThirdPartyPrompt>();
        var visited = new HashSet<String>();
        var receivedIds = new HashSet<String>();
        String url = source.url();
        int received = 0;
        long bytes = 0;
        for (int page = 0; page < 300; page++) {
            if (!visited.add(url))
                throw ThirdPartyPromptValidation.invalid();
            String body = http.feed(url);
            bytes += body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > ThirdPartyPromptHttpClient.MAX_FEED_BYTES)
                throw ThirdPartyPromptValidation.invalid();
            var root = mapper.readTree(body);
            var batch = items(root);
            received += batch.size();
            for (var item : batch)
                receivedIds.add(text(item, "domain") + ":" + text(item, "model") + ":" + text(item, "id"));
            if (received > ThirdPartyPromptValidation.MAX_ENTRIES)
                throw ThirdPartyPromptValidation.invalid();
            for (var prompt : parse(source, mapper.writeValueAsString(batch))) {
                var old = prompts.putIfAbsent(prompt.id(), prompt);
                if (old != null && !old.equals(prompt))
                    throw ThirdPartyPromptValidation.invalid();
            }
            var pagination = root.path("pagination").isObject() ? root.path("pagination") : root;
            String next = text(pagination, "next");
            if (next.isBlank())
                next = text(pagination, "nextUrl");
            var builder = UriComponentsBuilder.fromUriString(source.url());
            if (!next.isBlank()) {
                var base = java.net.URI.create(source.url());
                var destination = base.resolve(next);
                if (!base.getScheme().equals(destination.getScheme())
                        || !base.getRawAuthority().equals(destination.getRawAuthority())
                        || !base.getPath().equals(destination.getPath()))
                    throw ThirdPartyPromptValidation.invalid();
                var destinationBuilder = UriComponentsBuilder.fromUri(destination);
                UriComponentsBuilder.fromUriString(source.url()).build().getQueryParams().forEach((key, values) -> {
                    if (!java.util.Set.of("cursor", "offset", "page").contains(key))
                        destinationBuilder.replaceQueryParam(key, values);
                });
                url = destinationBuilder.build().toUriString();
            } else if (!text(pagination, "nextCursor").isBlank()) {
                url = builder.replaceQueryParam("cursor", "{cursor}").encode()
                        .buildAndExpand(text(pagination, "nextCursor")).toUriString();
            } else if (pagination.path("nextOffset").isIntegralNumber()) {
                url = builder.replaceQueryParam("offset", pagination.path("nextOffset").asInt()).build().encode()
                        .toUriString();
            } else if (pagination.path("nextPage").isIntegralNumber()) {
                url = builder.replaceQueryParam("page", pagination.path("nextPage").asInt()).build().encode()
                        .toUriString();
            } else if (pagination.path("offset").isIntegralNumber() && pagination.path("limit").isIntegralNumber()
                    && pagination.path("total").isIntegralNumber()
                    && pagination.path("total").asLong() > receivedIds.size()) {
                // The compact public API exposes offset/limit/total rather than an explicit next link.
                long offset = pagination.path("offset").asLong(), limit = pagination.path("limit").asLong();
                long total = pagination.path("total").asLong();
                if (offset < 0 || offset >= total || limit <= 0 || limit > ThirdPartyPromptValidation.MAX_ENTRIES
                        || total > ThirdPartyPromptValidation.MAX_ENTRIES || batch.size() != limit
                        || offset + limit >= total)
                    throw ThirdPartyPromptValidation.invalid();
                url = builder.replaceQueryParam("offset", offset + limit).build().encode().toUriString();
            } else {
                if (pagination.path("hasMore").asBoolean(false)
                        || pagination.path("total").asInt(0) > receivedIds.size()
                        || root.path("total").asInt(0) > receivedIds.size())
                    throw ThirdPartyPromptValidation.invalid();
                return List.copyOf(prompts.values());
            }
            if (batch.isEmpty())
                throw ThirdPartyPromptValidation.invalid();
        }
        throw ThirdPartyPromptValidation.invalid();
    }
    @Override
    public List<ThirdPartyPrompt> parse(ThirdPartyPromptSource source, String body) {
        if (source.targetKind() != TargetKind.VIDEO)
            throw ThirdPartyPromptValidation.invalid();
        var root = mapper.readTree(body);
        var items = items(root);
        // Never silently publish the first page as a complete daily sync.
        if (root.path("total").asInt(0) > items.size() || root.path("hasMore").asBoolean(false))
            throw ThirdPartyPromptValidation.invalid();
        var result = new LinkedHashMap<String, ThirdPartyPrompt>();
        for (var item : items) {
            if (!text(item, "domain").equals("video") || !text(item, "model").equals(source.model()))
                continue;
            String upstreamId = text(item, "id"), prompt = text(item, "prompt");
            if (prompt.isBlank())
                prompt = publicPrefillPrompt(item);
            if (upstreamId.isBlank() || prompt.isBlank())
                throw ThirdPartyPromptValidation.invalid();
            var references = galleryReferences(item);
            String declared = text(item, "inputMode");
            if (declared.isBlank())
                declared = text(item, "generationMode");
            var inputs = VideoPromptInputs.resolve(prompt, VideoPromptInputs.mode(declared), references, List.of(),
                    declared.equals("audio-driven"));
            var attribution = item.path("source");
            String original = text(attribution, "originalUrl");
            if (original.isBlank())
                original = text(attribution, "caseUrl");
            if (original.isBlank() && !text(item, "caseHref").isBlank())
                original = URI.create(source.url()).resolve(text(item, "caseHref")).toString();
            if (original.isBlank())
                original = source.url();
            var tags = new ArrayList<>(strings(item, "tags"));
            for (String tag : strings(item, "styleTags"))
                if (!tags.contains(tag))
                    tags.add(tag);
            String category = text(item, "category");
            if (!category.isBlank() && !tags.contains(category))
                tags.add(category);
            String author = text(item, "author");
            if (author.isBlank())
                author = text(item.path("attribution"), "sourceName");
            String cover = text(item, "coverUrl"), preview = text(item, "previewVideoUrl");
            if (cover.isBlank())
                cover = text(item.path("media"), "thumbnailUrl");
            if (preview.isBlank() && "video".equals(text(item.path("media"), "type")))
                preview = text(item.path("media"), "url");
            String id = source.id() + ":" + upstreamId;
            var value = new Video(id, source.id(), text(item, "title"), prompt, text(item, "description"),
                    cover, List.copyOf(tags), author, original, text(item, "createdAt"), inputs.mode(), source.model(),
                    references, null, preview, inputs.missing());
            var previous = result.putIfAbsent(id, value);
            if (previous != null && !previous.equals(value))
                throw ThirdPartyPromptValidation.invalid();
        }
        return List.copyOf(result.values());
    }

    /** Read the inline public launch payload as data; never follow its destination or use its fallback model. */
    private String publicPrefillPrompt(JsonNode item) {
        String href = text(item, "tryHref");
        if (href.length() > MAX_PREFILL_BYTES * 2)
            throw ThirdPartyPromptValidation.invalid();
        try {
            URI uri = URI.create(href);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() == null)
                throw ThirdPartyPromptValidation.invalid();
            String encoded = null;
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "s".equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                    if (encoded != null)
                        throw ThirdPartyPromptValidation.invalid();
                    encoded = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
            if (encoded == null || encoded.length() > MAX_PREFILL_BYTES * 2)
                throw ThirdPartyPromptValidation.invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_PREFILL_BYTES)
                throw ThirdPartyPromptValidation.invalid();
            var prefill = mapper.readTree(bytes);
            if (!"image-prompt-gallery".equals(text(prefill, "source")) || text(item, "slug").isBlank()
                    || !text(item, "slug").equals(text(prefill, "case")))
                throw ThirdPartyPromptValidation.invalid();
            return text(prefill, "prompt");
        } catch (RuntimeException failure) {
            throw ThirdPartyPromptValidation.invalid();
        }
    }

    private List<Reference> galleryReferences(JsonNode item) {
        var explicit = references(mapper, item);
        if (!explicit.isEmpty())
            return explicit;
        // Only the separately declared input array supplies references; the preview remains output-only.
        return strings(item, "referenceImageUrls").stream()
                .map(url -> new Reference(MediaKind.IMAGE, Role.REFERENCE, url)).toList();
    }
}
