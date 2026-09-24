package dev.agenvas.export.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 将 Agent 提出的导出内容保存为待审提案，只有用户审批后才创建导出任务。 */
@Service
public class ExportProposalService {

    /** 模型可提交的提案顶层字段；其他属性一律拒绝。 */
    private static final Set<String> FIELDS = Set.of("aspectRatio", "segments");
    /** 每个片段可声明的素材 ID、版本和裁剪区间字段白名单。 */
    private static final Set<String> SEGMENT_FIELDS = Set.of("shotArtifactId",
            "shotVersionId", "videoArtifactId", "videoVersionId", "startMs", "endMs");
    /** 检查项目归属、活动状态和当前项目版本。 */
    private final ProjectService projects;
    /** 验证建议来自仍在运行且作用域匹配的 Agent Run。 */
    private final AgentRunService runs;
    /** 校验镜头与视频版本权限，并读取用户选定的当前版本。 */
    private final ArtifactService artifacts;
    /** 预览分段、裁剪范围和项目画幅，且不创建导出任务。 */
    private final MediaExportService exports;
    /** 持久化提案并以行锁串行处理用户审批决定。 */
    private final ExportProposalRepository proposals;
    /** 只在审批后创建任务，并检查重复审批对应的既有任务。 */
    private final TaskService tasks;
    /** 与提案状态和任务变更一起追加项目事件。 */
    private final ProjectEventService events;
    /** 生成提案输入快照及校验后的事件 JSON。 */
    private final ObjectMapper mapper;
    /** 生成提案和用户决定时间戳。 */
    private final Clock clock;

    /** 组合权限、产物版本、导出预览与持久任务服务。 */
    public ExportProposalService(ProjectService projects, AgentRunService runs,
            ArtifactService artifacts,
            MediaExportService exports, ExportProposalRepository proposals, TaskService tasks,
            ProjectEventService events, ObjectMapper mapper, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.artifacts = artifacts;
        this.exports = exports;
        this.proposals = proposals;
        this.tasks = tasks;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 保存数量受限且绑定 Run 的导出建议；此阶段不创建媒体任务或启动 FFmpeg。 */
    @Transactional
    public ExportProposal propose(TrustedToolContext context, AgentRun run, JsonNode input) {
        return events.recordChange(context.ownerId(), context.projectId(), () -> {
            ExportProposal proposal = proposeLocked(context, run, input);
            proposals.create(proposal);
            return ProjectEventService.Change.changed(proposal, event(proposal));
        }).value();
    }

    /** 获取项目事件锁后重新解析并校验所有可变输入。 */
    private ExportProposal proposeLocked(TrustedToolContext context, AgentRun run,
            JsonNode input) {
        projects.requireActiveProject(context.ownerId(), context.projectId());
        AgentRun currentRun = runs.get(context.ownerId(), context.projectId(), context.runId());
        if (!context.runId().equals(run.id()) || !context.projectId().equals(run.projectId())
                || !context.ownerId().equals(run.userId())
                || currentRun.status() != AgentRun.Status.RUNNING
                || currentRun.contextSnapshot().has("redoShotArtifactId")) {
            throw conflict("Run cannot propose a project export");
        }
        if (input == null || !input.isObject()) {
            throw invalid("Export proposal must be an object");
        }
        requireOnly(input, FIELDS);
        String aspectRatio = text(input, "aspectRatio");
        if (!Set.of("LANDSCAPE_16_9", "PORTRAIT_9_16", "SQUARE_1_1")
                .contains(aspectRatio)) {
            throw invalid("Unsupported export aspect ratio");
        }
        JsonNode supplied = input.path("segments");
        if (!supplied.isArray() || supplied.isEmpty() || supplied.size() > 6) {
            throw invalid("Export proposal needs one to six ordered segments");
        }
        List<MediaExportService.SegmentRequest> segments = new ArrayList<>();
        List<UUID> shotIds = new ArrayList<>();
        List<UUID> shotVersionIds = new ArrayList<>();
        ArrayNode pins = mapper.createArrayNode();
        Set<UUID> seenShots = new HashSet<>();
        for (JsonNode segment : supplied) {
            if (!segment.isObject()) throw invalid("Each export segment must be an object");
            requireOnly(segment, SEGMENT_FIELDS);
            UUID shotId = uuid(segment, "shotArtifactId");
            UUID shotVersionId = uuid(segment, "shotVersionId");
            UUID videoId = uuid(segment, "videoArtifactId");
            UUID videoVersionId = uuid(segment, "videoVersionId");
            if (!seenShots.add(shotId)) throw invalid("Each shot may appear only once");
            shotIds.add(shotId);
            shotVersionIds.add(shotVersionId);
            pin(context, currentRun, shotId, shotVersionId, Artifact.Kind.SHOT, pins);
            pin(context, currentRun, videoId, videoVersionId, Artifact.Kind.VIDEO, pins);
            int startMs = millisecond(segment, "startMs");
            int endMs = millisecond(segment, "endMs");
            segments.add(new MediaExportService.SegmentRequest(videoId, videoVersionId,
                    startMs, endMs));
        }
        MediaExportService.ExportPreview preview;
        try {
            preview = exports.preview(context.ownerId(), context.projectId(), segments);
        } catch (ApiProblemException invalidExport) {
            if ("EXPORT_INPUT_INVALID".equals(invalidExport.code())) {
                throw invalid("Export segment range or media is invalid");
            }
            throw invalidExport;
        }
        if (!aspectRatio.equals(preview.inputSnapshot().path("aspectRatio").asText())) {
            throw invalid("Proposed aspect ratio differs from project settings");
        }
        ObjectNode proposedInput = ((ObjectNode) preview.inputSnapshot()).deepCopy();
        for (int index = 0; index < shotIds.size(); index++) {
            ObjectNode selected = (ObjectNode) proposedInput.path("segments").get(index);
            selected.put("shotArtifactId", shotIds.get(index).toString());
            selected.put("shotVersionId", shotVersionIds.get(index).toString());
        }
        String hash = sha256(proposedInput + "\n" + pins + "\n"
                + preview.projectVersion());
        ExportProposal proposal = new ExportProposal(UUID.randomUUID(), context.projectId(),
                context.runId(), ExportProposal.Status.PENDING, proposedInput, pins,
                hash, preview.projectVersion(), null, null, clock.instant(), null);
        return proposal;
    }

    /** 项目所有者即使在 Agent Run 结束后仍可查看已保存的提案。 */
    @Transactional(readOnly = true)
    public List<ExportProposal> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return proposals.list(projectId);
    }

    /** 验证项目归属后返回指定提案。 */
    @Transactional(readOnly = true)
    public ExportProposal get(UUID ownerId, UUID projectId, UUID proposalId) {
        projects.get(ownerId, projectId);
        return proposals.find(projectId, proposalId).orElseThrow(this::notFound);
    }

    /** 用户确认匹配的提案摘要后，创建唯一的项目级导出任务。 */
    @Transactional
    public Approval approve(UUID ownerId, UUID projectId, UUID proposalId,
            String displayedHash) {
        if (displayedHash == null || !displayedHash.matches("[0-9a-f]{64}")) {
            throw invalid("A valid displayed proposal hash is required");
        }
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            ExportProposal proposal = proposals.findForUpdate(projectId, proposalId)
                    .orElseThrow(this::notFound);
            if (!proposal.proposalHash().equals(displayedHash)) {
                throw conflict("Export proposal changed; reload before approval");
            }
            if (proposal.status() == ExportProposal.Status.APPROVED) {
                Task existing = tasks.get(ownerId, projectId, proposal.approvedTaskId());
                return ProjectEventService.Change.unchanged(
                        new Approval(proposal, existing, true));
            }
            if (proposal.status() != ExportProposal.Status.PENDING
                    || !inputsCurrent(ownerId, projectId, proposal)) {
                throw conflict("Export proposal is no longer current or pending");
            }
            Task task = tasks.createProjectExport(ownerId, projectId,
                    "agent-export-" + proposal.id(), proposal.input(), proposal.projectVersion());
            if (!proposals.decide(projectId, proposalId, ExportProposal.Status.APPROVED,
                    task.id(), ownerId, clock.instant())) {
                throw conflict("Export proposal changed during approval");
            }
            ExportProposal decided = proposals.find(projectId, proposalId).orElseThrow();
            events.append(ownerId, projectId, event(decided));
            return ProjectEventService.Change.unchanged(new Approval(decided, task, false));
        }).value();
    }

    /** 记录用户拒绝决定，不创建导出任务。 */
    @Transactional
    public ExportProposal reject(UUID ownerId, UUID projectId, UUID proposalId) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            ExportProposal proposal = proposals.findForUpdate(projectId, proposalId)
                    .orElseThrow(this::notFound);
            if (proposal.status() == ExportProposal.Status.REJECTED) {
                return ProjectEventService.Change.unchanged(proposal);
            }
            if (proposal.status() != ExportProposal.Status.PENDING
                    || !proposals.decide(projectId, proposalId,
                            ExportProposal.Status.REJECTED, null, ownerId, clock.instant())) {
                throw conflict("Only a pending export proposal can be rejected");
            }
            ExportProposal decided = proposals.find(projectId, proposalId).orElseThrow();
            return ProjectEventService.Change.changed(decided, event(decided));
        }).value();
    }

    /** 固定提案引用的产物版本，并拒绝跨产物、过期或不符合媒体类型的引用。 */
    private void pin(TrustedToolContext context, AgentRun run, UUID artifactId,
            UUID versionId, Artifact.Kind kind, ArrayNode pins) {
        ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                context.projectId(), context.runId(), versionId, run.contextSnapshot());
        if (!version.artifactId().equals(artifactId)) throw invalid("Version owner mismatch");
        ArtifactService.ArtifactView current = artifacts.get(context.ownerId(),
                context.projectId(), artifactId);
        if (current.artifact().kind() != kind || current.artifact().archivedAt() != null
                || !current.currentVersion().id().equals(versionId)) {
            throw invalid("Export input kind or current version does not match");
        }
        ObjectNode pin = pins.addObject();
        pin.put("artifactId", artifactId.toString());
        pin.put("versionId", versionId.toString());
        pin.put("artifactVersion", current.artifact().version());
    }

    /** 审批时逐项比对项目和素材版本；任一引用失效都使提案不可执行。 */
    private boolean inputsCurrent(UUID ownerId, UUID projectId, ExportProposal proposal) {
        if (projects.requireActiveProject(ownerId, projectId).version()
                != proposal.projectVersion()) return false;
        for (JsonNode pin : proposal.inputPins()) {
            try {
                ArtifactService.ArtifactView current = artifacts.get(ownerId, projectId,
                        UUID.fromString(pin.path("artifactId").asText()));
                if (current.artifact().archivedAt() != null
                        || current.artifact().version() != pin.path("artifactVersion").longValue()
                        || !current.currentVersion().id().toString()
                                .equals(pin.path("versionId").asText())) return false;
            } catch (ApiProblemException | IllegalArgumentException failure) {
                return false;
            }
        }
        return true;
    }

    /** 将提案状态映射为不含素材内容的项目事件。 */
    private ProjectEventService.EventDraft event(ExportProposal proposal) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("proposalId", proposal.id().toString());
        payload.put("status", proposal.status().name());
        if (proposal.approvedTaskId() != null) {
            payload.put("taskId", proposal.approvedTaskId().toString());
        }
        return new ProjectEventService.EventDraft("export.proposal.changed", 1,
                proposal.id(), proposal.status() == ExportProposal.Status.PENDING ? 0 : 1,
                payload);
    }

    /** 拒绝未纳入当前提案契约的字段，避免模型附带未校验选项。 */
    private void requireOnly(JsonNode value, Set<String> allowed) {
        for (String name : value.propertyNames()) {
            if (!allowed.contains(name)) throw invalid("Unknown export proposal field");
        }
    }

    /** 读取非空字符串字段；缺失、类型错误和纯空白均视为参数错误。 */
    private String text(JsonNode value, String field) {
        JsonNode selected = value.path(field);
        if (!selected.isTextual() || selected.asText().isBlank()) {
            throw invalid("Missing or invalid " + field);
        }
        return selected.asText();
    }

    /** 将已校验的文本字段解析为 UUID，并统一转换为稳定的参数错误。 */
    private UUID uuid(JsonNode value, String field) {
        try {
            return UUID.fromString(text(value, field));
        } catch (IllegalArgumentException failure) {
            throw invalid("Invalid " + field);
        }
    }

    /** 读取可安全转换为 int 的整数毫秒值，范围语义由导出预览继续校验。 */
    private int millisecond(JsonNode value, String field) {
        JsonNode selected = value.path(field);
        if (!selected.isIntegralNumber() || !selected.canConvertToInt()) {
            throw invalid("Invalid " + field);
        }
        return selected.intValue();
    }

    /** 对规范化输入、版本固定项和项目版本生成审批展示用摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** 构造供 Agent 参数校验失败使用的 400 问题响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                "导出提案无效", detail, false);
    }

    /** 构造提案状态或内容已变化时使用的 409 问题响应。 */
    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "EXPORT_PROPOSAL_CONFLICT",
                "导出提案已变化", detail, false);
    }

    /** 构造项目内找不到目标提案时使用的 404 问题响应。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "导出提案不存在", "该项目没有此导出提案。", false);
    }

    /** 用户审批结果及本次复用或新建的持久化任务。
     * @param proposal 审批决定后的提案状态
     * @param task 已有或新建的导出任务
     * @param replayed 是否为重复审批请求并复用原任务
     */
    public record Approval(ExportProposal proposal, Task task, boolean replayed) {}
}
