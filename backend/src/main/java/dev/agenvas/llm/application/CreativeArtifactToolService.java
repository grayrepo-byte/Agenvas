package dev.agenvas.llm.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 实现受限的产物修订与画布布局工具：校验 Run 可见范围后调用产物与画布应用服务。 */
@Service
public class CreativeArtifactToolService {

    /** Agent 修订请求允许字段，预期版本用于保护并发编辑。 */
    private static final Set<String> REVISE_FIELDS = Set.of(
            "artifactId", "expectedVersion", "title", "content");

    /** 按预期版本修订产物并校验 Agent 可见版本范围。 */
    private final ArtifactService artifacts;
    /** 在业务画布中创建或排列 Agent 输出卡片。 */
    private final CanvasService canvas;
    /** 解析工具参数并组装统一结果结构。 */
    private final ObjectMapper mapper;

    /** 连接产物领域校验、画布投影和工具 JSON 编解码。 */
    public CreativeArtifactToolService(ArtifactService artifacts, CanvasService canvas,
            ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.canvas = canvas;
        this.mapper = mapper;
    }

    /** 按预期版本完整修订产物，并由服务端确认目标处于当前 Run 的授权范围。 */
    public JsonNode reviseArtifact(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, REVISE_FIELDS);
        UUID artifactId = parseUuid(input.path("artifactId"));
        JsonNode version = input.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong()
                || version.longValue() < 0) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.expectedversion-must-be-a-nonnegative-integer"));
        }
        String title = input.has("title") ? requiredText(input, "title", 160) : null;
        JsonNode content = input.path("content");
        if (!content.isObject()) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.a-complete-content-object-is-required"));
        }
        ArtifactService.ArtifactView revised = artifacts.reviseFromAgent(context.ownerId(),
                context.projectId(), context.runId(), run.contextSnapshot(), artifactId,
                version.longValue(), title, content);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", ToolResultStatus.SUCCEEDED.name());
        result.put("operationId", operationId.toString());
        result.putArray("createdIds");
        result.putArray("updatedIds").add(artifactId.toString());
        result.putObject("affectedVersions").put(artifactId.toString(),
                revised.resourceDefaultVersion().id().toString());
        result.putObject("artifactVersions").put(artifactId.toString(),
                revised.artifact().version());
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已创建产物的新内容版本");
        return result;
    }

    /** 将新产物写入 Agent 持久化输出分组，使其成为业务画布项。 */
    public void placeOutputs(TrustedToolContext context, AgentRun run,
            List<ArtifactService.ArtifactView> created) {
        JsonNode group = run.contextSnapshot().path("outputGroupId");
        UUID groupId = parseUuid(group);
        BigDecimal rightEdge = canvas.list(context.ownerId(), context.projectId()).stream()
                .map(entry -> entry.item().x().add(entry.item().width()))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal x = rightEdge.add(new BigDecimal("80"));
        List<CanvasService.PlaceArtifact> commands = new ArrayList<>();
        for (int index = 0; index < created.size(); index++) {
            ArtifactService.ArtifactView view = created.get(index);
            commands.add(new CanvasService.PlaceArtifact(UUID.randomUUID(), view.artifact().id(),
                    x, BigDecimal.valueOf(index * 240L), new BigDecimal("320"),
                    new BigDecimal("200"), index, groupId, false));
        }
        canvas.apply(context.ownerId(), context.projectId(), commands);
    }

    /** 复用本 Run 可见且仍选用精确结果的媒体输出卡片；新建卡片与文字按资源默认版本校验。 */
    public JsonNode placeArtifacts(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, Set.of("versionIds", "group"));
        if (!"AGENT_OUTPUT".equals(requiredText(input, "group", 20))) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.only-the-agent-output-group-is-allowed"));
        }
        JsonNode requested = input.path("versionIds");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > 6) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.place-artifacts-requires-one-to-six-version-ids"));
        }
        List<UUID> versionIds = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (JsonNode supplied : requested) {
            UUID versionId = parseUuid(supplied);
            if (!unique.add(versionId)) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.place-artifacts-version-ids-must-be-unique"));
            }
            versionIds.add(versionId);
        }
        List<UUID> artifactIds = new ArrayList<>();
        Set<UUID> uniqueArtifacts = new HashSet<>();
        UUID outputGroupId = parseUuid(run.contextSnapshot().path("outputGroupId"));
        List<CanvasService.CanvasEntry> existing = canvas.list(context.ownerId(),
                context.projectId());
        for (UUID versionId : versionIds) {
            ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), versionId, run.contextSnapshot());
            ArtifactService.ArtifactView view = artifacts.get(context.ownerId(),
                    context.projectId(), version.artifactId());
            CanvasItem output = existing.stream().map(CanvasService.CanvasEntry::item)
                    .filter(item -> item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                            && item.subjectId().equals(version.artifactId())
                            && outputGroupId.equals(item.groupId()))
                    .findFirst().orElse(null);
            if (view.artifact().archivedAt() != null
                    || !isCurrentCardVersion(view, output, versionId)) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "ARTIFACT_VERSION_CONFLICT", ApiMessage.of("api.creative-artifact-tool-service.product-version-has-changed"),
                        ApiMessage.of("api.creative-artifact-tool-service.the-output-card-can-only-point-to-the-currently-selected"), false);
            }
            if (!uniqueArtifacts.add(version.artifactId())) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.place-artifacts-must-not-repeat-an-artifact"));
            }
            artifactIds.add(version.artifactId());
        }
        CanvasService.OutputPlacements placed = canvas.placeArtifactsInAgentOutputWithinChange(
                context.ownerId(), context.projectId(), run.agentInstanceId(), artifactIds);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", ToolResultStatus.SUCCEEDED.name());
        result.put("operationId", operationId.toString());
        ArrayNode createdIds = result.putArray("createdIds");
        placed.created().forEach(item -> createdIds.add(item.id().toString()));
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已将允许的产物放入 Agent 输出分组");
        ArrayNode items = result.putArray("data");
        for (int index = 0; index < artifactIds.size(); index++) {
            UUID artifactId = artifactIds.get(index);
            CanvasItem item = placed.created().stream()
                    .filter(card -> card.subjectId().equals(artifactId))
                    .findFirst().orElseGet(() -> placed.alreadyPresent().stream()
                            .filter(card -> card.subjectId().equals(artifactId))
                            .findFirst().orElseThrow());
            ObjectNode mapping = items.addObject();
            mapping.put("artifactId", artifactId.toString());
            mapping.put("versionId", versionIds.get(index).toString());
            mapping.put("itemId", item.id().toString());
            mapping.put("itemVersion", item.version());
            mapping.put("created", placed.created().contains(item));
        }
        return result;
    }

    /** 以每张卡片的预期版本更新布局，且只允许排列本 Run 输出分组中的卡片。 */
    public JsonNode arrangeItems(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        allowOnly(input, Set.of("items", "layout"));
        String layout = requiredText(input, "layout", 20);
        if (!Set.of("HORIZONTAL", "VERTICAL", "GRID").contains(layout)) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-layout-is-unsupported"));
        }
        JsonNode requested = input.path("items");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > 6) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-requires-one-to-six-items"));
        }
        List<ArrangeRequest> commands = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (JsonNode supplied : requested) {
            if (!(supplied instanceof ObjectNode object)
                    || object.size() != 3
                    || !object.has("itemId") || !object.has("versionId")
                    || !object.has("expectedVersion")) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-requires-itemid-versionid-and-expectedversion"));
            }
            UUID itemId = parseUuid(object.path("itemId"));
            UUID versionId = parseUuid(object.path("versionId"));
            JsonNode expected = object.path("expectedVersion");
            if (!expected.isIntegralNumber() || !expected.canConvertToLong()
                    || expected.longValue() < 0 || !unique.add(itemId)) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-has-an-invalid-or-duplicate-item"));
            }
            commands.add(new ArrangeRequest(itemId, versionId, expected.longValue()));
        }
        UUID outputGroupId = parseUuid(run.contextSnapshot().path("outputGroupId"));
        List<CanvasService.CanvasEntry> before = canvas.list(context.ownerId(),
                context.projectId());
        List<CanvasItem> selected = new ArrayList<>();
        for (ArrangeRequest request : commands) {
            CanvasItem item = before.stream().map(CanvasService.CanvasEntry::item)
                    .filter(candidate -> candidate.id().equals(request.itemId()))
                    .findFirst().orElseThrow(() -> new ApiProblemException(
                            HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                            ApiMessage.of("api.creative-artifact-tool-service.canvas-card-does-not-exist"), ApiMessage.of("api.creative-artifact-tool-service.the-target-card-does-not-belong-to-the-current-project"), false));
            if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT
                    || !outputGroupId.equals(item.groupId())) {
                throw new ApiProblemException(HttpStatus.FORBIDDEN, "INPUT_SCOPE_DENIED",
                        ApiMessage.of("api.creative-artifact-tool-service.canvas-card-exceeds-authorization-scope"), ApiMessage.of("api.creative-artifact-tool-service.only-the-product-cards-of-this-agent-s-output-group"), false);
            }
            if (item.locked()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED",
                        ApiMessage.of("api.creative-artifact-tool-service.card-locked"), ApiMessage.of("api.creative-artifact-tool-service.locked-cards-cannot-be-arranged-by-the-agent"), false);
            }
            ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), request.versionId(),
                    run.contextSnapshot());
            if (!version.artifactId().equals(item.subjectId())) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-version-does-not-match-its-card"));
            }
            ArtifactService.ArtifactView view = artifacts.get(context.ownerId(),
                    context.projectId(), item.subjectId());
            if (view.artifact().archivedAt() != null
                    || !isCurrentCardVersion(view, item, request.versionId())) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "ARTIFACT_VERSION_CONFLICT", ApiMessage.of("api.creative-artifact-tool-service.product-version-has-changed"),
                        ApiMessage.of("api.creative-artifact-tool-service.old-content-versions-do-not-determine-the-current-card-layout"), false);
            }
            selected.add(item);
        }
        BigDecimal rightEdge = before.stream().map(CanvasService.CanvasEntry::item)
                .filter(item -> !unique.contains(item.id()))
                .map(item -> item.x().add(item.width()))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal baseX = rightEdge.add(new BigDecimal("80"));
        BigDecimal widthStep = selected.stream().map(CanvasItem::width)
                .max(BigDecimal::compareTo).orElseThrow().add(new BigDecimal("32"));
        BigDecimal heightStep = selected.stream().map(CanvasItem::height)
                .max(BigDecimal::compareTo).orElseThrow().add(new BigDecimal("32"));
        List<CanvasService.UpdateLayout> updates = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            CanvasItem item = selected.get(index);
            int column = switch (layout) {
                case "HORIZONTAL" -> index;
                case "GRID" -> index % 3;
                default -> 0;
            };
            int row = switch (layout) {
                case "VERTICAL" -> index;
                case "GRID" -> index / 3;
                default -> 0;
            };
            updates.add(new CanvasService.UpdateLayout(item.id(),
                    commands.get(index).expectedVersion(),
                    baseX.add(widthStep.multiply(BigDecimal.valueOf(column))),
                    heightStep.multiply(BigDecimal.valueOf(row)),
                    item.width(), item.height(), item.zIndex(), item.groupId()));
        }
        List<CanvasService.CanvasEntry> after = canvas.apply(context.ownerId(),
                context.projectId(), updates);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", ToolResultStatus.SUCCEEDED.name());
        result.put("operationId", operationId.toString());
        result.putArray("createdIds");
        ArrayNode updatedIds = result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已排列 Agent 输出卡片");
        ArrayNode data = result.putArray("data");
        for (CanvasItem old : selected) {
            CanvasItem current = after.stream().map(CanvasService.CanvasEntry::item)
                    .filter(item -> item.id().equals(old.id()))
                    .findFirst().orElseThrow();
            if (current.version() != old.version()) {
                updatedIds.add(current.id().toString());
            }
            ObjectNode item = data.addObject();
            item.put("itemId", current.id().toString());
            item.put("itemVersion", current.version());
            item.put("x", current.x());
            item.put("y", current.y());
        }
        return result;
    }

    /**
     * 媒体已有卡片按自身选用结果校验；没有卡片时只能从明确的资源默认版本创建。
     * 文字卡片继续展示资源默认版本，媒体生成归档不必设置该默认版本。
     */
    private boolean isCurrentCardVersion(ArtifactService.ArtifactView view, CanvasItem item,
            UUID versionId) {
        if (view.artifact().kind() != Artifact.Kind.TEXT && item != null) {
            return versionId.equals(item.selectedVersionId());
        }
        ArtifactVersion resourceDefault = view.resourceDefaultVersion();
        return resourceDefault != null && versionId.equals(resourceDefault.id());
    }

    /** 已校验的单张卡片布局请求，在批量画布更新前保持不可变。
     * @param itemId 要移动或调整尺寸的画布项
     * @param versionId 项目产物卡片当前展示的内容版本；Agent 卡片时为空
     * @param expectedVersion 写入前必须匹配的画布布局版本
     */
    private record ArrangeRequest(UUID itemId, UUID versionId, long expectedVersion) {}

    /** 解析工具 JSON 对象；语法错误和非对象根节点均转换为稳定参数错误。 */
    private ObjectNode parseObject(String arguments) {
        JsonNode node;
        try {
            node = mapper.readTree(arguments);
        } catch (RuntimeException exception) {
            throw invalid(ApiMessage.of("api.tool-execution-service.tool-arguments-are-not-valid-json"));
        }
        if (!(node instanceof ObjectNode object)) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.tool-arguments-must-be-an-object"));
        }
        return object;
    }

    /** 拒绝工具契约之外的属性，确保后续逻辑只消费显式校验过的字段。 */
    private void allowOnly(ObjectNode input, Set<String> allowed) {
        for (String field : input.propertyNames()) {
            if (!allowed.contains(field)) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.tool-arguments-contain-an-unknown-field"));
            }
        }
    }

    /** 读取非空且不超过指定长度的文本值。 */
    private String requiredText(JsonNode input, String field, int maximum) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximum) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.tool-argument-is-invalid", field));
        }
        return value.asText();
    }

    /** 将 JSON 字符串解析为 UUID，阻止数字或其他节点被宽松强转。 */
    private UUID parseUuid(JsonNode value) {
        if (!value.isTextual()) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.reference-version-id-must-be-a-uuid-string"));
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw invalid(ApiMessage.of("api.creative-artifact-tool-service.reference-version-id-must-be-a-uuid-string"));
        }
    }

    /** 构造创作工具参数或引用校验失败时使用的 400 响应。 */
    private ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), detail, false);
    }
}
