package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Two explicit metadata adapters; upstream provenance claims never become download authorization. */
@Component
public class ReferenceCatalogParser {
    public static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    public ReferenceCatalogParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    public Snapshot parse(Source source, String commit, byte[] data) {
        if (data.length > MAX_METADATA_BYTES || commit != null && !commit.matches("[a-f0-9]{40}")
                || commit == null && source.format() != Format.OPEN_POSE_COSTUME)
            throw invalid();
        try {
            JsonNode root = mapper.readTree(data);
            boolean upstream = source.format() == Format.OPEN_POSE_COSTUME;
            if (!root.isObject() || !upstream && root.path("schemaVersion").asInt() != 1)
                throw invalid();
            JsonNode items = root.path(upstream ? "items" : "entries");
            if (!items.isArray() || items.isEmpty() || items.size() > 10_000)
                throw invalid();
            String license = upstream ? "UPSTREAM_RESTRICTED" : text(root, "license", 100);
            List<Entry> entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> variantIds = new HashSet<>();
            for (JsonNode item : items) {
                String kindValue = text(item, "kind", 20);
                if (upstream && kindValue.equals("character"))
                    continue;
                Kind kind;
                try {
                    kind = Kind.valueOf(kindValue.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException failure) {
                    throw invalid();
                }
                String id = slug(text(item, "id", 100));
                if (!ids.add(id))
                    throw invalid();
                Map<String, String> title = upstream
                        ? localized(item.path("title"))
                        : Map.of("en", text(item, "title", 160));
                Map<String, String> category = upstream
                        ? localized(item.path("category"))
                        : Map.of("en", text(item, "category", 160));
                String categoryId = upstream
                        ? slug(text(item.path("category"), "id", 100))
                        : text(item, "category", 160);
                List<String> tags = tags(item.path("tags"), upstream);
                Map<String, Map<String, String>> localizedTags = new TreeMap<>();
                if (upstream && item.path("tags").isArray())
                    for (JsonNode tag : item.path("tags"))
                        localizedTags.put(text(tag, "id", 100), localized(tag));
                Map<String, String> facets = new TreeMap<>();
                if (!upstream && item.has("facets")) {
                    JsonNode f = item.get("facets");
                    if (!f.isObject() || f.size() > 20)
                        throw invalid();
                    f.properties().forEach(e -> {
                        slug(e.getKey());
                        if (!e.getValue().isTextual() || e.getValue().asText().length() > 160)
                            throw invalid();
                        facets.put(e.getKey(), e.getValue().asText());
                    });
                }
                tags.stream().filter(t -> Set.of("photo", "anime", "line-art").contains(t)).findFirst()
                        .ifPresent(style -> facets.put("style", style));
                JsonNode variants = item.path("variants");
                if (!variants.isArray() || variants.isEmpty() || variants.size() > 16)
                    throw invalid();
                List<Variant> parsed = new ArrayList<>();
                for (JsonNode variant : variants) {
                    String variantId = slug(text(variant, "id", 100));
                    if (!variantIds.add(id + ":" + variantId))
                        throw invalid();
                    String angle = optional(variant, upstream ? "angle" : "view", 80);
                    Map<String, String> label = upstream
                            ? localized(variant.path("label"))
                            : Map.of("en", text(variant, "label", 160));
                    if (upstream) {
                        String originalValue = text(variant, "original", 2048);
                        String original = link(source, commit, originalValue);
                        // Validate supplied presentation links too, while never fetching or hotlinking them.
                        for (String field : List.of("thumbnail", "image"))
                            if (variant.has(field))
                                link(source, commit, text(variant, field, 2048));
                        String file = !originalValue.contains(":") ? path("public/" + path(originalValue)) : null;
                        parsed.add(new Variant(variantId, display(label), angle, angle, label, original, file, null,
                                null, null));
                    } else {
                        String file = path(text(variant, "path", 512));
                        String sha = text(variant, "sha256", 64);
                        long bytes = variant.path("byteSize").asLong(-1);
                        String type = text(variant, "contentType", 80);
                        if (!sha.matches("[a-f0-9]{64}") || !variant.path("byteSize").isIntegralNumber() || bytes <= 0
                                || bytes > MAX_IMAGE_BYTES
                                || !Set.of("image/png", "image/jpeg", "image/webp").contains(type))
                            throw invalid();
                        parsed.add(new Variant(variantId, display(label), angle, angle, label,
                                githubLink(source, commit, file), file, sha, bytes, type));
                    }
                }
                String sourceLink = upstream ? text(item, "source", 2048) : "";
                String external = upstream && !sourceLink.equals("custom")
                        ? link(source, commit, sourceLink)
                        : source.sourceType() == SourceType.OMILIC
                                ? ReferenceCatalogSiteClient.ORIGIN
                                : githubLink(source, commit, source.manifestPath());
                String collection = upstream && item.path("collection").asText().equals("custom")
                        ? "custom"
                        : "builtin";
                if (upstream && !item.path("extended").isBoolean())
                    throw invalid();
                entries.add(
                        new Entry(id, kind, display(title), title, optional(item, "code", 100), categoryId, category,
                                upstream ? localized(item.path("series")) : Map.of(), tags, Map.copyOf(localizedTags),
                                upstream ? localized(item.path("keywords")) : Map.of(), Map.copyOf(facets), collection,
                                upstream && item.path("extended").asBoolean(), external, List.copyOf(parsed)));
            }
            if (entries.isEmpty())
                throw invalid();
            return new Snapshot(UUID.randomUUID(), source.id(), commit, hash(data), license, List.copyOf(entries));
        } catch (dev.agenvas.shared.error.ApiProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }
    public static boolean importable(Source source, Snapshot snapshot, Variant variant) {
        return source.enabled() && source.imageDistributionApproved()
                && source.format() == Format.AGENVAS_REFERENCE_PACK
                && Set.of("CC0-1.0", "CC-BY-4.0", "MIT").contains(snapshot.license()) && variant.path() != null
                && variant.sha256() != null;
    }
    private static String text(JsonNode node, String field, int max) {
        JsonNode n = node.path(field);
        if (!n.isTextual() || n.asText().isBlank() || n.asText().length() > max)
            throw invalid();
        return n.asText();
    }
    private static String optional(JsonNode node, String field, int max) {
        return node.has(field) ? text(node, field, max) : "";
    }
    private static Map<String, String> localized(JsonNode node) {
        if (!node.isObject())
            throw invalid();
        return Map.of("zh", text(node, "zh", 2000), "en", text(node, "en", 2000));
    }
    private static String display(Map<String, String> values) {
        return values.getOrDefault("en", values.getOrDefault("zh", ""));
    }
    private static List<String> tags(JsonNode node, boolean upstream) {
        if (node.isMissingNode())
            return List.of();
        if (!node.isArray() || node.size() > 30)
            throw invalid();
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : node) {
            String value = upstream ? text(tag, "id", 100) : tag.asText();
            slug(value);
            if (tags.contains(value))
                throw invalid();
            tags.add(value);
        }
        return List.copyOf(tags);
    }
    private static String link(Source source, String commit, String value) {
        if (!value.contains(":"))
            return source.sourceType() == SourceType.OMILIC
                    ? ReferenceCatalogSiteClient.ORIGIN + path(value)
                    : rawLink(source, commit, "public/" + path(value));
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw invalid();
            return uri.toASCIIString();
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }
    private static String githubLink(Source source, String commit, String path) {
        return "https://github.com/" + source.owner() + "/" + source.repository() + "/blob/"
                + (commit == null ? source.ref() : commit) + "/" + path.replace(" ", "%20");
    }
    private static String rawLink(Source source, String commit, String path) {
        return "https://raw.githubusercontent.com/" + source.owner() + "/" + source.repository() + "/"
                + (commit == null ? source.ref() : commit) + "/" + path.replace(" ", "%20");
    }
}
