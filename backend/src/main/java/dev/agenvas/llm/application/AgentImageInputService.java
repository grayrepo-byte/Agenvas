package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.library.application.LibraryService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    public static final String SKILL_METADATA_KEY = "agentSkillImageInputs";
    public static final String PREVIEW_REQUEST_KEY = "imagePreviewRequested";
    public static final int MAX_IMAGES = 8;
    public static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ObjectMapper mapper;
    private final SkillService skills;
    private final ToolExecutionRepository ledger;

    public AgentImageInputService(ArtifactService artifacts, AssetService assets, ObjectMapper mapper,
            SkillService skills, ToolExecutionRepository ledger) {
        this.artifacts = artifacts;
        this.assets = assets;
        this.mapper = mapper;
        this.skills = skills;
        this.ledger = ledger;
    }

    /** Only successful committed reads add references; checkpoint messages never contain image bytes or storage keys. */
    public void appendReadPreviews(List<Message> history, AssistantMessage assistant,
            Map<String, JsonNode> results) {
        var existing = new LinkedHashSet<Input>();
        var existingSkills = new LinkedHashSet<SkillInput>();
        for (Message message : history) {
            if (!(message instanceof UserMessage)) continue;
            if (message.getMetadata().containsKey(METADATA_KEY))
                for (JsonNode entry : mapper.valueToTree(message.getMetadata().get(METADATA_KEY)))
                    existing.add(projectInput(entry));
            if (message.getMetadata().containsKey(SKILL_METADATA_KEY))
                for (JsonNode entry : mapper.valueToTree(message.getMetadata().get(SKILL_METADATA_KEY)))
                    existingSkills.add(skillInput(entry));
        }
        List<Input> added = new ArrayList<>();
        List<SkillInput> addedSkills = new ArrayList<>();
        for (var call : assistant.getToolCalls()) {
            JsonNode result = results.get(call.id());
            if (result == null || !ToolResultStatus.SUCCEEDED.name().equals(result.path("status").asText())) continue;
            if ("read_artifacts".equals(call.name())) for (JsonNode item : result.path("data")) {
                if (!"IMAGE".equals(item.path("kind").asText())
                        || !item.path(PREVIEW_REQUEST_KEY).asBoolean()) continue;
                Input input = projectInput(item);
                if (existing.add(input)) added.add(input);
            }
            if ("read_skill_asset".equals(call.name())) {
                JsonNode item = result.path("data");
                if (!"IMAGE".equals(item.path("kind").asText())
                        || !item.path(PREVIEW_REQUEST_KEY).asBoolean()) continue;
                SkillInput input = skillInput(item);
                if (existingSkills.add(input)) addedSkills.add(input);
            }
        }
        if (!added.isEmpty()) history.add(UserMessage.builder()
                .text("Image previews requested by read_artifacts (exact immutable versions): " + added)
                .metadata(Map.of(METADATA_KEY, List.copyOf(added))).build());
        if (!addedSkills.isEmpty()) history.add(UserMessage.builder()
                .text("Skill reference image previews requested by read_skill_asset (LLM context only): " + addedSkills)
                .metadata(Map.of(SKILL_METADATA_KEY, List.copyOf(addedSkills))).build());
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
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Agent image dispatch cannot hold a database transaction");
        List<Input> requested = new ArrayList<>();
        List<SkillInput> requestedSkills = new ArrayList<>();
        for (Message message : messages) {
            if (!(message instanceof UserMessage)) continue;
            if (message.getMetadata().containsKey(METADATA_KEY)) {
                for (JsonNode entry : manifest(message, METADATA_KEY)) {
                    Input input = projectInput(entry);
                    if (requested.contains(input)) throw invalid();
                    requested.add(input);
                }
            }
            if (message.getMetadata().containsKey(SKILL_METADATA_KEY)) {
                for (JsonNode entry : manifest(message, SKILL_METADATA_KEY)) {
                    SkillInput input = skillInput(entry);
                    if (requestedSkills.contains(input)) throw invalid();
                    requestedSkills.add(input);
                }
            }
        }
        if (requested.isEmpty() && requestedSkills.isEmpty()) return messages;
        if (requested.size() + requestedSkills.size() > MAX_IMAGES) throw invalid();
        validateInputs(owner, project, run, snapshot, requested);
        long total = 0;
        Map<Input, Asset> projectAssets = new LinkedHashMap<>();
        for (Input input : requested) {
            Asset asset = image(owner, project, run, snapshot, input);
            total += asset.thumbnailByteSize();
            projectAssets.put(input, asset);
        }
        List<JsonNode> skillReads = requestedSkills.isEmpty() ? List.of() : ledger.skillReads(project, run);
        Map<SkillInput, LibraryService.MediaFile> skillFiles = new LinkedHashMap<>();
        for (SkillInput input : requestedSkills) {
            LibraryService.MediaFile file = skillImage(owner, snapshot, input, skillReads);
            if (file.size() <= 0 || file.size() > MAX_IMAGE_BYTES) throw invalid();
            total += file.size();
            skillFiles.put(input, file);
        }
        if (total > MAX_TOTAL_BYTES) throw invalid();
        Map<Input, Media> media = new LinkedHashMap<>();
        for (var entry : projectAssets.entrySet()) {
            Asset asset = entry.getValue();
            AssetService.AssetContent content = assets.content(owner, project, asset.id(), true);
            if (content.size() <= 0 || content.size() > MAX_IMAGE_BYTES
                    || content.size() != asset.thumbnailByteSize()) throw invalid();
            try (InputStream stream = assets.open(content, 0, content.size())) {
                media.put(entry.getKey(), readMedia(stream, content.size(), content.contentType()));
            } catch (IOException failed) { throw unreadable(failed); }
        }
        Map<SkillInput, Media> skillMedia = new LinkedHashMap<>();
        for (var entry : skillFiles.entrySet()) {
            LibraryService.MediaFile file = entry.getValue();
            try (InputStream stream = Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS)) {
                skillMedia.put(entry.getKey(), readMedia(stream, file.size(), file.contentType()));
            } catch (IOException failed) { throw unreadable(failed); }
        }
        List<Message> hydrated = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof UserMessage user) || (!message.getMetadata().containsKey(METADATA_KEY)
                    && !message.getMetadata().containsKey(SKILL_METADATA_KEY))) {
                hydrated.add(message);
                continue;
            }
            List<Media> attachments = new ArrayList<>();
            if (message.getMetadata().containsKey(METADATA_KEY))
                for (JsonNode entry : manifest(message, METADATA_KEY)) attachments.add(media.get(projectInput(entry)));
            if (message.getMetadata().containsKey(SKILL_METADATA_KEY))
                for (JsonNode entry : manifest(message, SKILL_METADATA_KEY)) attachments.add(skillMedia.get(skillInput(entry)));
            Map<String, Object> metadata = new LinkedHashMap<>(user.getMetadata());
            metadata.remove(METADATA_KEY);
            metadata.remove(SKILL_METADATA_KEY);
            hydrated.add(UserMessage.builder().text(user.getText()).metadata(metadata).media(attachments).build());
        }
        return List.copyOf(hydrated);
    }

    /** Check frozen selection, committed activation/read, and the account-owned immutable publication. */
    private LibraryService.MediaFile skillImage(UUID owner, JsonNode snapshot, SkillInput input, List<JsonNode> reads) {
        JsonNode selected = null;
        for (JsonNode skill : snapshot.path("creativeSkills"))
            if (input.skillId().toString().equals(skill.path("skillId").asText())
                    && input.skillVersionId().toString().equals(skill.path("skillVersionId").asText())) selected = skill;
        if (selected == null || !SkillContent.AssetDelivery.LLM_CONTEXT.name().equals(selected.path("assetDelivery").asText()))
            throw invalid();
        boolean activated = reads.stream().anyMatch(read -> input.skillVersionId().toString().equals(read.path("skillVersionId").asText())
                && "SKILL.md".equals(read.path("path").asText()));
        boolean read = reads.stream().anyMatch(item -> input.skillId().toString().equals(item.path("skillId").asText())
                && input.skillVersionId().toString().equals(item.path("skillVersionId").asText())
                && input.alias().equals(item.path("alias").asText()) && input.contentHash().equals(item.path("contentHash").asText())
                && "IMAGE".equals(item.path("kind").asText()) && item.path(PREVIEW_REQUEST_KEY).asBoolean());
        if (!activated || !read) throw invalid();
        boolean frozen = false;
        for (JsonNode asset : selected.path("assets"))
            if (input.alias().equals(asset.path("alias").asText()) && input.contentHash().equals(asset.path("contentHash").asText())
                    && "IMAGE".equals(asset.path("kind").asText())) frozen = true;
        if (!frozen) throw invalid();
        SkillContent.Version version = skills.getBundle(owner, input.skillId(), input.skillVersionId());
        if (!owner.equals(version.ownerId()) || !input.skillId().equals(version.skillId()) || !input.skillVersionId().equals(version.id()))
            throw invalid();
        SkillContent.PublishedAsset asset = version.bundle().assets().stream()
                .filter(item -> input.alias().equals(item.alias())).findFirst().orElseThrow(AgentImageInputService::invalid);
        if (asset.kind() != dev.agenvas.artifact.domain.Artifact.Kind.IMAGE || !input.contentHash().equals(asset.contentHash())
                || asset.media().kind() != Asset.MediaKind.IMAGE || !owner.equals(asset.media().ownerId())
                || !input.contentHash().equals(asset.media().sha256())) throw invalid();
        return skills.file(owner, input.skillId(), input.skillVersionId(), input.alias(), true);
    }

    private JsonNode manifest(Message message, String key) {
        JsonNode manifest = mapper.valueToTree(message.getMetadata().get(key));
        if (!manifest.isArray() || manifest.isEmpty()) throw invalid();
        return manifest;
    }
    private static Input projectInput(JsonNode entry) {
        return new Input(uuid(entry, "artifactId"), uuid(entry, "versionId"));
    }
    private static SkillInput skillInput(JsonNode entry) {
        return new SkillInput(uuid(entry, "skillId"), uuid(entry, "skillVersionId"), text(entry, "alias"), text(entry, "contentHash"));
    }
    private static String text(JsonNode entry, String field) {
        if (!entry.path(field).isTextual() || entry.path(field).asText().isBlank()) throw invalid();
        return entry.path(field).asText();
    }
    private static Media readMedia(InputStream stream, long size, String contentType) throws IOException {
        if (!contentType.startsWith("image/")) throw invalid();
        byte[] bytes = stream.readNBytes(Math.toIntExact(size) + 1);
        if (bytes.length != size) throw invalid();
        return new Media(MimeTypeUtils.parseMimeType(contentType), new ByteArrayResource(bytes));
    }
    private static IllegalStateException unreadable(IOException failed) {
        return new IllegalStateException("Agent image preview could not be read", failed);
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
    public record SkillInput(UUID skillId, UUID skillVersionId, String alias, String contentHash) {}
}
