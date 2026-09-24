package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 提供受所有者与 Run 范围约束的读取工具，并限制批量大小及内联内容字节数。 */
@Service
public class ReadToolService {

    /** 一次读取最多返回的产物版本数，限制查询与模型上下文膨胀。 */
    private static final int MAX_READ_VERSIONS = 12;
    /** 一次读取最多检查的任务数。 */
    private static final int MAX_READ_TASKS = 12;
    /** 单个版本允许完整内联到工具结果中的 UTF-8 字节上限。 */
    private static final int MAX_INLINE_BYTES = 24 * 1024;
    /** 单次工具调用全部完整内联内容的累计上限。 */
    private static final int MAX_TOTAL_INLINE_BYTES = 160 * 1024;
    /** 超出内联上限时，预览文本按 Unicode 码点截取的字符数。 */
    private static final int PREVIEW_CHARS = 2_000;

    /** 读取项目元数据时检查请求用户的项目权限。 */
    private final ProjectService projects;
    /** 只读取当前 Run 获准访问的 Artifact 与版本。 */
    private final ArtifactService artifacts;
    /** 查询任务时同时核验用户和项目作用域。 */
    private final TaskService tasks;
    /** 构造具有稳定结果结构的 JSON 工具响应。 */
    private final ObjectMapper mapper;

    /** 组装项目元数据、产物版本和任务状态三类受限读取能力。 */
    public ReadToolService(ProjectService projects, ArtifactService artifacts, TaskService tasks,
            ObjectMapper mapper) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    /** 读取项目公开元数据与服务端固定限制，不读取凭证，也不枚举所有画布项。 */
    public JsonNode projectSummary(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        if (!input.isEmpty()) {
            throw invalid("read_project_summary takes no arguments");
        }
        Project project = projects.get(context.ownerId(), context.projectId());
        ObjectNode output = result(operationId, "已读取项目概要");
        ObjectNode data = output.putObject("data");
        data.put("name", project.name());
        data.put("aspectRatio", project.aspectRatio().name());
        data.put("status", project.status().name());
        ObjectNode limits = data.putObject("runLimits");
        for (String field : new String[] {"maxModelTurns", "maxToolExecutions",
                "maxImages", "maxVideos", "maxShots"}) {
            JsonNode value = run.policySnapshot().path(field);
            if (!value.isIntegralNumber() || value.intValue() < 0) {
                throw new IllegalStateException("Run policy snapshot is malformed");
            }
            limits.put(field, value.intValue());
        }
        return output;
    }

    /** 返回 Run 创建时记录的界面选择供理解意图；该快照不能作为写入授权。 */
    public JsonNode selection(AgentRun run, UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        if (!input.isEmpty()) {
            throw invalid("read_selection takes no arguments");
        }
        JsonNode selected = run.contextSnapshot().path("selection");
        if (!selected.isMissingNode() && (!selected.isArray()
                || selected.size() > 20)) {
            throw new IllegalStateException("Run selection snapshot is malformed");
        }
        ObjectNode output = result(operationId, "已读取运行开始时选中的画布卡片");
        output.set("data", selected.isMissingNode()
                ? mapper.createArrayNode() : selected.deepCopy());
        return output;
    }

    /** 仅读取显式绑定或本 Run 创建的版本，并限制完整内联内容的大小。 */
    public JsonNode artifacts(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        if (input.size() != 1 || !input.has("versionIds")) {
            throw invalid("read_artifacts requires only versionIds");
        }
        JsonNode requested = input.path("versionIds");
        if (!requested.isArray() || requested.isEmpty()
                || requested.size() > MAX_READ_VERSIONS) {
            throw invalid("read_artifacts requires one to twelve version IDs");
        }
        List<UUID> versionIds = validatedIds(requested, "read_artifacts");
        ObjectNode output = result(operationId, "已读取允许范围内的产物版本");
        ArrayNode items = output.putArray("data");
        int inlineBytes = 0;
        for (UUID versionId : versionIds) {
            ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                    context.projectId(), context.runId(), versionId, run.contextSnapshot());
            ArtifactService.ArtifactView view = artifacts.get(context.ownerId(),
                    context.projectId(), version.artifactId());
            ObjectNode item = items.addObject();
            item.put("artifactId", version.artifactId().toString());
            item.put("versionId", version.id().toString());
            item.put("kind", view.artifact().kind().name());
            boolean current = view.currentVersion().id().equals(versionId);
            item.put("current", current);
            if (current) {
                item.put("title", view.artifact().title());
            }
            boolean mutableInRun = context.runId().equals(version.runId());
            if (current && (mutableInRun || unchangedBinding(run, version,
                    view.artifact().version()))) {
                item.put("expectedVersion", view.artifact().version());
            }
            String content = version.content().toString();
            int size = content.getBytes(StandardCharsets.UTF_8).length;
            if (size <= MAX_INLINE_BYTES && inlineBytes + size <= MAX_TOTAL_INLINE_BYTES) {
                item.set("content", version.content().deepCopy());
                item.put("contentTruncated", false);
                inlineBytes += size;
            } else {
                int previewCodePoints = Math.min(content.codePointCount(0, content.length()),
                        PREVIEW_CHARS);
                item.put("contentPreview", content.substring(0,
                        content.offsetByCodePoints(0, previewCodePoints)));
                item.put("contentTruncated", true);
            }
        }
        return output;
    }

    /** 返回本 Run 任务的有限状态快照，不包含 Provider 内部数据。 */
    public JsonNode taskStatus(TrustedToolContext context, UUID operationId, String arguments) {
        ObjectNode input = parseObject(arguments);
        if (input.size() != 1 || !input.has("taskIds")) {
            throw invalid("read_task_status requires only taskIds");
        }
        JsonNode requested = input.path("taskIds");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > MAX_READ_TASKS) {
            throw invalid("read_task_status requires one to twelve task IDs");
        }
        List<UUID> taskIds = validatedIds(requested, "read_task_status");
        ObjectNode output = result(operationId, "已读取本次运行的任务状态");
        ArrayNode items = output.putArray("data");
        for (UUID taskId : taskIds) {
            Task task = tasks.get(context.ownerId(), context.projectId(), taskId);
            if (!context.runId().equals(task.runId())) {
                throw new ApiProblemException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND",
                        "任务不存在", "Task is not accessible to this Run", false);
            }
            ObjectNode item = items.addObject();
            item.put("taskId", task.id().toString());
            item.put("kind", task.kind().name());
            item.put("status", task.status().name());
            item.put("cancelRequested", task.cancelRequested());
            item.put("attemptNo", task.attemptNo());
            if (task.errorCode() != null) {
                item.put("errorCode", task.errorCode());
            }
            item.put("updatedAt", task.updatedAt().toString());
            if (task.completedAt() != null) {
                item.put("completedAt", task.completedAt().toString());
            }
        }
        return output;
    }

    /** 绑定的历史版本不能为用户后来选定的新版本提供 CAS 令牌。 */
    private boolean unchangedBinding(AgentRun run, ArtifactVersion version, long artifactVersion) {
        JsonNode bindings = run.contextSnapshot().path("bindings");
        if (!bindings.isArray()) return false;
        for (JsonNode binding : bindings) {
            if (version.artifactId().toString().equals(binding.path("artifactId").asText())
                    && version.id().toString().equals(
                            binding.path("selectedVersionId").asText())
                    && binding.path("expectedVersion").canConvertToLong()
                    && binding.path("expectedVersion").longValue() == artifactVersion) {
                return true;
            }
        }
        return false;
    }

    /** 构造成功读取工具共用的空变更结果结构。 */
    private ObjectNode result(UUID operationId, String summary) {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds");
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", summary);
        return result;
    }

    /** 将原始工具参数解析为 JSON 对象，并把语法或根类型错误映射为参数错误。 */
    private ObjectNode parseObject(String arguments) {
        JsonNode value;
        try {
            value = mapper.readTree(arguments);
        } catch (RuntimeException malformed) {
            throw invalid("Read tool arguments are not valid JSON");
        }
        if (!(value instanceof ObjectNode object)) {
            throw invalid("Read tool arguments must be an object");
        }
        return object;
    }

    /** 只接受 UUID 文本节点，避免 Jackson 对数字等类型进行宽松转换。 */
    private UUID uuid(JsonNode value) {
        if (!value.isTextual()) {
            throw invalid("IDs must contain UUID strings");
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException malformed) {
            throw invalid("IDs must contain UUID strings");
        }
    }

    /** 在任何项目范围查询前拒绝格式错误或重复的 ID。 */
    private List<UUID> validatedIds(JsonNode requested, String toolName) {
        Set<UUID> unique = new HashSet<>();
        List<UUID> ids = new ArrayList<>(requested.size());
        for (JsonNode supplied : requested) {
            UUID id = uuid(supplied);
            if (!unique.add(id)) {
                throw invalid(toolName + " IDs must be unique");
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** 构造读取工具输入不符合契约时的 400 问题响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                "工具参数无效", detail, false);
    }
}
