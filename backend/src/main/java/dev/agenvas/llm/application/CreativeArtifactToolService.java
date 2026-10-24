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

/** 实现受限的产物修订与画布布局工具：核对当前画布后调用产物与画布应用服务。 */
@Service
public class CreativeArtifactToolService {

    /** Agent 修订请求允许字段，预期版本用于保护并发编辑。 */
    private static final Set<String> REVISE_FIELDS = Set.of(
            "artifactId", "expectedVersion", "title", "content");

    /** 按预期版本修订当前画布文字产物。 */
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

    /** 按预期版本完整修订产物，并由服务端确认目标处于当前画布。 */
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
                context.projectId(), context.runId(), artifactId,
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

    /** 新产物独立放置在当前画布。 */
    public List<CanvasItem> placeOutputs(TrustedToolContext context, AgentRun run,
            List<ArtifactService.ArtifactView> created) {
        var placed = canvas.placeArtifactsWithinChange(context.ownerId(), context.projectId(),
                created.stream().map(view -> view.artifact().id()).toList(), null);
        return java.util.stream.Stream.concat(placed.created().stream(), placed.alreadyPresent().stream()).toList();
    }

    /** 以每张当前画布卡片的预期版本更新布局。 */
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
                    || object.size() < 2 || object.size() > 3
                    || object.propertyNames().stream().anyMatch(field -> !Set.of("itemId", "versionId", "expectedVersion").contains(field))
                    || !object.has("itemId")
                    || !object.has("expectedVersion")) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-requires-itemid-versionid-and-expectedversion"));
            }
            UUID itemId = parseUuid(object.path("itemId"));
            UUID versionId = object.has("versionId") ? parseUuid(object.path("versionId")) : null;
            JsonNode expected = object.path("expectedVersion");
            if (!expected.isIntegralNumber() || !expected.canConvertToLong()
                    || expected.longValue() < 0 || !unique.add(itemId)) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-has-an-invalid-or-duplicate-item"));
            }
            commands.add(new ArrangeRequest(itemId, versionId, expected.longValue()));
        }
        List<CanvasService.CanvasEntry> before = canvas.list(context.ownerId(),
                context.projectId());
        List<CanvasItem> selected = new ArrayList<>();
        for (ArrangeRequest request : commands) {
            CanvasService.CanvasEntry entry = before.stream()
                    .filter(candidate -> candidate.item().id().equals(request.itemId()))
                    .findFirst().orElseThrow(() -> new ApiProblemException(
                            HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                            ApiMessage.of("api.creative-artifact-tool-service.canvas-card-does-not-exist"), ApiMessage.of("api.creative-artifact-tool-service.the-target-card-does-not-belong-to-the-current-project"), false));
            CanvasItem item = entry.item();
            ArtifactService.ArtifactView view = artifacts.get(context.ownerId(), context.projectId(), item.subjectId());
            if (item.subjectType() != CanvasItem.SubjectType.ARTIFACT) {
                throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-version-does-not-match-its-card"));
            }
            if (item.locked()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_ITEM_LOCKED",
                        ApiMessage.of("api.creative-artifact-tool-service.card-locked"), ApiMessage.of("api.creative-artifact-tool-service.locked-cards-cannot-be-arranged-by-the-agent"), false);
            }
            boolean emptyMedia = request.versionId() == null
                    && view.artifact().kind() != Artifact.Kind.TEXT && item.selectedVersionId() == null;
            if (!emptyMedia) {
                if (request.versionId() == null) throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-version-does-not-match-its-card"));
                ArtifactVersion version = artifacts.requireCanvasVersion(context.ownerId(), context.projectId(), request.versionId());
                if (!version.artifactId().equals(item.subjectId())) {
                    throw invalid(ApiMessage.of("api.creative-artifact-tool-service.arrange-items-version-does-not-match-its-card"));
                }
            }
            if (view.artifact().archivedAt() != null
                    || (!emptyMedia && !isCurrentCardVersion(view, item, request.versionId()))) {
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
        result.put("userVisibleSummary", "已排列画布节点");
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
     * 媒体卡片按自身当前选用结果校验，文字卡片按资源默认版本校验。
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
     * @param versionId 项目产物卡片当前展示的内容版本；空媒体草稿时为空
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
