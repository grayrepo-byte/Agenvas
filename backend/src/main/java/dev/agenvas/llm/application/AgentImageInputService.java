package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checkpoints contain exact version references; authorized image bytes exist only during dispatch. */
@Service
public class AgentImageInputService {
    public static final String METADATA_KEY = "agentImageInputs";
    public static final String PREVIEW_REQUEST_KEY = "imagePreviewRequested";
    public static final int MAX_IMAGES = 8;
    public static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ObjectMapper mapper;

    public AgentImageInputService(ArtifactService artifacts, AssetService assets, ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.assets = assets;
        this.mapper = mapper;
    }

    /** Only successful, committed read_artifacts results can add images to the model history. */
    public void appendReadPreviews(List<Message> history, AssistantMessage assistant,
            Map<String, JsonNode> results) {
        var existing = new LinkedHashSet<Input>();
        for (Message message : history) {
            if (!(message instanceof UserMessage) || !message.getMetadata().containsKey(METADATA_KEY)) continue;
            for (JsonNode entry : mapper.valueToTree(message.getMetadata().get(METADATA_KEY))) {
                existing.add(new Input(uuid(entry, "artifactId"), uuid(entry, "versionId")));
            }
        }
        List<Input> added = new ArrayList<>();
        for (var call : assistant.getToolCalls()) {
            if (!"read_artifacts".equals(call.name())) continue;
            JsonNode result = results.get(call.id());
            if (result == null || !ToolResultStatus.SUCCEEDED.name().equals(result.path("status").asText())) continue;
            for (JsonNode item : result.path("data")) {
                if (!"IMAGE".equals(item.path("kind").asText())
                        || !item.path(PREVIEW_REQUEST_KEY).asBoolean()) continue;
                Input input = new Input(uuid(item, "artifactId"), uuid(item, "versionId"));
                if (existing.add(input)) added.add(input);
            }
        }
        if (!added.isEmpty()) history.add(UserMessage.builder()
                .text("Image previews requested by read_artifacts (exact immutable versions): " + added)
                .metadata(Map.of(METADATA_KEY, List.copyOf(added))).build());
    }

    /** Validate requested previews before opening any storage stream; never read unrequested assets. */
    public void validateInputs(UUID owner, UUID project, UUID run, JsonNode snapshot, List<Input> inputs) {
        if (inputs.size() > MAX_IMAGES) throw invalid();
        long total = 0;
        for (Input input : inputs) {
            Asset asset = image(owner, project, run, snapshot, input);
            Long size = asset.thumbnailByteSize();
            if (size == null || size <= 0 || size > MAX_IMAGE_BYTES) throw invalid();
            total += size;
            if (total > MAX_TOTAL_BYTES) throw invalid();
        }
    }

    /** Reauthorize saved references and load bounded previews outside all database transactions. */
    public List<Message> hydrate(UUID owner, UUID project, UUID run, JsonNode snapshot, List<Message> messages) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Agent image dispatch cannot hold a database transaction");
        }
        List<Input> requested = new ArrayList<>();
        for (Message message : messages) {
            if (!(message instanceof UserMessage) || !message.getMetadata().containsKey(METADATA_KEY)) continue;
            JsonNode manifest = mapper.valueToTree(message.getMetadata().get(METADATA_KEY));
            if (!manifest.isArray() || manifest.isEmpty()) throw invalid();
            for (JsonNode entry : manifest) {
                Input input = new Input(uuid(entry, "artifactId"), uuid(entry, "versionId"));
                if (requested.contains(input)) throw invalid();
                requested.add(input);
            }
        }
        if (requested.isEmpty()) return messages;
        validateInputs(owner, project, run, snapshot, requested);
        Map<Input, Media> media = new LinkedHashMap<>();
        for (Input input : requested) {
            Asset asset = image(owner, project, run, snapshot, input);
            AssetService.AssetContent content = assets.content(owner, project, asset.id(), true);
            if (content.size() <= 0 || content.size() > MAX_IMAGE_BYTES
                    || content.size() != asset.thumbnailByteSize()) throw invalid();
            try (InputStream stream = assets.open(content, 0, content.size())) {
                byte[] bytes = stream.readNBytes(Math.toIntExact(content.size()) + 1);
                if (bytes.length != content.size()) throw invalid();
                media.put(input, new Media(MimeTypeUtils.parseMimeType(content.contentType()), new ByteArrayResource(bytes)));
            } catch (IOException failed) {
                throw new IllegalStateException("Agent image preview could not be read", failed);
            }
        }
        List<Message> hydrated = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof UserMessage user) || !message.getMetadata().containsKey(METADATA_KEY)) {
                hydrated.add(message);
                continue;
            }
            List<Media> attachments = new ArrayList<>();
            for (JsonNode entry : mapper.valueToTree(message.getMetadata().get(METADATA_KEY))) {
                attachments.add(media.get(new Input(uuid(entry, "artifactId"), uuid(entry, "versionId"))));
            }
            Map<String, Object> metadata = new LinkedHashMap<>(user.getMetadata());
            metadata.remove(METADATA_KEY);
            hydrated.add(UserMessage.builder().text(user.getText()).metadata(metadata).media(attachments).build());
        }
        return List.copyOf(hydrated);
    }

    private Asset image(UUID owner, UUID project, UUID run, JsonNode snapshot, Input input) {
        var version = artifacts.requireAgentVisibleVersion(owner, project, run, input.versionId(), snapshot);
        if (!input.artifactId().equals(version.artifactId())) throw invalid();
        Asset asset = assets.metadata(owner, project, uuid(version.content(), "assetId"));
        if (asset.mediaKind() != Asset.MediaKind.IMAGE || !project.equals(asset.projectId())) throw invalid();
        return asset;
    }

    private static UUID uuid(JsonNode value, String field) {
        if (!value.path(field).isTextual()) throw invalid();
        try { return UUID.fromString(value.path(field).asText()); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private static ApiProblemException invalid() {
        var message = ApiMessage.of("api.agent-image-input-service.image-preview-input-invalid");
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "AGENT_IMAGE_INPUT_INVALID", message, message, false);
    }

    public record Input(UUID artifactId, UUID versionId) {}
}
