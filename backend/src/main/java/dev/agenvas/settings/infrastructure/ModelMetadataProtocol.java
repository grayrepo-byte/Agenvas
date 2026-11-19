package dev.agenvas.settings.infrastructure;

import dev.agenvas.settings.application.ModelCatalog;
import dev.agenvas.settings.application.ModelCatalog.Model;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Compiled metadata wire profiles. All exchanges use the client's bounded transport. */
class ModelMetadataProtocol {
    interface Exchange {
        JsonNode request(String path, String cursor, JsonNode body) throws IOException;
    }

    private interface ModelReader {
        Model read(JsonNode entry, String id, String digest);
    }

    final String entriesField;
    private final String idField;

    ModelMetadataProtocol(String entriesField, String idField) {
        this.entriesField = entriesField;
        this.idField = idField;
    }

    static ModelMetadataProtocol forProvider(ModelCatalog.Provider provider) {
        return switch (provider) {
            case COMPATIBLE, OPENAI -> COMPATIBLE;
            case OPENROUTER -> OPENROUTER;
            case GEMINI -> GEMINI;
            case ANTHROPIC -> ANTHROPIC;
            case OLLAMA -> OLLAMA;
        };
    }

    void authenticate(Request.Builder request, String key) {
        request.header("Authorization", "Bearer " + key);
    }

    void query(HttpUrl.Builder url, String cursor) {
        // Unpaged list requests have no query parameters.
    }

    ModelCatalog fetch(String prefix, String selected, String key, Exchange exchange) throws IOException {
        return parse(exchange.request(prefix + "/models", "", null));
    }

    final ModelCatalog parse(JsonNode listed) {
        return parse(listed, this::model);
    }

    final ModelCatalog parse(JsonNode listed, ModelReader reader) {
        var entries = listed.path(entriesField);
        if (!entries.isArray())
            throw new IllegalArgumentException("MODEL_METADATA_INVALID");
        var result = new LinkedHashMap<String, Model>();
        boolean partial = listed.path("has_more").asBoolean(false)
                || !listed.path("nextPageToken").asText("").isEmpty();
        for (var entry : entries) {
            if (result.size() >= ModelCatalog.MAX_MODELS) {
                partial = true;
                break;
            }
            String id = normalizeId(entry.path(idField).asText(""));
            if (!ModelCatalog.validModelId(id)) {
                partial = true;
                continue;
            }
            String digest = entry.path("digest").asText("");
            Model model = reader.read(entry, id, digest.isEmpty() || digest.length() > 160 ? null : digest);
            if (model != null)
                result.putIfAbsent(id, model);
        }
        return new ModelCatalog(List.copyOf(result.values()), partial);
    }

    String normalizeId(String id) {
        return id;
    }

    Model model(JsonNode entry, String id, String digest) {
        return new Model(id, null, null, null, null, digest, null);
    }

    private static Integer limit(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0
                && value.intValue() <= ModelCatalog.MAX_REPORTED_TOKENS ? value.intValue() : null;
    }

    private static final ModelMetadataProtocol COMPATIBLE = new ModelMetadataProtocol("data", "id");

    private static final ModelMetadataProtocol OPENROUTER = new ModelMetadataProtocol("data", "id") {
        @Override
        Model model(JsonNode entry, String id, String digest) {
            var top = entry.path("top_provider");
            return new Model(id, limit(entry.path("context_length")), null, limit(top.path("max_completion_tokens")),
                    null, digest, limit(top.path("context_length")));
        }
    };

    private abstract static class Paged extends ModelMetadataProtocol {

        Paged(String field, String idField) {
            super(field, idField);
        }

        String listPath(String prefix) {
            return prefix + "/models";
        }

        abstract String cursor(JsonNode page);
        abstract void updateMarker(ObjectNode combined, JsonNode next, String cursor);

        @Override
        final ModelCatalog fetch(String prefix, String selected, String key, Exchange exchange) throws IOException {
            String path = listPath(prefix);
            var first = exchange.request(path, "", null);
            if (!first.isObject() || !first.path(entriesField).isArray())
                throw ModelMetadataClient.failure("INVALID");
            var combined = (ObjectNode) first;
            var entries = (ArrayNode) combined.path(entriesField);
            var seen = new HashSet<String>();
            String cursor = cursor(first);
            // A cursor is opaque and untrusted. Never put a credential echo into a follow-up URL.
            for (int page = 1; page < 4 && !cursor.isEmpty() && !cursor.contains(key)
                    && entries.size() < ModelCatalog.MAX_MODELS && seen.add(cursor); page++) {
                var next = exchange.request(path, cursor, null);
                if (!next.path(entriesField).isArray())
                    throw ModelMetadataClient.failure("INVALID");
                for (var entry : next.path(entriesField))
                    entries.add(entry);
                cursor = cursor(next);
                updateMarker(combined, next, cursor);
            }
            return parse(combined);
        }

        static String token(JsonNode raw) {
            return raw.isString() && raw.asText().length() <= 512 ? raw.asText() : "";
        }
    }

    private static final Paged GEMINI = new Paged("models", "name") {
        @Override
        String listPath(String prefix) {
            return (prefix.endsWith("/openai") ? prefix.substring(0, prefix.length() - 7) : prefix) + "/models";
        }
        @Override
        void authenticate(Request.Builder request, String key) {
            request.header("x-goog-api-key", key);
        }
        @Override
        void query(HttpUrl.Builder url, String cursor) {
            url.addQueryParameter("pageSize", "1000");
            if (!cursor.isEmpty())
                url.addQueryParameter("pageToken", cursor);
        }
        @Override
        String cursor(JsonNode page) {
            return token(page.path("nextPageToken"));
        }
        @Override
        void updateMarker(ObjectNode combined, JsonNode next, String cursor) {
            if (cursor.isEmpty())
                combined.remove("nextPageToken");
            else
                combined.put("nextPageToken", cursor);
        }
        @Override
        String normalizeId(String id) {
            return id.startsWith("models/") ? id.substring(7) : id;
        }
        @Override
        Model model(JsonNode entry, String id, String digest) {
            var methods = entry.path("supportedGenerationMethods");
            if (methods.isArray() && methods.valueStream().noneMatch(m -> "generateContent".equals(m.asText())))
                return null;
            return new Model(id, null, limit(entry.path("inputTokenLimit")), limit(entry.path("outputTokenLimit")),
                    null, digest, null);
        }
    };

    private static final Paged ANTHROPIC = new Paged("data", "id") {
        @Override
        void authenticate(Request.Builder request, String key) {
            request.header("x-api-key", key).header("anthropic-version", "2023-06-01");
        }
        @Override
        void query(HttpUrl.Builder url, String cursor) {
            url.addQueryParameter("limit", "100");
            if (!cursor.isEmpty())
                url.addQueryParameter("after_id", cursor);
        }
        @Override
        String cursor(JsonNode page) {
            return page.path("has_more").asBoolean(false) ? token(page.path("last_id")) : "";
        }
        @Override
        void updateMarker(ObjectNode combined, JsonNode next, String cursor) {
            combined.put("has_more", next.path("has_more").asBoolean(false));
        }
        @Override
        Model model(JsonNode entry, String id, String digest) {
            return new Model(id, null, limit(entry.path("max_input_tokens")), limit(entry.path("max_tokens")),
                    null, digest, null);
        }
    };

    private static final ModelMetadataProtocol OLLAMA = new ModelMetadataProtocol("models", "name") {
        @Override
        ModelCatalog fetch(String prefix, String selected, String key, Exchange exchange) throws IOException {
            String root = prefix.endsWith("/v1") ? prefix.substring(0, prefix.length() - 3) : prefix;
            var tags = exchange.request(root + "/api/tags", "", null);
            var running = exchange.request(root + "/api/ps", "", null);
            var show = selected == null || selected.isEmpty()
                    ? null
                    : exchange.request(root + "/api/show", "",
                            JsonNodeFactory.instance.objectNode().put("model", selected).put("verbose", false));
            return parse(tags, (entry, id, digest) -> ollamaModel(id, digest, show, running, selected));
        }
        private Model ollamaModel(String id, String digest, JsonNode show, JsonNode running, String selected) {
            Integer context = null, runtime = null;
            if (id.equals(selected) && show != null) {
                var info = show.path("model_info");
                String architecture = info.path("general.architecture").asText("");
                if (architecture.matches("[A-Za-z0-9_]{1,80}"))
                    context = limit(info.path(architecture + ".context_length"));
            }
            if (running != null)
                for (JsonNode loaded : running.path("models")) {
                    if (!(id.equals(loaded.path("name").asText()) || id.equals(loaded.path("model").asText())))
                        continue;
                    if (digest != null && !digest.equals(loaded.path("digest").asText("")))
                        continue;
                    runtime = limit(loaded.path("context_length"));
                    break;
                }
            return new Model(id, context, null, null, runtime, digest, null);
        }
    };
}
